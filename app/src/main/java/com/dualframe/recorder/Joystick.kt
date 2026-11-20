package com.dualframe.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Thumb stick: while held, [onMove] gets the deflection (-1..1, -1..1, y down) every frame; springs back on release. */
class Joystick(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var onMove: ((dx: Float, dy: Float, dtSeconds: Float) -> Unit)? = null
    private var dx = 0f
    private var dy = 0f
    private var lastFrame = 0L
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF4D8DFF.toInt() }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }

    init { contentDescription = "Joystick: moves the portrait frame" }

    private val radius get() = min(width, height) / 2f
    private val knobRadius get() = radius * 0.38f

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        c.drawCircle(cx, cy, radius - 2f, base)
        c.drawCircle(cx, cy, radius - 2f, ring)
        val travel = radius - knobRadius
        c.drawCircle(cx + dx * travel, cy + dy * travel, knobRadius, knob)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            onMove?.invoke(dx, dy, (now - lastFrame) / 1e9f)
            lastFrame = now
            if (dx != 0f || dy != 0f) postOnAnimation(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val travel = radius - knobRadius
                var x = (e.x - width / 2f) / travel
                var y = (e.y - height / 2f) / travel
                val len = hypot(x, y)
                if (len > 1f) { x /= len; y /= len }
                val wasIdle = dx == 0f && dy == 0f
                dx = x; dy = y
                if (wasIdle) { lastFrame = System.nanoTime(); postOnAnimation(tick) }
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dx = 0f; dy = 0f }
        }
        invalidate()
        return true
    }
}
