package com.dualframe.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Floating box over other apps that marks the blur area while screen recording.
 * Drag inside to move, drag the bottom-right corner to resize. Its faint outline shows in the recording.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class BlurBoxOverlay(context: Context, private val onChange: (Rect) -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val handle = 36 * density
    private val params = WindowManager.LayoutParams(
        (240 * density).toInt(), (140 * density).toInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = (60 * density).toInt(); y = (300 * density).toInt() }

    private val view = object : View(context) {
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0x66FFFFFF
            pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
        override fun onDraw(c: Canvas) {
            c.drawRect(1f, 1f, width - 1f, height - 1f, outline)
            c.drawCircle(width - handle / 3, height - handle / 3, 5 * density, corner)
        }
    }.apply {
        contentDescription = "Blur area. Drag to move, drag the corner to resize."
        var startRawX = 0f; var startRawY = 0f
        var startX = 0; var startY = 0; var startW = 0; var startH = 0
        var resizing = false
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = e.rawX; startRawY = e.rawY
                    startX = params.x; startY = params.y; startW = params.width; startH = params.height
                    resizing = e.x > v.width - handle && e.y > v.height - handle
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - startRawX).toInt(); val dy = (e.rawY - startRawY).toInt()
                    if (resizing) {
                        params.width = maxOf((48 * density).toInt(), startW + dx)
                        params.height = maxOf((48 * density).toInt(), startH + dy)
                    } else {
                        params.x = startX + dx; params.y = startY + dy
                    }
                    wm.updateViewLayout(v, params)
                    report()
                }
            }
            true
        }
    }

    private fun report() = onChange(Rect(params.x, params.y, params.x + params.width, params.y + params.height))

    fun show() {
        wm.addView(view, params)
        report()
    }

    fun remove() = runCatching { wm.removeView(view) }
}
