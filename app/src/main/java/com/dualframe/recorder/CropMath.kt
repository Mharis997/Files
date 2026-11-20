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

/**
 * Picks the camera stream size (sensor orientation) for [fps]. Returns (size, lowDetail); lowDetail = short edge < 1920,
 * so 1080p output is upscaled. Any shape works: the crops only need the short edge.
 * Normally: among sizes with short edge >= 1920, the squarest (4:3 keeps the whole sensor in view), then the smallest.
 * Otherwise, or with [largest] (4K): the biggest short edge, then the squarest.
 * Phones often run 60 fps only on 16:9 streams, which is why shape isn't fixed. [fourThreeOnly] is for photos (4:3 JPEGs).
 */
fun chooseSourceSize(
    sizes: List<Pair<Int, Int>>, fps: Int, largest: Boolean = false, fourThreeOnly: Boolean = false,
    minFrameNs: (Pair<Int, Int>) -> Long,
): Pair<Pair<Int, Int>, Boolean>? {
    val short = { s: Pair<Int, Int> -> min(s.first, s.second) }
    val squareness = { s: Pair<Int, Int> -> short(s).toFloat() / max(s.first, s.second) }
    val ok = sizes.filter { (w, h) -> (!fourThreeOnly || w * 3 == h * 4) && minFrameNs(w to h) <= 1_000_000_000L / fps + 1 }
    val sharp = ok.filter { short(it) >= 1920 }
    val pick = if (!largest && sharp.isNotEmpty())
        sharp.minWithOrNull(compareByDescending(squareness).thenBy { it.first * it.second })
    else ok.maxWithOrNull(compareBy(short).thenBy(squareness))
    return pick?.let { it to (short(it) < 1920) }
}

/**
 * Tap at ([u], [v]) in a view showing [crop] (u right, v down, 0..1) to a small metering rect
 * [left, top, right, bottom] in sensor pixels of a [sensorW]x[sensorH] array, for a camera zoomed by [zoom] around its center.
 */
fun meterRect(crop: Crop, u: Float, v: Float, mirror: Boolean, zoom: Float, orientation: Int, sensorW: Int, sensorH: Int): IntArray {
    val x = crop.x + (if (mirror) 1f - u else u) * crop.w
    val y = crop.y + (1f - v) * crop.h
    // the zoomed view is the center 1/zoom of the array
    val zx = 0.5f + (x - 0.5f) / zoom; val zy = 0.5f + (y - 0.5f) / zoom
    val half = 0.05f / zoom
    val box = Crop((zx - half).coerceIn(0f, 1f - 2 * half), (zy - half).coerceIn(0f, 1f - 2 * half), 2 * half, 2 * half)
    return sensorRect(box, orientation, sensorW, sensorH)
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
