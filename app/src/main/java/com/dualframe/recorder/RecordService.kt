package com.dualframe.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.MediaActionSound
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Holds the camera and screen recorders so recording continues when the app is in the background.
 * While recording it runs in the foreground with a notification that has a Stop button.
 */
class RecordService : Service(), CameraController.Listener {
    inner class LocalBinder : Binder() { val service get() = this@RecordService }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val sound = MediaActionSound()
    lateinit var camera: CameraController
        private set
    var ui: CameraController.Listener? = null
    var screen: ScreenRecorder? = null
        private set
    var screenStartedAt = 0L
        private set
    var pendingStart = false // between the start beep and the recording actually starting
        private set
    private var blurBox: BlurBoxOverlay? = null
    private var blurBoxRect: Rect? = null
    private var blurMode = 0
    private var blurRadius = 0f

    val busy get() = camera.recording || screen != null || pendingStart

    override fun onCreate() {
        super.onCreate()
        camera = CameraController(this).also { it.listener = this }
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
        sound.load(MediaActionSound.SHUTTER_CLICK)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { camera.stopRecording(); screen?.stop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        screen?.stop()
        camera.release()
        blurBox?.remove()
        sound.release()
        super.onDestroy()
    }

    // --- camera ---

    fun takePhoto() {
        sound.play(MediaActionSound.SHUTTER_CLICK)
        camera.takePhoto()
    }

    fun startCameraRecording() {
        if (busy) return
        pendingStart = true
        goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            "Camera is recording")
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        main.postDelayed({ // start after the beep so it isn't in the files
            pendingStart = false
            if (!camera.startRecording()) { leaveForeground(); ui?.onRecordingStopped() }
        }, BEEP_MS)
    }

    fun stopCameraRecording() = camera.stopRecording()

    override fun onReady(uprightW: Int, uprightH: Int, lenses: List<Float>, hasFlash: Boolean) {
        ui?.onReady(uprightW, uprightH, lenses, hasFlash)
    }

    override fun onNotice(text: String) { ui?.onNotice(text) }

    override fun onSaved(uris: List<Uri>, ok: Boolean) {
        if (!busy) leaveForeground() // only after the files are finalized
        ui?.onSaved(uris, ok)
    }

    override fun onCropsChanged() { ui?.onCropsChanged() }

    override fun onRecordingStopped() {
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        ui?.onRecordingStopped()
    }

    // --- screen ---

    fun startScreen(resultCode: Int, data: Intent, fps: Int, geometry: ScreenGeometry) {
        if (busy) return
        camera.close()
        pendingStart = true
        // the foreground service must be running before the projection is created
        goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            "Screen is recording")
        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        main.postDelayed({
            pendingStart = false
            val s = ScreenRecorder(this, projection, fps, geometry, ::onSaved) {
                screen = null
                blurBox?.remove(); blurBox = null
                onRecordingStopped()
            }
            try {
                s.start()
            } catch (e: Exception) {
                projection.stop()
                leaveForeground()
                ui?.onNotice("Could not start screen recording: ${e.message}")
                ui?.onRecordingStopped()
                return@postDelayed
            }
            screen = s
            screenStartedAt = SystemClock.elapsedRealtime()
            applyBlur()
        }, BEEP_MS)
    }

    fun stopScreen() = screen?.stop()

    /** [mode]: 0 off, 1 inside box, 2 whole screen, 3 outside box. */
    fun setBlur(mode: Int, radiusPx: Float) {
        blurMode = mode; blurRadius = radiusPx
        applyBlur()
    }

    private fun applyBlur() {
        val s = screen ?: return
        val needsBox = (blurMode == 1 || blurMode == 3) && Settings.canDrawOverlays(this)
        if (needsBox && blurBox == null) {
            blurBox = BlurBoxOverlay(this) { r -> blurBoxRect = r; s.setBlur(blurMode, blurRadius, r) }.also { it.show() }
        } else if (!needsBox) {
            blurBox?.remove(); blurBox = null
        }
        s.setBlur(blurMode, blurRadius, blurBoxRect)
    }

    // --- foreground notification ---

    private fun goForeground(type: Int, text: String) {
        startForegroundService(Intent(this, RecordService::class.java))
        val flags = PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 0, Intent(this, RecordService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle("DualFrame")
            .setContentText(text)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        startForeground(1, n, type)
    }

    private fun leaveForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf() // the service stays alive while the activity is bound
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val ACTION_STOP = "com.dualframe.recorder.STOP"
        private const val BEEP_MS = 350L
    }
}
