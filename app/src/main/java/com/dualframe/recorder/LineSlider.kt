package com.dualframe.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Slider along the view's long side: value 0 at the top (or left) to 1 at the bottom (or right). */
class LineSlider(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var onChange: ((Float) -> Unit)? = null
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color: Int
        get() = thumb.color
        set(c) { thumb.color = c; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; color = 0x59FFFFFF }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8A3D.toInt() }
    private val horizontal get() = width > height
    private val pad get() = minOf(width, height) / 2f
    private val length get() = (if (horizontal) width else height) - 2 * pad

    override fun onDraw(c: Canvas) {
        val mid = minOf(width, height) / 2f
        val at = pad + value * length
        if (horizontal) {
            c.drawLine(pad, mid, width - pad, mid, track)
            c.drawCircle(at, mid, mid / 1.3f, thumb)
        } else {
            c.drawLine(mid, pad, mid, height - pad, track)
            c.drawCircle(mid, at, mid / 1.3f, thumb)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            value = ((if (horizontal) e.x else e.y) - pad) / length
            onChange?.invoke(value)
            parent.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }
}
