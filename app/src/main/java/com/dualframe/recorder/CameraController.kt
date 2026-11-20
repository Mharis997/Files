package com.dualframe.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.LENS_FACING
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Camera side of the app: one camera, its GL renderer, dual-crop video recording and dual-crop photos. Main thread only. */
class CameraController(private val context: Context) {
    interface Listener {
        fun onReady(uprightW: Int, uprightH: Int, lenses: List<Float>, hasFlash: Boolean)
        fun onNotice(text: String)
        fun onSaved(uris: List<Uri>, ok: Boolean)
        fun onRecordingStopped()
    }

    var listener: Listener? = null

    // Settings survive close/open. Quality and frame rate come from [Prefs] on every open.
    var front = false; private set
    var fps = 30; private set
    var uhd = false; private set
    var photoMode = false; private set
    var zoom = 1f; private set
    var zoomRange = 1f..1f; private set
    var torch = false; private set
    var landscapeY = 0.5f; private set // 0 = top of the camera's view, 1 = bottom
    private var focus: IntArray? = null // metering rect in sensor pixels, from a tap

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val photoThread = HandlerThread("photo").apply { start() }
    private var renderer: GlRenderer? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var streamSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private val previewSurfaces = arrayOfNulls<Surface>(2)
    private var chars: CameraCharacteristics? = null
    private var orientation = 90
    private var stream = 4000 to 3000
    var upright = 3000 to 4000; private set
    var lenses = listOf(1f); private set
    var hasFlash = false; private set
    private var bootClock = false
    private var recorder: Recorder? = null
    val recording get() = recorder != null
    var recordStartedAt = 0L; private set
    val isOpen get() = renderer != null

    private fun notice(text: String) { listener?.onNotice(text) }

    /** Slot 0 shows the portrait frame, slot 1 the landscape frame. */
    fun setPreview(slot: Int, surface: Surface?) {
        previewSurfaces[slot] = surface
        renderer?.attachPreview(slot, surface)
    }

    val qualityLabel get() = "${if (uhd) "4K" else "1080p"} · $fps fps"

    fun open() {
        if (renderer != null) return
        Prefs(context).let { uhd = it.uhd; fps = if (uhd && it.fps == 60) 30 else it.fps } // 4K tops out at 30 fps: two 4K60 encodes overload most phones
        focus = null
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(LENS_FACING) == facing }
            ?: return notice("No ${if (front) "front" else "back"} camera found.")
        val c = manager.getCameraCharacteristics(id)
        chars = c
        if (fps != 30 && c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.none { it.upper == fps } == true) {
            notice("$fps fps isn't available on this camera; using 30 fps.")
            fps = 30
        }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).map { it.width to it.height }
        val minNs = { s: Pair<Int, Int> -> map.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(s.first, s.second)) }
        var choice = chooseSourceSize(sizes, fps, uhd, minNs)
        if (choice == null && fps == 60) {
            notice("60 fps isn't available on this camera; using 30 fps.")
            fps = 30
            choice = chooseSourceSize(sizes, fps, uhd, minNs)
        }
        val (size, lowDetail) = choice ?: return notice("This camera has no 4:3 video stream.")
        if (lowDetail) notice("Lower detail at $fps fps: camera stream is ${size.first}×${size.second}.")
        stream = size
        orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        bootClock = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        // the SurfaceTexture transform rotates the landscape buffer upright, so upright size is swapped for 90/270
        upright = if (orientation % 180 == 90) size.second to size.first else size

        val r = GlRenderer()
        renderer = r
        previewSurfaces.forEachIndexed { i, s -> r.attachPreview(i, s) }
        applyCrops()
        r.surfaceTexture.setDefaultBufferSize(size.first, size.second)
        val st = Surface(r.surfaceTexture)
        streamSurface = st
        val outputs = mutableListOf(st)
        if (photoMode) {
            val jpegs = map.getOutputSizes(ImageFormat.JPEG)
            // <= 12.6 MP keeps the decoded crops small enough for memory
            val js = jpegs.filter { it.width * 3 == it.height * 4 && it.width.toLong() * it.height <= 12_600_000 }
                .maxByOrNull { it.width * it.height } ?: jpegs.minBy { it.width * it.height }
            val reader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ savePhoto(it) }, Handler(photoThread.looper))
            jpegReader = reader
            outputs += reader.surface
        }
        val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        zoomRange = (zr?.lower ?: 1f)..(zr?.upper ?: 1f).coerceAtMost(10f)
        lenses = buildList {
            if (zoomRange.start < 0.95f) add(zoomRange.start)
            add(1f)
            if (zoomRange.endInclusive >= 3f) add(3f)
        }
        zoom = zoom.coerceIn(zoomRange)

        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (renderer !== r) { device.close(); return } // closed while opening
                    camera = device
                    device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        outputs.map { OutputConfiguration(it) }, context.mainExecutor, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (camera !== device) return
                                session = s
                                updateRequest()
                                hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                                listener?.onReady(upright.first, upright.second, lenses, hasFlash)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) = notice("Camera setup failed at this setting.")
                        }))
                }
                override fun onDisconnected(device: CameraDevice) = lost(device)
                override fun onError(device: CameraDevice, error: Int) {
                    lost(device)
                    notice("Camera error $error. Close other camera apps and reopen DualFrame.")
                }
            }, main)
        } catch (e: SecurityException) {
            notice("Camera permission was revoked.")
        }
    }

    private fun lost(device: CameraDevice) {
        if (camera === device) { stopRecording(); close() } else device.close()
    }

    fun close() {
        stopRecording()
        session = null
        camera?.close(); camera = null
        renderer?.release(); renderer = null
        streamSurface?.release(); streamSurface = null
        jpegReader?.close(); jpegReader = null
    }

    fun release() {
        close()
        photoThread.quitSafely()
    }

    private fun restart() { if (isOpen) { close(); open() } }

    fun setFront(value: Boolean) { front = value; zoom = 1f; restart() }
    fun setPhotoMode(value: Boolean) { photoMode = value; restart() }
    fun setZoom(value: Float) { zoom = value.coerceIn(zoomRange); focus = null; updateRequest() }
    fun setTorch(value: Boolean) { torch = value; updateRequest() }

    /** Focus and exposure on a tap at ([u], [v]) of the portrait view (0..1, v down). */
    fun focusAt(u: Float, v: Float) {
        val c = chars ?: return
        val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        focus = meterRect(currentCrops().first, u, v, front, zoom, orientation, a.width(), a.height())
        updateRequest()
    }

    fun setLandscapeY(value: Float) { landscapeY = value.coerceIn(0f, 1f); applyCrops() }

    fun currentCrops() = crops(upright.first, upright.second, landscapeY = landscapeY)

    private fun applyCrops() {
        val (p, l) = currentCrops()
        // the preview is mirrored for the front camera; files are not
        renderer?.setCrops(listOf(p, l), listOf(p, l), mirrorOutputs = front)
    }

    private fun fpsRange() = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.let { r ->
        r.firstOrNull { it.lower == fps && it.upper == fps } ?: r.filter { it.upper == fps }.maxByOrNull { it.lower }
    }

    private fun CaptureRequest.Builder.common() {
        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        focus?.let { f ->
            val region = arrayOf(MeteringRectangle(f[0], f[1], f[2] - f[0], f[3] - f[1], MeteringRectangle.METERING_WEIGHT_MAX))
            if ((chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) set(CaptureRequest.CONTROL_AF_REGIONS, region)
            if ((chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) set(CaptureRequest.CONTROL_AE_REGIONS, region)
        }
    }

    private fun updateRequest() {
        val s = session ?: return
        val d = camera ?: return
        val st = streamSurface ?: return
        val req = d.createCaptureRequest(if (photoMode) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(st)
            common()
            fpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }.build()
        runCatching { s.setRepeatingRequest(req, null, null) }
    }

    fun startRecording(): Boolean {
        val r = renderer ?: return false
        if (recording || photoMode) return false
        lateinit var rec: Recorder
        val (short, long) = if (uhd) 2160 to 3840 else 1080 to 1920
        rec = Recorder(context, listOf(VideoSpec("portrait", short, long), VideoSpec("landscape", long, short)),
            fps, bootClock) { uris, ok ->
            listener?.onSaved(uris, ok)
            if (recorder === rec) { // an encoder failed mid-take: the recorder already cleaned up
                recorder = null
                renderer?.stopEncoding {}
                listener?.onRecordingStopped()
            }
        }
        val surfaces = try { rec.start() } catch (e: Exception) {
            notice("Could not start recording at ${qualityLabel}: ${e.message}" + if (uhd) " Try 1080p in Settings." else "")
            return false
        }
        recorder = rec
        recordStartedAt = SystemClock.elapsedRealtime()
        r.startEncoding(surfaces)
        return true
    }

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        val r = renderer
        if (r != null) r.stopEncoding { rec.stop() } else rec.stop()
        listener?.onRecordingStopped()
    }

    fun takePhoto() {
        val d = camera ?: return
        val s = session ?: return
        val jr = jpegReader ?: return
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jr.surface)
            common()
            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
        }.build()
        runCatching { s.capture(req, null, null) }
    }

    /** Photo thread: cuts the portrait and landscape crops out of one JPEG and saves both. */
    private fun savePhoto(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        val w = image.width
        val h = image.height
        val bytes = image.use { val b = it.planes[0].buffer; ByteArray(b.remaining()).also { a -> b.get(a) } }
        val (p, l) = currentCrops()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION") val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)!!
        val parts = listOf("portrait" to p, "landscape" to l)
        for ((name, crop0) in parts) {
            // crops are set on the preview, which is mirrored for the front camera; the JPEG is not
            val crop = if (front) crop0.copy(x = 1f - crop0.x - crop0.w) else crop0
            val r = sensorRect(crop, orientation, w, h)
            val part = decoder.decodeRegion(Rect(r[0], r[1], r[2], r[3]), null)
            val upright = if (orientation == 0) part else
                Bitmap.createBitmap(part, 0, 0, part.width, part.height, Matrix().apply { postRotate(orientation.toFloat()) }, true)
            saveJpeg(upright, "DualFrame_${stamp}_$name.jpg")?.let { uris += it }
            if (upright !== part) upright.recycle()
            part.recycle()
        }
        decoder.recycle()
        main.post { listener?.onSaved(uris, uris.size == parts.size) }
    }

    private fun saveJpeg(bmp: Bitmap, fileName: String): Uri? {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DualFrame")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: return null
        val ok = runCatching { resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } }.getOrDefault(false)
        if (!ok) { resolver.delete(uri, null, null); return null }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
