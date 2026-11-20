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
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
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
import kotlin.math.roundToInt

/** Camera side of the app: one camera, its GL renderer, dual-crop video recording and dual-crop photos. Main thread only. */
class CameraController(private val context: Context) {
    interface Listener {
        fun onReady(uprightW: Int, uprightH: Int, lenses: List<Float>, hasFlash: Boolean)
        fun onNotice(text: String)
        fun onSaved(uris: List<Uri>, ok: Boolean)
        fun onRecordingStopped()
    }

    var listener: Listener? = null

    // Settings survive close/open.
    var front = false; private set
    var fps = 30; private set
    var photoMode = false; private set
    var zoom = 1f; private set
    var torch = false; private set
    var torchLevel = 1; private set
    var portraitX = 0.5f; private set
    var portraitY = 0.5f; private set
    var landscapeY = 0.5f; private set

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val photoThread = HandlerThread("photo").apply { start() }
    private var renderer: GlRenderer? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var streamSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var chars: CameraCharacteristics? = null
    private var orientation = 90
    private var stream = 4000 to 3000
    var upright = 3000 to 4000; private set
    var lenses = listOf(1f); private set
    var hasFlash = false; private set
    /** Highest torch brightness step this camera accepts; 1 means brightness can't be changed. */
    var maxTorchLevel = 1; private set
    private var bootClock = false
    private var recorder: Recorder? = null
    val recording get() = recorder != null
    var recordStartedAt = 0L; private set
    val isOpen get() = renderer != null

    private fun notice(text: String) { listener?.onNotice(text) }

    fun setPreview(surface: Surface?) {
        previewSurface = surface
        renderer?.attachPreview(surface)
    }

    fun open() {
        if (renderer != null) return
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(LENS_FACING) == facing }
            ?: return notice("No ${if (front) "front" else "back"} camera found.")
        val c = manager.getCameraCharacteristics(id)
        chars = c
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).map { it.width to it.height }
        val minNs = { s: Pair<Int, Int> -> map.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(s.first, s.second)) }
        var choice = chooseSourceSize(sizes, fps, minNs)
        if (choice == null && fps == 60) {
            notice("60 fps isn't available on this camera; using 30 fps.")
            fps = 30
            choice = chooseSourceSize(sizes, fps, minNs)
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
        r.attachPreview(previewSurface)
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
        val zoomRange = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        lenses = buildList {
            if (zoomRange != null && zoomRange.lower < 0.95f) add(zoomRange.lower)
            add(1f)
            if (zoomRange != null && zoomRange.upper >= 3f) add(3f)
        }
        zoom = zoom.coerceIn(zoomRange?.lower ?: 1f, zoomRange?.upper ?: 1f)

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
                                maxTorchLevel = if (Build.VERSION.SDK_INT >= 35)
                                    c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1 else 1
                                torchLevel = torchLevel.coerceIn(1, maxTorchLevel)
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
    fun setFps(value: Int) { fps = value; restart() }
    fun setPhotoMode(value: Boolean) { photoMode = value; restart() }
    fun setZoom(value: Float) { zoom = value; updateRequest() }
    fun setTorch(value: Boolean) { torch = value; updateRequest() }
    fun setTorchLevel(value: Int) { torchLevel = value.coerceIn(1, maxTorchLevel); updateRequest() }


    fun setPositions(portraitX: Float, portraitY: Float, landscapeY: Float) {
        this.portraitX = portraitX.coerceIn(0f, 1f)
        this.portraitY = portraitY.coerceIn(0f, 1f)
        this.landscapeY = landscapeY.coerceIn(0f, 1f)
        applyCrops()
    }

    fun currentCrops() = crops(upright.first, upright.second, portraitX, portraitY, landscapeY)

    private fun applyCrops() {
        val (p, l) = currentCrops()
        // the preview is mirrored for the front camera; files are not
        renderer?.setCrops(FULL, listOf(p, l), mirrorOutputs = front)
    }

    private fun fpsRange() = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.let { r ->
        r.firstOrNull { it.lower == fps && it.upper == fps } ?: r.filter { it.upper == fps }.maxByOrNull { it.lower }
    }

    private fun CaptureRequest.Builder.common() {
        if (Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        if (torch && maxTorchLevel > 1 && Build.VERSION.SDK_INT >= 35) set(CaptureRequest.FLASH_STRENGTH_LEVEL, torchLevel)
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
        rec = Recorder(context, listOf(VideoSpec("portrait", 1080, 1920), VideoSpec("landscape", 1920, 1080)),
            fps, bootClock) { uris, ok ->
            listener?.onSaved(uris, ok)
            if (recorder === rec) { // an encoder failed mid-take: the recorder already cleaned up
                recorder = null
                renderer?.stopEncoding {}
                listener?.onRecordingStopped()
            }
        }
        val surfaces = try { rec.start() } catch (e: Exception) {
            notice("Could not start recording: ${e.message}")
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

    /** What the current setup records, what is left out, and what every camera on the phone can do. */
    fun describe(): String = buildString {
        if (chars != null) {
            val (uw, uh) = upright
            val (p, l) = currentCrops()
            val rec = recordedFraction(p, l)
            appendLine("NOW (${if (front) "front" else "back"} camera, ${"%.1f".format(zoom)}×, $fps fps)")
            appendLine("Camera stream: ${stream.first}×${stream.second} → upright ${uw}×${uh}")
            appendLine("Portrait file: ${(p.w * uw).roundToInt()}×${(p.h * uh).roundToInt()} crop → 1080×1920")
            appendLine("Landscape file: ${(l.w * uw).roundToInt()}×${(l.h * uh).roundToInt()} crop → 1920×1080")
            appendLine("Recorded: ${(rec * 100).roundToInt()}% of the camera's view")
            appendLine("Missing: ${((1 - rec) * 100).roundToInt()}% (the dimmed areas in the preview)")
            appendLine()
        }
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val facing = if (c.get(LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Back"
            appendLine("$facing camera $id")
            c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                appendLine("  Sensor: ${it.width}×${it.height} (${"%.1f".format(it.width * it.height / 1e6)} MP)")
            }
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let { f ->
                appendLine("  Focal length: ${f.joinToString { "%.1f mm".format(it) }}")
            }
            if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                appendLine("  Zoom: ${"%.1f".format(it.lower)}× – ${"%.0f".format(it.upper)}×")
            }
            if (c.physicalCameraIds.isNotEmpty()) appendLine("  Lenses inside: ${c.physicalCameraIds.size} (${c.physicalCameraIds.joinToString()})")
            val video = map.getOutputSizes(MediaRecorder::class.java).maxByOrNull { it.width * it.height }
            video?.let {
                val fps = 1_000_000_000L / map.getOutputMinFrameDuration(MediaRecorder::class.java, it).coerceAtLeast(1)
                appendLine("  Max video: ${it.width}×${it.height} @ $fps fps")
            }
            val slowMo = map.highSpeedVideoFpsRanges.maxByOrNull { it.upper }
            slowMo?.let { r ->
                val s = map.getHighSpeedVideoSizesFor(r).maxByOrNull { it.width * it.height }
                appendLine("  Slow motion: ${r.upper} fps${s?.let { " at ${it.width}×${it.height}" } ?: ""}")
            }
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val tenBit = Build.VERSION.SDK_INT >= 33 &&
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT in caps
            appendLine("  10-bit HDR video: ${if (tenBit) "yes" else "no"}")
            appendLine("  RAW photos: ${if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps) "yes" else "no"}")
            val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
            appendLine("  Stabilization: optical ${if (ois) "yes" else "no"}, electronic ${if (eis) "yes" else "no"}")
            appendLine("  Flash: ${if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) "yes" else "no"}")
            appendLine()
        }
        append("DualFrame doesn't use: 4K/8K, slow motion, 10-bit HDR, RAW, stabilization. Each file is 1080p H.264.")
    }

}
