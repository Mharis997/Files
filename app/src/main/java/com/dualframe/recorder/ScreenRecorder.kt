package com.dualframe.recorder

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlin.math.roundToInt

/** Full display size, status bar height ([top]) and navigation bar height ([bottom]) in pixels. */
data class ScreenGeometry(val width: Int, val height: Int, val top: Int, val bottom: Int, val dpi: Int)

/** Records the screen without the status and navigation bars, with optional blur, plus mic and phone audio. */
class ScreenRecorder(
    private val context: Context,
    private val projection: MediaProjection,
    private val fps: Int,
    private val g: ScreenGeometry,
    private val onSaved: (List<Uri>, Boolean) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val renderer = GlRenderer()
    private var recorder: Recorder? = null
    private var display: VirtualDisplay? = null
    private val crop: Crop
    private val outH: Int
    private val cropTop: Int // first screen row in the output

    init {
        val (c, h) = screenCrop(g.height, g.top, g.bottom)
        crop = c; outH = h
        cropTop = g.height - (c.y * g.height).roundToInt() - h
    }

    fun start() {
        renderer.setCrops(FULL, listOf(crop), mirrorOutputs = false)
        renderer.surfaceTexture.setDefaultBufferSize(g.width, g.height)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stop() // user ended it from the system UI
        }, Handler(Looper.getMainLooper()))
        lateinit var rec: Recorder
        // screen frames carry CLOCK_MONOTONIC timestamps, so audio uses System.nanoTime
        rec = Recorder(context, listOf(VideoSpec("screen", g.width, outH)), fps, false, projection) { uris, ok ->
            onSaved(uris, ok)
            if (recorder === rec) stop() // an encoder failed mid-take
        }
        val surfaces = try { rec.start() } catch (e: Exception) { renderer.release(); throw e }
        recorder = rec
        renderer.startEncoding(surfaces, fps)
        display = projection.createVirtualDisplay("DualFrame", g.width, g.height, g.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, Surface(renderer.surfaceTexture), null, null)
    }

    /** [boxPx] is the blur box in screen pixels; used by modes 1 (inside) and 3 (outside). */
    fun setBlur(mode: Int, radiusPx: Float, boxPx: Rect?) {
        val box = boxPx?.let { screenBox(it.left, it.top, it.right, it.bottom, g.width, cropTop, outH) } ?: Crop(0f, 0f, 0f, 0f)
        renderer.setBlur(mode, box, radiusPx)
    }

    fun stop() {
        val rec = recorder ?: return
        recorder = null
        display?.release(); display = null
        renderer.stopEncoding { rec.stop() }
        renderer.release()
        projection.stop()
        onStopped()
    }
}
