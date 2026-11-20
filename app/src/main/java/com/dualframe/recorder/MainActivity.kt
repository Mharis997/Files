package com.dualframe.recorder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Bundle
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Chronometer
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SurfaceHolder.Callback {
    private val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private lateinit var preview: SurfaceView
    private lateinit var recordButton: Button
    private lateinit var timer: Chronometer
    private lateinit var notice: TextView

    private var renderer: GlRenderer? = null
    private var camera: CameraDevice? = null
    private var cameraSurface: Surface? = null
    private var recorder: Recorder? = null
    private var bootClock = false
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        recordButton = findViewById(R.id.record)
        timer = findViewById(R.id.timer)
        notice = findViewById(R.id.notice)
        val square = findViewById<View>(R.id.square)
        square.post { square.layoutParams = square.layoutParams.apply { height = square.width } }
        preview.holder.addCallback(this)
        recordButton.setOnClickListener { if (recorder == null) startRecording() else stopRecording() }
        if (!hasPerms()) requestPermissions(perms, 1)
    }

    private fun hasPerms() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasPerms()) { if (surfaceReady) openCamera() } else showNotice("Camera and microphone access are needed to record.")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (hasPerms()) openCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        closeCamera()
    }

    override fun onPause() {
        stopRecording() // leaving the app finalizes both files
        super.onPause()
    }

    private fun showNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
    }

    private fun openCamera() {
        if (renderer != null) return
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return showNotice("No back camera found.")
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).map { it.width to it.height }
        val (size, lowDetail) = chooseSourceSize(sizes) {
            map.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(it.first, it.second))
        } ?: return showNotice("This camera has no 4:3 video stream at 30 fps.")
        if (lowDetail) showNotice("Lower detail on this phone: camera stream is ${size.first}×${size.second}.")

        // the SurfaceTexture transform rotates the landscape buffer upright, so upright size is swapped for 90/270
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        bootClock = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val (uw, uh) = if (orientation % 180 == 90) size.second to size.first else size
        val (portrait, landscape) = squareCrops(uw, uh)

        val r = GlRenderer(preview.holder.surface)
        renderer = r
        r.setCrops(square(uw, uh), portrait, landscape)
        r.surfaceTexture.setDefaultBufferSize(size.first, size.second)
        val surface = Surface(r.surfaceTexture)
        cameraSurface = surface
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.firstOrNull { it.lower == 30 && it.upper == 30 }

        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(surface)), mainExecutor, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(surface)
                                    if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                                }.build()
                                runCatching { session.setRepeatingRequest(req, null, null) }
                                recordButton.isEnabled = true
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) = showNotice("Camera setup failed.")
                        }))
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); camera = null
                    showNotice("Camera error $error. Close other camera apps and reopen DualFrame.")
                }
            }, null)
        } catch (e: SecurityException) {
            showNotice("Camera permission was revoked.")
        }
    }

    private fun closeCamera() {
        recordButton.isEnabled = false
        camera?.close()
        camera = null
        renderer?.release()
        renderer = null
        cameraSurface?.release()
        cameraSurface = null
    }

    private fun startRecording() {
        val r = renderer ?: return
        lateinit var rec: Recorder
        rec = Recorder(this, bootClock) { saved ->
            Toast.makeText(this, if (saved) "Saved to Movies/DualFrame" else "Recording failed, nothing saved", Toast.LENGTH_LONG).show()
            if (recorder === rec) { // an encoder failed mid-take: the recorder already cleaned up
                recorder = null
                resetRecordUi()
                renderer?.stopEncoding {}
            }
        }
        val (p, l) = try { rec.start() } catch (e: Exception) {
            Toast.makeText(this, "Could not start recording: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        recorder = rec
        r.startEncoding(p, l)
        timer.base = SystemClock.elapsedRealtime()
        timer.start()
        recordButton.text = "■ STOP"
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        resetRecordUi()
        val r = renderer
        if (r != null) r.stopEncoding { rec.stop() } else rec.stop()
    }

    private fun resetRecordUi() {
        timer.stop()
        recordButton.text = "● REC"
    }
}
