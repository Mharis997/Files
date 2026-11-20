package com.dualframe.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Dims what neither file records and outlines the portrait (blue) and landscape (orange) frames. */
class FrameOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; this.color = color
    }
    private val portraitPaint = stroke(0xFF4D8DFF.toInt())
    private val landscapePaint = stroke(0xFFFF8A3D.toInt())
    private val dim = 0x99000000.toInt()
    private var portrait = crops(3, 4).first
    private var landscape = crops(3, 4).second

    fun setCrops(p: Crop, l: Crop) {
        portrait = p; landscape = l
        invalidate()
    }

    // Crop uses GL coordinates (origin bottom-left); views are top-left
    private fun rect(c: Crop) = RectF(c.x * width, (1 - c.y - c.h) * height, (c.x + c.w) * width, (1 - c.y) * height)

    override fun onDraw(c: Canvas) {
        val p = rect(portrait)
        val l = rect(landscape)
        c.save()
        c.clipOutRect(p)
        c.clipOutRect(l)
        c.drawColor(dim)
        c.restore()
        val inset = 2.5f // keep strokes inside the view at the edges
        c.drawRect(p.left + inset, p.top + inset, p.right - inset, p.bottom - inset, portraitPaint)
        c.drawRect(l.left + inset, l.top + inset, l.right - inset, l.bottom - inset, landscapePaint)
    }
}
