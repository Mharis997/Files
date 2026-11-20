package com.dualframe.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Pan guide over the camera view: horizon level line, a gyro dial with the turn/tilt dot, readouts and a hint. */
class PanGuideView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var meter = PanMeter()
    var level = 0f
    var targetSpeed = 20f // deg/s

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x99FFFFFF.toInt() }
    private val target = Paint(ring).apply { pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f); color = 0xCCFFFFFF.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 13 * d; textAlign = Paint.Align.CENTER
        setShadowLayer(3 * d, 0f, 0f, 0xCC000000.toInt())
    }
    private val hintPaint = Paint(text).apply { textSize = 15 * d; isFakeBoldText = true }

    private fun scoreColor(s: Float) = when {
        s >= 80 -> 0xFF34C759.toInt()
        s >= 50 -> 0xFFFFCC00.toInt()
        else -> 0xFFFF3B30.toInt()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val yaw = meter.yaw
        val pitch = meter.pitch
        val smooth = meter.smoothness

        // horizon as the camera sees it; green when within 1 degree of level
        line.color = if (abs(level) < 1f) 0xFF34C759.toInt() else 0xCCFFFFFF.toInt()
        c.save()
        c.rotate(level, cx, height / 2f)
        c.drawLine(cx - width * 0.35f, height / 2f, cx - 24 * d, height / 2f, line)
        c.drawLine(cx + 24 * d, height / 2f, cx + width * 0.35f, height / 2f, line)
        c.restore()

        // dial: edge = 2x target speed; dashed ring = target; dot = current turn (x) and tilt (y)
        val r = 64 * d
        val cy = height - r - 96 * d // clear of the lens buttons below the view
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, r / 2, target)
        c.drawLine(cx - r, cy, cx + r, cy, ring)
        c.drawLine(cx, cy - r, cx, cy + r, ring)
        var dx = -yaw / (2 * targetSpeed) * r // panning right moves the dot right
        var dy = -pitch / (2 * targetSpeed) * r // tilting up moves it up
        val len = hypot(dx, dy)
        if (len > r) { dx *= r / len; dy *= r / len }
        dot.color = scoreColor(smooth)
        c.drawCircle(cx + dx, cy + dy, 9 * d, dot)

        c.drawText("PAN ${abs(yaw).toInt()}°/s   SMOOTH ${smooth.toInt()}   LEVEL ${"%.1f".format(abs(level))}°",
            cx, 24 * d, text)
        val turning = abs(yaw)
        val hint = when {
            smooth < 50 -> "Shaky: slow down, use both hands"
            turning > targetSpeed * 1.3f -> "Too fast"
            abs(pitch) > 5f && turning > 3f -> "Tilting: keep the camera height steady"
            abs(level) > 2f -> "Not level"
            turning > targetSpeed * 0.7f && smooth >= 80 -> "Smooth pan ✓"
            turning < 3f -> "Pan slowly left or right"
            else -> "Speed up a little"
        }
        hintPaint.color = if (hint.endsWith("✓")) 0xFF34C759.toInt() else 0xFFFFFFFF.toInt()
        c.drawText(hint, cx, 48 * d, hintPaint)
    }
}
