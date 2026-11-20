package com.dualframe.recorder

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two H.264 encoders (portrait, landscape) and one AAC encoder. Each file gets its own video track
 * and the same audio samples. [useBootTimeClock] must match the camera's timestamp source for A/V sync.
 */
class Recorder(
    private val context: Context,
    private val useBootTimeClock: Boolean,
    private val onDone: (saved: Boolean) -> Unit, // runs on the main thread, also after an encoder failure
) {

    private class Sample(val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private inner class Output(name: String, stamp: String) {
        val uri: Uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "DualFrame_${stamp}_$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualFrame")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }) ?: error("Could not create $name file")
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "rw")!!
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoFormat: MediaFormat? = null
        var videoTrack = -1
        var audioTrack = -1
        var started = false
        var videoDone = false
        val pending = ArrayList<Sample>() // video that arrives before the muxer can start; keeps the first keyframe
    }

    private val lock = Any()
    private val codecThread = HandlerThread("video-codec").apply { start() }
    private val audioThread = HandlerThread("audio-codec").apply { start() }
    private lateinit var portrait: Output
    private lateinit var landscape: Output
    private lateinit var portraitEnc: MediaCodec
    private lateinit var landscapeEnc: MediaCodec
    private lateinit var audioEnc: MediaCodec
    private lateinit var audio: AudioRecord
    private var audioFormat: MediaFormat? = null
    private var audioDone = false
    private var finished = false
    private var lastAudioPtsUs = 0L
    @Volatile private var stopping = false

    private fun nowNs() = if (useBootTimeClock) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    /** Starts the encoders; returns the (portrait, landscape) input surfaces for the GL renderer. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the record button is enabled
    fun start(): Pair<Surface, Surface> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        portrait = Output("portrait", stamp)
        landscape = Output("landscape", stamp)

        val rate = 48_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audio = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, 65_536)) // 4x the 16 KB read size, so reads never drain it
        audioEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            setCallback(audioCallback, Handler(audioThread.looper))
            configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        portraitEnc = videoEncoder(1080, 1920, portrait)
        landscapeEnc = videoEncoder(1920, 1080, landscape)
        val surfaces = portraitEnc.createInputSurface() to landscapeEnc.createInputSurface()
        audio.startRecording()
        audioEnc.start()
        portraitEnc.start()
        landscapeEnc.start()
        return surfaces
    }

    /** Call after the GL renderer stopped drawing to the encoder surfaces. */
    fun stop() {
        stopping = true // the audio encoder queues its end-of-stream on its next input buffer
        // runCatching: an encoder may have failed and been released already; finish() reports that
        runCatching { portraitEnc.signalEndOfInputStream() }
        runCatching { landscapeEnc.signalEndOfInputStream() }
    }

    private fun videoEncoder(w: Int, h: Int, out: Output) =
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = synchronized(lock) {
                    out.videoFormat = format
                    tryStart(out)
                }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    synchronized(lock) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buf = codec.getOutputBuffer(index)!!
                            if (out.started) write(out, out.videoTrack, buf, info) else out.pending += copy(buf, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { out.videoDone = true; maybeFinish() }
                    }
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail(e)
            }, Handler(codecThread.looper))
            configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 16_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

    private val audioCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (stopping) {
                codec.queueInputBuffer(index, 0, 0, lastAudioPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            val buf = codec.getInputBuffer(index)!!
            val n = audio.read(buf, buf.capacity()).coerceAtLeast(0)
            // timestamp of the first sample in this chunk, on the camera's clock
            val pts = (nowNs() / 1000 - n / 2 * 1_000_000L / 48_000).coerceAtLeast(lastAudioPtsUs + 1)
            lastAudioPtsUs = pts
            codec.queueInputBuffer(index, 0, n, pts, 0)
        }
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = synchronized(lock) {
            audioFormat = format
            tryStart(portrait); tryStart(landscape)
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            synchronized(lock) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val buf = codec.getOutputBuffer(index)!!
                    for (out in listOf(portrait, landscape)) if (out.started) write(out, out.audioTrack, buf, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { audioDone = true; maybeFinish() }
            }
        }
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail(e)
    }

    private fun copy(buf: ByteBuffer, info: MediaCodec.BufferInfo): Sample {
        buf.position(info.offset); buf.limit(info.offset + info.size)
        val data = ByteBuffer.allocate(info.size).put(buf).apply { flip() }
        return Sample(data, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
    }

    private fun write(out: Output, track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        buf.position(info.offset); buf.limit(info.offset + info.size)
        out.muxer.writeSampleData(track, buf, info)
    }

    private fun tryStart(out: Output) {
        if (!::portrait.isInitialized || out.started) return
        val v = out.videoFormat ?: return
        val a = audioFormat ?: return
        out.videoTrack = out.muxer.addTrack(v)
        out.audioTrack = out.muxer.addTrack(a)
        out.muxer.start()
        out.started = true
        out.pending.forEach { out.muxer.writeSampleData(out.videoTrack, it.data, it.info) }
        out.pending.clear()
    }

    private fun maybeFinish() {
        if (finished || !portrait.videoDone || !landscape.videoDone || !audioDone) return
        finished = true
        Handler(Looper.getMainLooper()).post { finish() } // codecs must not be released from their own callbacks
    }

    private fun fail(e: Exception) {
        Log.e("DualFrame", "Encoder error", e)
        synchronized(lock) {
            if (finished) return
            finished = true
        }
        Handler(Looper.getMainLooper()).post { finish(forceFail = true) }
    }

    private fun finish(forceFail: Boolean = false) {
        var ok = !forceFail
        for (out in listOf(portrait, landscape)) {
            val saved = !forceFail && out.started && runCatching { out.muxer.stop() }.isSuccess
            runCatching { out.muxer.release() }
            runCatching { out.pfd.close() }
            if (saved) {
                context.contentResolver.update(out.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                ok = false
                context.contentResolver.delete(out.uri, null, null)
            }
        }
        for (c in listOf(portraitEnc, landscapeEnc, audioEnc)) { runCatching { c.stop() }; c.release() }
        runCatching { audio.stop() }
        audio.release()
        codecThread.quitSafely()
        audioThread.quitSafely()
        onDone(ok)
    }
}
