package com.dualframe.recorder

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.q1080).setOnClickListener { prefs.uhd = false; refresh() }
        findViewById<View>(R.id.q4k).setOnClickListener { prefs.uhd = true; refresh() }
        findViewById<View>(R.id.fps24).setOnClickListener { prefs.fps = 24; refresh() }
        findViewById<View>(R.id.fps30).setOnClickListener { prefs.fps = 30; refresh() }
        findViewById<View>(R.id.fps60).setOnClickListener { if (!prefs.uhd) { prefs.fps = 60; refresh() } }
        findViewById<Switch>(R.id.grid).apply {
            isChecked = prefs.grid
            setOnCheckedChangeListener { _, on -> prefs.grid = on }
        }
        findViewById<TextView>(R.id.version).text =
            "DualFrame ${packageManager.getPackageInfo(packageName, 0).versionName}"
        refresh()
    }

    private fun refresh() {
        val sel = { id: Int, on: Boolean -> findViewById<View>(id).isSelected = on }
        sel(R.id.q1080, !prefs.uhd); sel(R.id.q4k, prefs.uhd)
        val fps = if (prefs.uhd && prefs.fps == 60) 30 else prefs.fps
        sel(R.id.fps24, fps == 24); sel(R.id.fps30, fps == 30); sel(R.id.fps60, fps == 60)
        val (w, h) = if (prefs.uhd) 3840 to 2160 else 1920 to 1080
        findViewById<TextView>(R.id.size).text = "≈ ${megabytesPerMinute(w, h, fps)} MB per minute (both files)"
        findViewById<View>(R.id.fps60).apply { isEnabled = !prefs.uhd; alpha = if (prefs.uhd) 0.35f else 1f }
        findViewById<TextView>(R.id.qualityNote).text = if (prefs.uhd)
            "Both files are 3840×2160 at up to 30 fps. Keeps every pixel the camera streams; on most phones the landscape file is upscaled (held upright, the sensor is only ~3000 px wide). Uses about 3× the storage of 1080p."
        else
            "Both files are 1920×1080, downscaled from the camera stream, so they stay sharp. Smallest files."
    }
}
