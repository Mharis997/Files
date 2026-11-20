package com.dualframe.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Rule-of-thirds lines. */
class GridOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply { color = 0x66FFFFFF; strokeWidth = resources.displayMetrics.density }

    override fun onDraw(canvas: Canvas) {
        for (i in 1..2) {
            val x = width * i / 3f; val y = height * i / 3f
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
    }
}
