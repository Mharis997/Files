package com.dualframe.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Vertical slider, value 0 (top) to 1 (bottom). */
class VerticalSlider(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var onChange: ((Float) -> Unit)? = null
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; color = 0x59FFFFFF }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF8A3D.toInt() }
    private val pad get() = width / 2f

    init { contentDescription = "Slider: moves the landscape frame up or down" }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        c.drawLine(cx, pad, cx, height - pad, track)
        c.drawCircle(cx, pad + value * (height - 2 * pad), width / 2.6f, thumb)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            value = (e.y - pad) / (height - 2 * pad)
            onChange?.invoke(value)
            parent.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }
}
