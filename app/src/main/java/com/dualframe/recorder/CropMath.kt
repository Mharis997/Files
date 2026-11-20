package com.dualframe.recorder

import kotlin.math.min

/** Normalized rect in the upright frame, GL convention (origin bottom-left). */
data class Crop(val x: Float, val y: Float, val w: Float, val h: Float)

private fun centered(w: Float, h: Float) = Crop((1f - w) / 2f, (1f - h) / 2f, w, h)

/** The shared centered square, side = the upright frame's short edge. */
fun square(uprightW: Int, uprightH: Int): Crop {
    val s = min(uprightW, uprightH).toFloat()
    return centered(s / uprightW, s / uprightH)
}

/** (portrait 9:16, landscape 16:9), both inscribed in the shared square, so both have the same scale. */
fun squareCrops(uprightW: Int, uprightH: Int): Pair<Crop, Crop> {
    val s = min(uprightW, uprightH).toFloat()
    val short = s * 9f / 16f
    return centered(short / uprightW, s / uprightH) to centered(s / uprightW, short / uprightH)
}

/**
 * Picks the camera stream size from sensor-orientation sizes.
 * Returns (size, lowDetail): the smallest 4:3 size at 30 fps with short edge >= 1920 (no upscaling to 1080p),
 * otherwise the largest 4:3 size at 30 fps flagged lowDetail, otherwise null.
 */
fun chooseSourceSize(sizes: List<Pair<Int, Int>>, minFrameNs: (Pair<Int, Int>) -> Long): Pair<Pair<Int, Int>, Boolean>? {
    val ok = sizes.filter { (w, h) -> w * 3 == h * 4 && minFrameNs(w to h) <= 33_333_334L }
    ok.filter { min(it.first, it.second) >= 1920 }.minByOrNull { it.first * it.second }?.let { return it to false }
    return ok.maxByOrNull { it.first * it.second }?.let { it to true }
}

/**
 * Column-major 4x4: quad UV (0..1) -> upright UV inside [crop] -> sensor UV for [sensorOrientation].
 * Multiply the SurfaceTexture transform on the left.
 */
fun texMatrix(crop: Crop, sensorOrientation: Int): FloatArray {
    val (cx, cy, cw, ch) = crop
    // s = a*u + b*v + c ; t = d*u + e*v + f
    val (abc, def) = when (sensorOrientation) {
        90 -> floatArrayOf(0f, -ch, 1f - cy) to floatArrayOf(cw, 0f, cx)
        180 -> floatArrayOf(-cw, 0f, 1f - cx) to floatArrayOf(0f, -ch, 1f - cy)
        270 -> floatArrayOf(0f, ch, cy) to floatArrayOf(-cw, 0f, 1f - cx)
        else -> floatArrayOf(cw, 0f, cx) to floatArrayOf(0f, ch, cy)
    }
    return floatArrayOf(
        abc[0], def[0], 0f, 0f,
        abc[1], def[1], 0f, 0f,
        0f, 0f, 1f, 0f,
        abc[2], def[2], 0f, 1f,
    )
}
