package com.dualframe.recorder

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
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
    private lateinit var seekPortrait: SeekBar
    private lateinit var seekLandscape: SeekBar
    private lateinit var seekBlur: SeekBar
    private lateinit var shutterInner: View
    private lateinit var screenShutterInner: View

    private var service: RecordService? = null
    private val cam get() = service?.camera
    private var surfaceReady = false
    private var cameraTab = true
    private var blurMode = 0
    private var pendingScreen: Pair<Int, Intent>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as RecordService.LocalBinder).service
            service = s
            s.ui = this@MainActivity
            if (s.screen != null) cameraTab = false
            startPendingScreen()
            if (cameraTab && hasPerms()) openCamera()
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
        seekPortrait = findViewById(R.id.seekPortrait)
        seekLandscape = findViewById(R.id.seekLandscape)
        seekBlur = findViewById(R.id.seekBlur)
        shutterInner = findViewById(R.id.shutterInner)
        screenShutterInner = findViewById(R.id.screenShutterInner)
        preview.holder.addCallback(this)

        findViewById<View>(R.id.tabCamera).setOnClickListener { switchTab(true) }
        findViewById<View>(R.id.tabScreen).setOnClickListener { switchTab(false) }
        flash.setOnClickListener { cam?.let { it.setTorch(!it.torch); refresh() } }
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
        findViewById<View>(R.id.gallery).setOnClickListener { openGallery() }
        findViewById<View>(R.id.gallery2).setOnClickListener { openGallery() }
        findViewById<View>(R.id.shutter).setOnClickListener { onShutter() }
        findViewById<View>(R.id.screenShutter).setOnClickListener { onScreenShutter() }
        findViewById<View>(R.id.showTaps).setOnClickListener { openShowTaps() }
        listOf(R.id.blurOff, R.id.blurBox, R.id.blurWhole, R.id.blurOutside).forEachIndexed { mode, id ->
            findViewById<View>(id).setOnClickListener { blurMode = mode; pushBlur(); refresh() }
        }

        val slider = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (bar === seekBlur) return pushBlur()
                cam?.setPositions(seekPortrait.progress / 100f, seekLandscape.progress / 100f)
                updateOverlay()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        }
        seekPortrait.setOnSeekBarChangeListener(slider)
        seekLandscape.setOnSeekBarChangeListener(slider)
        seekBlur.setOnSeekBarChangeListener(slider)
        seekBlur.progress = 40

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
        if (hasPerms()) { if (cameraTab) openCamera() } else onNotice("Camera and microphone access are needed to record.")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (cameraTab) cam?.setPreview(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        cam?.setPreview(null)
    }

    private fun openCamera() {
        val s = service ?: return
        val c = s.camera
        if (s.screen != null || (s.pendingStart && !c.isOpen)) return // screen recording owns the session
        c.setPreview(if (surfaceReady) preview.holder.surface else null)
        c.open()
        if (c.isOpen) onReady(c.upright.first, c.upright.second, c.lenses, c.hasFlash)
    }

    private fun switchTab(camera: Boolean) {
        val s = service ?: return
        if (s.busy || camera == cameraTab) return
        cameraTab = camera
        infoPanel.visibility = View.GONE
        if (camera) { if (hasPerms()) openCamera() } else s.camera.close()
        refresh()
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

    private fun onScreenShutter() {
        val s = service ?: return
        if (s.screen != null) return s.stopScreen().let { refresh() }
        if (s.busy) return
        if ((blurMode == 1 || blurMode == 3) && !Settings.canDrawOverlays(this)) {
            toast("Allow DualFrame to appear on top (for the blur box), then tap record again.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return requestPermissions(perms, 1)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQ_SCREEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_SCREEN) return
        if (resultCode != RESULT_OK || data == null) return toast("Screen recording was not allowed.")
        pendingScreen = resultCode to data
        startPendingScreen() // the service may not be bound yet; onServiceConnected retries
    }

    private fun startPendingScreen() {
        val s = service ?: return
        val (code, data) = pendingScreen ?: return
        pendingScreen = null
        s.setBlur(blurMode, blurRadius())
        s.startScreen(code, data, s.camera.fps, geometry())
        moveTaskToBack(true) // get out of the way so the user records other apps
    }

    private fun geometry(): ScreenGeometry {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val bars = windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        return ScreenGeometry(bounds.width(), bounds.height(), bars.top, bars.bottom, resources.displayMetrics.densityDpi)
    }

    private fun blurRadius() = 4f + seekBlur.progress * 0.6f // 4..64 px

    private fun pushBlur() { service?.setBlur(blurMode, blurRadius()) }

    private fun openGallery() {
        val uri = service?.lastSaved
        val intent = if (uri != null) Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
        runCatching { startActivity(intent) }.onFailure { toast("No gallery app found.") }
    }

    private fun openShowTaps() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            toast("Scroll to Input and turn on “Show taps”. Touches will appear in screen recordings.")
        }.onFailure {
            toast("Turn on Developer options first: Settings › About phone › Software information › tap Build number 7 times.")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun updateOverlay() {
        val c = cam ?: return
        val (p, l) = crops(c.upright.first, c.upright.second, c.portraitPos, c.landscapePos)
        overlay.setCrops(p, l)
    }

    /** Syncs every control with the service's state. */
    private fun refresh() {
        val s = service ?: return
        val c = s.camera
        val selected = { v: Int, on: Boolean -> findViewById<View>(v).setBackgroundResource(if (on) R.drawable.pill_selected else 0) }
        selected(R.id.tabCamera, cameraTab)
        selected(R.id.tabScreen, !cameraTab)
        previewBox.visibility = if (cameraTab) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.screenHint).visibility = if (cameraTab) View.GONE else View.VISIBLE
        findViewById<View>(R.id.cameraControls).visibility = if (cameraTab) View.VISIBLE else View.GONE
        findViewById<View>(R.id.screenControls).visibility = if (cameraTab) View.GONE else View.VISIBLE
        flash.visibility = if (cameraTab && c.hasFlash) View.VISIBLE else View.INVISIBLE
        flash.setImageResource(if (c.torch) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        fpsChip.text = "${c.fps} FPS"
        selected(R.id.modePhoto, c.photoMode)
        selected(R.id.modeVideo, !c.photoMode)
        listOf(R.id.blurOff, R.id.blurBox, R.id.blurWhole, R.id.blurOutside).forEachIndexed { i, id -> selected(id, i == blurMode) }
        seekPortrait.progress = (c.portraitPos * 100).toInt()
        seekLandscape.progress = (c.landscapePos * 100).toInt()
        for (i in 0 until lensRow.childCount) {
            val chip = lensRow.getChildAt(i)
            chip.setBackgroundResource(if (chip.tag == c.zoom) R.drawable.pill_selected else 0)
        }

        val recording = c.recording || s.screen != null || s.pendingStart
        timer.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        if (c.recording) { timer.base = c.recordStartedAt; timer.start() }
        if (s.screen != null) { timer.base = s.screenStartedAt; timer.start() }
        if (!recording) timer.stop()
        styleShutter(shutterInner, if (c.photoMode) SHUTTER_PHOTO else if (c.recording || s.pendingStart) SHUTTER_STOP else SHUTTER_RECORD)
        styleShutter(screenShutterInner, if (s.screen != null) SHUTTER_STOP else SHUTTER_RECORD)
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
        private const val REQ_SCREEN = 2
        private const val SHUTTER_RECORD = 0
        private const val SHUTTER_STOP = 1
        private const val SHUTTER_PHOTO = 2
    }
}
