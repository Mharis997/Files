package com.dualframe.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Outlines the portrait (blue) and landscape (orange) frames inside the square preview. */
class FrameOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; this.color = color
    }
    private val portrait = stroke(0xFF4D8DFF.toInt())
    private val landscape = stroke(0xFFFF8A3D.toInt())

    override fun onDraw(c: Canvas) {
        val s = min(width, height) - 6f // inset by the stroke so edges stay visible
        val k = s * 9f / 16f
        val cx = width / 2f
        val cy = height / 2f
        c.drawRect(cx - k / 2, cy - s / 2, cx + k / 2, cy + s / 2, portrait)
        c.drawRect(cx - s / 2, cy - k / 2, cx + s / 2, cy + k / 2, landscape)
    }
}
