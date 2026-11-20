package com.dualframe.recorder

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SurfaceHolder.Callback, CameraController.Listener {
    private val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var previewBox: View
    private lateinit var preview: SurfaceView
    private lateinit var card: View
    private lateinit var cardView: TextureView
    private lateinit var grid: View
    private lateinit var focusRing: View
    private lateinit var flash: ImageButton
    private lateinit var settings: View
    private lateinit var quality: TextView
    private lateinit var recPill: View
    private lateinit var recDot: View
    private var volumeZoom = false
    private var zoomDir = 0 // +1 in, -1 out, 0 idle: set while a volume button is held
    private var lastZoomFrameNs = 0L
    private lateinit var timer: Chronometer
    private lateinit var notice: TextView
    private lateinit var lensRow: LinearLayout
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var gallery: ImageView
    private lateinit var shutter: View
    private lateinit var shutterInner: View
    private lateinit var flip: View
    private lateinit var thumbs: Thumbnails

    private var service: RecordService? = null
    private val cam get() = service?.camera
    private var surfaceReady = false
    private var cardSurface: Surface? = null

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
        card = findViewById(R.id.card)
        cardView = findViewById(R.id.cardView)
        grid = findViewById(R.id.grid)
        focusRing = findViewById(R.id.focusRing)
        flash = findViewById(R.id.flash)
        settings = findViewById(R.id.settings)
        quality = findViewById(R.id.quality)
        recPill = findViewById(R.id.recPill)
        recDot = findViewById(R.id.recDot)
        timer = findViewById(R.id.timer)
        notice = findViewById(R.id.notice)
        lensRow = findViewById(R.id.lenses)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        gallery = findViewById(R.id.gallery)
        shutter = findViewById(R.id.shutter)
        shutterInner = findViewById(R.id.shutterInner)
        flip = findViewById(R.id.flip)
        thumbs = Thumbnails(this)

        // Android 15 draws apps under the system bars: keep the controls clear of the nav bar and camera cutout
        val bars = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        for ((id, top) in listOf(R.id.topBar to true, R.id.controls to false)) {
            val v = findViewById<View>(id)
            val base = if (top) v.paddingTop else v.paddingBottom
            v.setOnApplyWindowInsetsListener { view, insets ->
                val i = insets.getInsets(bars)
                if (top) view.setPadding(view.paddingLeft, base + i.top, view.paddingRight, view.paddingBottom)
                else view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, base + i.bottom)
                insets
            }
        }
        preview.holder.addCallback(this)
        cardView.surfaceTextureListener = cardListener
        rounded(card, 14f); rounded(gallery, 12f)
        previewBox.post { // the portrait file is 9:16: show exactly that, full width
            previewBox.layoutParams = previewBox.layoutParams.apply { height = previewBox.width * 16 / 9 }
            card.layoutParams = card.layoutParams.apply { height = previewBox.width * 9 / 16 }
            card.post { placeCard() }
        }
        var grabY = 0f
        var grabT = 0f
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { grabY = e.rawY; grabT = v.translationY }
                MotionEvent.ACTION_MOVE -> {
                    val range = cardRange()
                    val t = (grabT + e.rawY - grabY).coerceIn(0f, range)
                    v.translationY = t
                    cam?.setLandscapeY(if (range > 0) t / range else 0.5f)
                }
            }
            true
        }
        timer.setOnChronometerTickListener {
            val s = (SystemClock.elapsedRealtime() - it.base).coerceAtLeast(0) / 1000
            it.text = "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        }

        flash.setOnClickListener { cam?.let { it.setTorch(!it.torch); refresh() } }
        settings.setOnClickListener { if (service?.busy == false) startActivity(Intent(this, SettingsActivity::class.java)) }
        gallery.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        modePhoto.setOnClickListener { setPhotoMode(true) }
        modeVideo.setOnClickListener { setPhotoMode(false) }
        flip.setOnClickListener {
            val c = cam ?: return@setOnClickListener
            if (!service!!.busy) c.setFront(!c.front)
        }
        shutter.setOnClickListener { onShutter() }

        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                cam?.let { it.setZoom(it.zoom * d.scaleFactor); refresh() }
                return true
            }
        })
        preview.setOnTouchListener { v, e ->
            pinch.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !pinch.isInProgress && e.eventTime - e.downTime < 300) {
                cam?.focusAt(e.x / v.width, e.y / v.height)
                showFocusRing(e.x, e.y)
            }
            true
        }

        if (!hasPerms()) requestPermissions(perms, 1)
    }

    override fun onStart() {
        super.onStart()
        Prefs(this).let {
            grid.visibility = if (it.grid) View.VISIBLE else View.GONE
            volumeZoom = it.volumeZoom
        }
        bindService(Intent(this, RecordService::class.java), connection, BIND_AUTO_CREATE)
        loadLatest()
    }

    override fun onStop() {
        zoomDir = 0
        service?.let {
            it.camera.setPreview(0, null)
            it.camera.setPreview(1, null)
            if (!it.busy) it.camera.close() // keep the camera only while it is recording
            it.ui = null
            unbindService(connection)
        }
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        thumbs.shutdown()
        super.onDestroy()
    }

    private fun hasPerms() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasPerms()) openCamera() else onNotice("Camera and microphone access are needed to record.")
    }

    // --- preview surfaces: 0 = full-screen portrait, 1 = landscape card ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        cam?.setPreview(0, holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        cam?.setPreview(0, null)
    }

    private val cardListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            cardSurface = Surface(st)
            cam?.setPreview(1, cardSurface)
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            cam?.setPreview(1, null)
            cardSurface?.release(); cardSurface = null
            return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    private fun openCamera() {
        val c = cam ?: return
        c.setPreview(0, if (surfaceReady) preview.holder.surface else null)
        c.setPreview(1, cardSurface)
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
            c.photoMode -> { s.takePhoto(); shutter.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction { shutter.animate().scaleX(1f).scaleY(1f).setDuration(80) } }
            c.recording -> s.stopCameraRecording()
            !s.busy -> {
                s.startCameraRecording()
                timer.base = SystemClock.elapsedRealtime() + 350
                timer.start()
            }
        }
        refresh()
    }

    private fun showFocusRing(x: Float, y: Float) {
        focusRing.animate().cancel()
        focusRing.x = previewBox.x + x - focusRing.width / 2f
        focusRing.y = previewBox.y + y - focusRing.height / 2f
        focusRing.alpha = 1f; focusRing.scaleX = 1.3f; focusRing.scaleY = 1.3f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300)
        }
    }

    /** How far the landscape view can travel: from the top edge of the screen to the bottom edge. */
    private fun cardRange() = ((card.parent as View).height - card.height).coerceAtLeast(0).toFloat()

    private fun placeCard() { card.translationY = (cam?.landscapeY ?: 0.5f) * cardRange() }

    // --- volume buttons: shutter, or smooth zoom while held ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dir = when (keyCode) { KeyEvent.KEYCODE_VOLUME_UP -> 1; KeyEvent.KEYCODE_VOLUME_DOWN -> -1; else -> return super.onKeyDown(keyCode, event) }
        if (event.repeatCount > 0) return true // held: the zoom loop is already running
        if (!volumeZoom) onShutter()
        else {
            if (zoomDir == 0) { lastZoomFrameNs = 0L; Choreographer.getInstance().postFrameCallback(zoomTick) }
            zoomDir = dir
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyUp(keyCode, event)
        zoomDir = 0
        return true
    }

    /** Once per display frame: zoom by the time since the last frame, so the speed is steady whatever the frame rate. */
    private val zoomTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameNs: Long) {
            val c = cam
            if (zoomDir == 0 || c == null) { zoomDir = 0; return }
            if (lastZoomFrameNs != 0L) {
                val dt = (frameNs - lastZoomFrameNs) / 1e9f
                c.setZoom(c.zoom * kotlin.math.exp(zoomDir * ZOOM_PER_SECOND * dt))
                refresh()
            }
            lastZoomFrameNs = frameNs
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun rounded(v: View, radiusDp: Float) {
        val r = radiusDp * resources.displayMetrics.density
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, r)
        }
        v.clipToOutline = true
    }

    private fun loadLatest() = thumbs.background({ queryCaptures(this).firstOrNull() }) { c ->
        if (c == null) {
            gallery.tag = null
            gallery.setImageResource(R.drawable.ic_gallery)
            gallery.scaleType = ImageView.ScaleType.CENTER
        } else {
            gallery.scaleType = ImageView.ScaleType.CENTER_CROP
            thumbs.load(c.uri, gallery)
        }
    }

    /** Syncs every control with the service's state. */
    private fun refresh() {
        val s = service ?: return
        val c = s.camera
        val recording = c.recording || s.pendingStart
        flash.visibility = if (c.hasFlash) View.VISIBLE else View.INVISIBLE
        flash.setImageResource(if (c.torch) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        flash.contentDescription = if (c.torch) "Turn light off" else "Turn light on"
        quality.text = if (c.photoMode) "9:16 · PHOTO" else "9:16 · ${c.qualityLabel}"
        recPill.setBackgroundResource(if (recording) R.drawable.rec_pill else 0)
        recDot.visibility = if (recording) View.VISIBLE else View.GONE
        recPill.visibility = if (c.photoMode) View.INVISIBLE else View.VISIBLE
        if (c.recording) { timer.base = c.recordStartedAt; timer.start() }
        if (!recording) { timer.stop(); timer.text = "00:00:00" }
        modePhoto.isSelected = c.photoMode
        modeVideo.isSelected = !c.photoMode
        // can't switch camera, mode or settings mid-take
        for (v in listOf(modePhoto, modeVideo, flip, settings, gallery)) v.alpha = if (recording) 0.3f else 1f
        for (i in 0 until lensRow.childCount) {
            val chip = lensRow.getChildAt(i) as TextView
            val z = chip.tag as Float
            val near = lensRow.childCount == 1 || z == c.lenses.lastOrNull { c.zoom >= it - 0.01f }
            chip.isSelected = near
            chip.text = if (near && kotlin.math.abs(c.zoom - z) > 0.05f) "%.1f×".format(c.zoom) else lensText(z)
        }
        shutterStyle(when { c.photoMode -> SHUTTER_PHOTO; recording -> SHUTTER_STOP; else -> SHUTTER_RECORD })
        shutter.contentDescription = if (c.photoMode) "Take photo" else if (recording) "Stop recording" else "Record"
    }

    private fun lensText(z: Float) = if (z < 1f) "%.1f×".format(z) else "%.0f×".format(z)

    private fun shutterStyle(style: Int) {
        val d = 62 * resources.displayMetrics.density
        shutterInner.background = GradientDrawable().apply {
            setColor(if (style == SHUTTER_PHOTO) 0xFFFFFFFF.toInt() else 0xFFFF3B30.toInt())
            cornerRadius = if (style == SHUTTER_STOP) d * 0.14f else d / 2
        }
        shutterInner.animate().scaleX(if (style == SHUTTER_STOP) 0.5f else 1f).scaleY(if (style == SHUTTER_STOP) 0.5f else 1f).setDuration(150)
    }

    // --- CameraController.Listener (forwarded by the service) ---

    override fun onReady(uprightW: Int, uprightH: Int, lenses: List<Float>, hasFlash: Boolean) {
        card.post { placeCard() }
        lensRow.removeAllViews()
        for (z in lenses) {
            lensRow.addView(TextView(this, null, 0, R.style.Lens).apply {
                text = lensText(z)
                tag = z
                contentDescription = "Zoom $text"
                setOnClickListener { cam?.setZoom(z); refresh() }
            })
        }
        refresh()
    }

    override fun onNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
        main.removeCallbacksAndMessages(notice)
        main.postAtTime({ notice.visibility = View.GONE }, notice, SystemClock.uptimeMillis() + 5000)
    }

    override fun onSaved(uris: List<Uri>, ok: Boolean) {
        val what = if (uris.firstOrNull()?.toString()?.contains("images") == true) "photos" else "videos"
        Toast.makeText(this, when {
            ok -> "Saved 9:16 and 16:9 $what"
            uris.isNotEmpty() -> "Only ${uris.size} file saved; the other failed."
            else -> "Saving failed; nothing was saved."
        }, Toast.LENGTH_SHORT).show()
        loadLatest()
    }

    override fun onRecordingStopped() = refresh()

    companion object {
        private const val SHUTTER_RECORD = 0
        private const val SHUTTER_STOP = 1
        private const val SHUTTER_PHOTO = 2
        private const val ZOOM_PER_SECOND = 0.6f // natural log: about 1.8x per second held
    }
}
