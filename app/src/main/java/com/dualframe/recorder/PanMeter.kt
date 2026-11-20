package com.dualframe.recorder

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Turns gyroscope samples into a pan guide: smoothed turn speed and tilt speed (deg/s) and a
 * 0..100 smoothness score from how much the raw rate shakes around the smoothed one.
 * Phone upright: y-axis rate is panning (positive = left), x-axis rate is tilting (positive = up).
 */
class PanMeter(private val tauSeconds: Float = 0.4f) {
    var yaw = 0f; private set
    var pitch = 0f; private set
    private var jitterSq = 0f
    private var started = false

    fun update(wxRad: Float, wyRad: Float, dtSeconds: Float) {
        val y = Math.toDegrees(wyRad.toDouble()).toFloat()
        val p = Math.toDegrees(wxRad.toDouble()).toFloat()
        if (!started) { yaw = y; pitch = p; started = true; return }
        val a = dtSeconds / (tauSeconds + dtSeconds)
        yaw += a * (y - yaw)
        pitch += a * (p - pitch)
        val e = (y - yaw) * (y - yaw) + (p - pitch) * (p - pitch)
        jitterSq += a * (e - jitterSq)
    }

    /** RMS shake in deg/s; 12 deg/s of shake or more scores 0. */
    val smoothness get() = (100f * (1f - sqrt(jitterSq) / 12f)).coerceIn(0f, 100f)
}

/** Horizon tilt in degrees from gravity on the screen's x/y axes; 0 when the phone is upright and level. */
fun levelDegrees(gx: Float, gy: Float) = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
