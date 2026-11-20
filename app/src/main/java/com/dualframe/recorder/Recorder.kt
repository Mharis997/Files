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
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VideoSpec(val name: String, val width: Int, val height: Int)

/** H.264 bitrate for one file. */
fun videoBitrate(w: Int, h: Int, fps: Int) = when {
    w * h > 4_000_000 -> if (fps > 30) 60_000_000 else 45_000_000 // 4K
    fps > 30 -> 24_000_000
    else -> 16_000_000
}

/** Storage for one minute of both files with audio, in MB. */
fun megabytesPerMinute(w: Int, h: Int, fps: Int) = 2L * (videoBitrate(w, h, fps) + AUDIO_BITRATE) * 60 / 8 / 1_000_000

const val AUDIO_BITRATE = 128_000

/**
 * One H.264 encoder per [VideoSpec] and one AAC encoder (mic) whose samples go into every file.
 * [useBootTimeClock] must match the video timestamps' clock for A/V sync.
 */
class Recorder(
    private val context: Context,
    private val specs: List<VideoSpec>,
    private val fps: Int,
    private val useBootTimeClock: Boolean,
    private val onDone: (saved: List<Uri>, ok: Boolean) -> Unit, // main thread, also after an encoder failure
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
        var encoder: MediaCodec? = null
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
    private val outputs = ArrayList<Output>()
    private lateinit var audioEnc: MediaCodec
    private val audioIn = ArrayList<AudioRecord>()
    private var pcm = ShortArray(0)
    private var audioFormat: MediaFormat? = null
    private var audioDone = false
    private var finished = false
    private var lastAudioPtsUs = 0L
    @Volatile private var stopping = false

    private fun nowNs() = if (useBootTimeClock) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    /** Starts the encoders; returns each spec's input surface with its size, in [specs] order. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before recording is offered
    fun start(): List<Triple<Surface, Int, Int>> = try {
        startUnsafe()
    } catch (e: Exception) {
        // undo a half-built start so no empty files or threads are left behind
        outputs.forEach {
            it.encoder?.release(); runCatching { it.muxer.release() }; runCatching { it.pfd.close() }
            context.contentResolver.delete(it.uri, null, null)
        }
        if (::audioEnc.isInitialized) audioEnc.release()
        audioIn.forEach { it.release() }
        codecThread.quitSafely(); audioThread.quitSafely()
        throw e
    }

    @SuppressLint("MissingPermission")
    private fun startUnsafe(): List<Triple<Surface, Int, Int>> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val rate = 48_000
        val fmt = AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val bufBytes = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4, 65_536) // 4x the 16 KB read size
        audioIn += AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(fmt).setBufferSizeInBytes(bufBytes).build()
        audioEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            setCallback(audioCallback, Handler(audioThread.looper))
            configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surfaces = specs.map { spec ->
            val out = Output(spec.name, stamp).also { outputs += it }
            out.encoder = videoEncoder(spec.width, spec.height, out)
            Triple(out.encoder!!.createInputSurface(), spec.width, spec.height)
        }
        audioIn.forEach { it.startRecording() }
        audioEnc.start()
        outputs.forEach { it.encoder!!.start() }
        return surfaces
    }

    /** Call after the GL renderer stopped drawing to the encoder surfaces. */
    fun stop() {
        stopping = true // the audio encoder queues its end-of-stream on its next input buffer
        // runCatching: an encoder may have failed and been released already; finish() reports that
        outputs.forEach { runCatching { it.encoder?.signalEndOfInputStream() } }
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
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate(w, h, fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

    private val audioCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (stopping) {
                codec.queueInputBuffer(index, 0, 0, lastAudioPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            val buf = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            val want = buf.capacity() / 2
            if (pcm.size < want) pcm = ShortArray(want)
            val n = audioIn[0].read(pcm, 0, want).coerceAtLeast(0) // blocking: the mic paces the audio
            buf.asShortBuffer().put(pcm, 0, n)
            // timestamp of the first sample in this chunk, on the video's clock
            val pts = (nowNs() / 1000 - n * 1_000_000L / 48_000).coerceAtLeast(lastAudioPtsUs + 1)
            lastAudioPtsUs = pts
            codec.queueInputBuffer(index, 0, n * 2, pts, 0)
        }
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = synchronized(lock) {
            audioFormat = format
            outputs.forEach { tryStart(it) }
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            synchronized(lock) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val buf = codec.getOutputBuffer(index)!!
                    for (out in outputs) if (out.started) write(out, out.audioTrack, buf, info)
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
        if (out.started) return
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
        if (finished || !audioDone || outputs.any { !it.videoDone }) return
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
        val saved = ArrayList<Uri>()
        for (out in outputs) {
            val ok = !forceFail && out.started && runCatching { out.muxer.stop() }.isSuccess
            runCatching { out.muxer.release() }
            runCatching { out.pfd.close() }
            if (ok) {
                context.contentResolver.update(out.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                saved += out.uri
            } else {
                context.contentResolver.delete(out.uri, null, null)
            }
        }
        for (c in outputs.mapNotNull { it.encoder } + audioEnc) { runCatching { c.stop() }; c.release() }
        audioIn.forEach { runCatching { it.stop() }; it.release() }
        codecThread.quitSafely()
        audioThread.quitSafely()
        onDone(saved, saved.size == outputs.size)
    }
}
