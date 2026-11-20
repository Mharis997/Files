package com.dualframe.recorder

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Normalized rect in the upright frame, GL convention (origin bottom-left). */
data class Crop(val x: Float, val y: Float, val w: Float, val h: Float)

val FULL = Crop(0f, 0f, 1f, 1f)

/**
 * (portrait 9:16, landscape 16:9) at the same scale: both fit a square whose side is the frame's short edge.
 * Positions run 0..1: [portraitX] left to right, [portraitY] and [landscapeY] top to bottom.
 */
fun crops(
    uprightW: Int, uprightH: Int,
    portraitX: Float = 0.5f, portraitY: Float = 0.5f, landscapeY: Float = 0.5f,
): Pair<Crop, Crop> {
    val s = min(uprightW, uprightH).toFloat()
    val short = s * 9f / 16f
    val pw = short / uprightW; val ph = s / uprightH
    val lw = s / uprightW; val lh = short / uprightH
    return Crop((1f - pw) * portraitX, (1f - ph) * (1f - portraitY), pw, ph) to
        Crop((1f - lw) / 2f, (1f - lh) * (1f - landscapeY), lw, lh)
}

/** Triangle wave for panning: 0 -> 1 -> 0 as [phase] goes 0 -> 1 -> 2, repeating. */
fun pingPong(phase: Float): Float {
    val t = phase % 2f
    return if (t <= 1f) t else 2f - t
}

/**
 * Picks the camera stream size from sensor-orientation sizes for [fps].
 * Returns (size, lowDetail): the smallest 4:3 size with short edge >= 1920 (no upscaling to 1080p),
 * otherwise the largest 4:3 size flagged lowDetail, otherwise null.
 */
fun chooseSourceSize(sizes: List<Pair<Int, Int>>, fps: Int, minFrameNs: (Pair<Int, Int>) -> Long): Pair<Pair<Int, Int>, Boolean>? {
    val ok = sizes.filter { (w, h) -> w * 3 == h * 4 && minFrameNs(w to h) <= 1_000_000_000L / fps + 1 }
    ok.filter { min(it.first, it.second) >= 1920 }.minByOrNull { it.first * it.second }?.let { return it to false }
    return ok.maxByOrNull { it.first * it.second }?.let { it to true }
}

/**
 * Column-major 4x4 mapping quad UV (0..1) into [crop] of the upright frame, flipped sideways if [mirror].
 * Multiply the SurfaceTexture transform on the left; Camera2 already puts the sensor rotation in that transform.
 */
fun texMatrix(crop: Crop, mirror: Boolean = false) = floatArrayOf(
    if (mirror) -crop.w else crop.w, 0f, 0f, 0f,
    0f, crop.h, 0f, 0f,
    0f, 0f, 1f, 0f,
    if (mirror) crop.x + crop.w else crop.x, crop.y, 0f, 1f,
)

/** Pixel rect [left, top, right, bottom] in an unrotated sensor image (e.g. a JPEG) for an upright [crop]. */
fun sensorRect(crop: Crop, orientation: Int, sensorW: Int, sensorH: Int): IntArray {
    val (uw, uh) = if (orientation % 180 == 90) sensorH to sensorW else sensorW to sensorH
    val l = crop.x * uw; val r = (crop.x + crop.w) * uw
    val t = (1f - crop.y - crop.h) * uh; val b = (1f - crop.y) * uh
    val f = when (orientation) {
        90 -> floatArrayOf(t, sensorH - r, b, sensorH - l)
        180 -> floatArrayOf(sensorW - r, sensorH - b, sensorW - l, sensorH - t)
        270 -> floatArrayOf(sensorW - b, l, sensorW - t, r)
        else -> floatArrayOf(l, t, r, b)
    }
    return IntArray(4) { f[it].roundToInt() }
}

/** Share of the camera frame that ends up in at least one of the two files. */
fun recordedFraction(p: Crop, l: Crop): Float {
    val ox = max(0f, min(p.x + p.w, l.x + l.w) - max(p.x, l.x))
    val oy = max(0f, min(p.y + p.h, l.y + l.h) - max(p.y, l.y))
    return p.w * p.h + l.w * l.h - ox * oy
}

/** Crop of a full-screen capture without status bar ([top] px) and navigation bar ([bottom] px); height rounded down to 16. */
fun screenCrop(screenH: Int, top: Int, bottom: Int): Pair<Crop, Int> {
    val avail = screenH - top - bottom
    val h = avail / 16 * 16
    return Crop(0f, (bottom + (avail - h) / 2).toFloat() / screenH, 1f, h.toFloat() / screenH) to h
}

/** A box in screen pixels mapped into the output frame of [screenCrop] (output starts at screen row [cropTop]). */
fun screenBox(left: Int, top: Int, right: Int, bottom: Int, screenW: Int, cropTop: Int, cropH: Int) = Crop(
    left.toFloat() / screenW, 1f - (bottom - cropTop).toFloat() / cropH,
    (right - left).toFloat() / screenW, (bottom - top).toFloat() / cropH,
)
