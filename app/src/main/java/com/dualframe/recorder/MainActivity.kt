package com.dualframe.recorder

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SurfaceHolder.Callback, CameraController.Listener {
    private val perms = listOfNotNull(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null).toTypedArray()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var previewBox: View
    private lateinit var preview: SurfaceView
    private lateinit var overlay: FrameOverlay
    private lateinit var flash: ImageButton
    private lateinit var fpsChip: TextView
    private lateinit var timer: Chronometer
    private lateinit var notice: TextView
    private lateinit var infoPanel: ScrollView
    private lateinit var lensRow: LinearLayout
    private lateinit var joystick: Joystick
    private lateinit var landscapeSlider: VerticalSlider
    private lateinit var seekFlash: SeekBar
    private lateinit var shutterInner: View

    private var service: RecordService? = null
    private val cam get() = service?.camera
    private var surfaceReady = false
    private var flashLevelNoticeShown = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as RecordService.LocalBinder).service
            service = s
            s.ui = this@MainActivity
            if (hasPerms()) openCamera()
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        previewBox = findViewById(R.id.previewBox)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        flash = findViewById(R.id.flash)
        fpsChip = findViewById(R.id.fps)
        timer = findViewById(R.id.timer)
        notice = findViewById(R.id.notice)
        infoPanel = findViewById(R.id.infoPanel)
        lensRow = findViewById(R.id.lenses)
        joystick = findViewById(R.id.joystick)
        landscapeSlider = findViewById(R.id.landscapeSlider)
        seekFlash = findViewById(R.id.seekFlash)
        shutterInner = findViewById(R.id.shutterInner)
        preview.holder.addCallback(this)

        flash.setOnClickListener {
            val c = cam ?: return@setOnClickListener
            c.setTorch(!c.torch)
            if (c.torch && c.maxTorchLevel <= 1 && !flashLevelNoticeShown) {
                flashLevelNoticeShown = true
                onNotice("This phone doesn't let apps change flash brightness.")
            }
            refresh()
        }
        fpsChip.setOnClickListener {
            val c = cam ?: return@setOnClickListener
            if (service!!.busy) return@setOnClickListener
            c.setFps(if (c.fps == 30) 60 else 30)
            refresh()
        }
        findViewById<View>(R.id.info).setOnClickListener {
            infoPanel.visibility = if (infoPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            findViewById<TextView>(R.id.infoText).text = cam?.describe() ?: ""
        }
        infoPanel.setOnClickListener { infoPanel.visibility = View.GONE }
        findViewById<View>(R.id.modePhoto).setOnClickListener { setPhotoMode(true) }
        findViewById<View>(R.id.modeVideo).setOnClickListener { setPhotoMode(false) }
        findViewById<View>(R.id.flip).setOnClickListener {
            val c = cam ?: return@setOnClickListener
            if (!service!!.busy) c.setFront(!c.front)
        }
        findViewById<View>(R.id.shutter).setOnClickListener { onShutter() }

        val slider = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                cam?.let { it.setTorchLevel(1 + value * (it.maxTorchLevel - 1) / 100) }
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        }
        seekFlash.max = 100
        seekFlash.setOnSeekBarChangeListener(slider)
        joystick.onMove = { dx, dy, dt ->
            cam?.let {
                val step = 0.8f * dt // full sweep in about 1.25 s at full tilt
                it.setPositions(it.portraitX + dx * step, it.portraitY + dy * step, it.landscapeY)
                updateOverlay()
            }
        }
        landscapeSlider.onChange = { v ->
            cam?.let { it.setPositions(it.portraitX, it.portraitY, v); updateOverlay() }
        }

        if (!hasPerms()) requestPermissions(perms, 1)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RecordService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.let {
            it.camera.setPreview(null)
            if (!it.busy) it.camera.close() // keep the camera only while it is recording
            it.ui = null
            unbindService(connection)
        }
        service = null
        super.onStop()
    }

    private fun hasPerms() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasPerms()) openCamera() else onNotice("Camera and microphone access are needed to record.")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        cam?.setPreview(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        cam?.setPreview(null)
    }

    private fun openCamera() {
        val c = cam ?: return
        c.setPreview(if (surfaceReady) preview.holder.surface else null)
        c.open()
        if (c.isOpen) onReady(c.upright.first, c.upright.second, c.lenses, c.hasFlash)
    }

    private fun setPhotoMode(photo: Boolean) {
        val c = cam ?: return
        if (service!!.busy || c.photoMode == photo) return
        c.setPhotoMode(photo)
        refresh()
    }

    private fun onShutter() {
        val s = service ?: return
        val c = s.camera
        when {
            c.photoMode -> s.takePhoto()
            c.recording -> s.stopCameraRecording()
            !s.busy -> {
                s.startCameraRecording()
                timer.base = SystemClock.elapsedRealtime() + 350
                timer.start()
            }
        }
        refresh()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun updateOverlay() {
        val c = cam ?: return
        val (p, l) = c.currentCrops()
        overlay.setCrops(p, l)
        landscapeSlider.value = c.landscapeY
    }

    /** Syncs every control with the service's state. */
    private fun refresh() {
        val s = service ?: return
        val c = s.camera
        val selected = { v: Int, on: Boolean -> findViewById<View>(v).setBackgroundResource(if (on) R.drawable.pill_selected else 0) }
        seekFlash.visibility = if (c.torch && c.maxTorchLevel > 1) View.VISIBLE else View.INVISIBLE
        seekFlash.progress = if (c.maxTorchLevel > 1) (c.torchLevel - 1) * 100 / (c.maxTorchLevel - 1) else 0
        flash.visibility = if (c.hasFlash) View.VISIBLE else View.INVISIBLE
        flash.setImageResource(if (c.torch) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        fpsChip.text = "${c.fps} FPS"
        selected(R.id.modePhoto, c.photoMode)
        selected(R.id.modeVideo, !c.photoMode)
        for (i in 0 until lensRow.childCount) {
            val chip = lensRow.getChildAt(i)
            chip.setBackgroundResource(if (chip.tag == c.zoom) R.drawable.pill_selected else 0)
        }

        val recording = c.recording || s.pendingStart
        timer.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        if (c.recording) { timer.base = c.recordStartedAt; timer.start() }
        if (!recording) timer.stop()
        styleShutter(shutterInner, if (c.photoMode) SHUTTER_PHOTO else if (c.recording || s.pendingStart) SHUTTER_STOP else SHUTTER_RECORD)
        findViewById<View>(R.id.shutter).contentDescription = if (c.photoMode) "Take photo" else if (c.recording) "Stop recording" else "Record"
    }

    private fun styleShutter(v: View, style: Int) {
        val d = 60 * resources.displayMetrics.density
        v.background = GradientDrawable().apply {
            setColor(if (style == SHUTTER_PHOTO) 0xFFFFFFFF.toInt() else 0xFFFF3B30.toInt())
            cornerRadius = if (style == SHUTTER_STOP) d * 0.15f else d / 2
        }
        v.scaleX = if (style == SHUTTER_STOP) 0.55f else 1f
        v.scaleY = v.scaleX
    }

    // --- CameraController.Listener (forwarded by the service) ---

    override fun onReady(uprightW: Int, uprightH: Int, lenses: List<Float>, hasFlash: Boolean) {
        previewBox.post {
            previewBox.layoutParams = previewBox.layoutParams.apply { height = previewBox.width * uprightH / uprightW }
        }
        lensRow.removeAllViews()
        for (z in lenses) {
            lensRow.addView(TextView(this, null, 0, R.style.Chip).apply {
                text = if (z < 1f) "%.1f×".format(z) else "%.0f×".format(z)
                tag = z
                contentDescription = "Zoom $text"
                setOnClickListener { cam?.setZoom(z); refresh() }
            })
        }
        updateOverlay()
        refresh()
    }

    override fun onNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
        main.removeCallbacksAndMessages(notice)
        main.postAtTime({ notice.visibility = View.GONE }, notice, SystemClock.uptimeMillis() + 5000)
    }

    override fun onSaved(uris: List<Uri>, ok: Boolean) {
        val what = if (uris.firstOrNull()?.toString()?.contains("images") == true) "Pictures" else "Movies"
        toast(when {
            ok -> "Saved ${uris.size} file${if (uris.size > 1) "s" else ""} to $what/DualFrame"
            uris.isNotEmpty() -> "Only ${uris.size} file saved; the other failed."
            else -> "Saving failed; nothing was saved."
        })
    }

    override fun onRecordingStopped() = refresh()


    companion object {
        private const val SHUTTER_RECORD = 0
        private const val SHUTTER_STOP = 1
        private const val SHUTTER_PHOTO = 2
    }
}
