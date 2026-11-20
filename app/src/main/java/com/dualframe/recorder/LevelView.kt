package com.dualframe.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/** Small level: a mini horizon that tilts like the real one, and the tilt as 0.0°. Green within 0.5° of level. */
class LevelView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * d; textAlign = Paint.Align.RIGHT; isFakeBoldText = true }
    private var shown = Float.NaN

    /** Degrees from [levelDegrees]; smoothed so the number is readable. */
    fun update(level: Float) {
        shown = if (level.isNaN() || shown.isNaN()) level else shown + 0.15f * (level - shown)
        contentDescription = if (shown.isNaN()) "Level unavailable" else "Tilted %.1f degrees".format(abs(shown))
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        c.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, bg)
        val level = !shown.isNaN() && abs(shown) < 0.5f
        val color = if (level) 0xFF34C759.toInt() else 0xFFFFFFFF.toInt()
        line.color = color; text.color = color
        val cx = h * 0.75f; val cy = h / 2
        c.save()
        if (!shown.isNaN()) c.rotate(shown, cx, cy) // the real horizon's tilt on screen
        c.drawLine(cx - h * 0.4f, cy, cx + h * 0.4f, cy, line)
        c.restore()
        c.drawText(if (shown.isNaN()) "--.-°" else "%.1f°".format(abs(shown)), width - h * 0.35f, cy + text.textSize / 3, text)
    }
}
