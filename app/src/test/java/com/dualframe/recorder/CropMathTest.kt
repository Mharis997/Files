package com.dualframe.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropMathTest {
    private fun assertCrop(e: Crop, a: Crop) {
        assertEquals(e.x, a.x, 1e-5f); assertEquals(e.y, a.y, 1e-5f)
        assertEquals(e.w, a.w, 1e-5f); assertEquals(e.h, a.h, 1e-5f)
    }

    // m is column-major 4x4; applies m * (u, v, 0, 1)
    private fun assertUv(expS: Float, expT: Float, m: FloatArray, u: Float, v: Float) {
        assertEquals(expS, m[0] * u + m[4] * v + m[12], 1e-5f)
        assertEquals(expT, m[1] * u + m[5] * v + m[13], 1e-5f)
    }

    @Test fun crops_centeredByDefault() {
        val (p, l) = crops(3000, 4000)
        assertCrop(Crop(0.21875f, 0.125f, 0.5625f, 0.75f), p)
        assertCrop(Crop(0f, 0.2890625f, 1f, 0.421875f), l)
        // same zoom: source px per output px equal
        assertEquals(p.w * 3000 / 1080, l.w * 3000 / 1920, 1e-4f)
    }

    @Test fun crops_positionsMoveFramesToTheEdges() {
        val (pTopLeft, lTop) = crops(3000, 4000, portraitX = 0f, portraitY = 0f, landscapeY = 0f)
        assertEquals(0f, pTopLeft.x, 1e-5f)
        assertEquals(1f - 0.75f, pTopLeft.y, 1e-5f) // GL y is bottom-up: top edge at 1
        assertEquals(1f - 0.421875f, lTop.y, 1e-5f)
        val (pBottomRight, lBottom) = crops(3000, 4000, portraitX = 1f, portraitY = 1f, landscapeY = 1f)
        assertEquals(1f - 0.5625f, pBottomRight.x, 1e-5f)
        assertEquals(0f, pBottomRight.y, 1e-5f)
        assertEquals(0f, lBottom.y, 1e-5f)
        assertEquals(0f, lBottom.x, 1e-5f) // landscape always spans the full width
    }

    @Test fun chooseSourceSize_picksSmallestAtLeast1920() =
        assertEquals((2560 to 1920) to false,
            chooseSourceSize(listOf(4000 to 3000, 2560 to 1920, 1440 to 1080, 1920 to 1080), 30) { 33_333_333L })

    @Test fun chooseSourceSize_skipsSlowSizes() =
        assertEquals((4000 to 3000) to false,
            chooseSourceSize(listOf(4000 to 3000, 2560 to 1920), 30) { if (it.first == 2560) 50_000_000L else 33_333_333L })

    @Test fun chooseSourceSize_60fpsNeedsFasterSizes() =
        assertEquals((1440 to 1080) to true,
            chooseSourceSize(listOf(4000 to 3000, 1440 to 1080), 60) { if (it.first == 4000) 33_333_333L else 16_666_666L })

    @Test fun chooseSourceSize_fallsBackLowDetail() =
        assertEquals((1440 to 1080) to true,
            chooseSourceSize(listOf(1440 to 1080, 640 to 480, 1920 to 1080), 30) { 33_333_333L })

    @Test fun chooseSourceSize_noneIsNull() = assertNull(chooseSourceSize(listOf(1920 to 1080), 30) { 33_333_333L })

    @Test fun texMatrix_mapsQuadIntoCrop() {
        // SurfaceTexture's own matrix already rotates for the sensor, so this is only the crop
        val m = texMatrix(Crop(0.21875f, 0.125f, 0.5625f, 0.75f))
        assertUv(0.21875f, 0.125f, m, 0f, 0f)
        assertUv(0.78125f, 0.875f, m, 1f, 1f)
    }

    @Test fun texMatrix_mirrorFlipsInsideCrop() {
        val m = texMatrix(Crop(0.2f, 0.1f, 0.5f, 0.6f), mirror = true)
        assertUv(0.7f, 0.1f, m, 0f, 0f)
        assertUv(0.2f, 0.7f, m, 1f, 1f)
    }

    @Test fun sensorRect_rotatesUprightCropIntoSensorPixels() {
        val p = Crop(0.21875f, 0.125f, 0.5625f, 0.75f) // centered portrait on 3000x4000 upright
        assertArrayEquals(intArrayOf(500, 656, 3500, 2344), sensorRect(p, 90, 4000, 3000))
        // upright top-left corner comes from the sensor's bottom-left at 90, top-right at 270
        val corner = Crop(0f, 0.9f, 0.1f, 0.1f) // top-left 300x400 of 3000x4000
        assertArrayEquals(intArrayOf(0, 2700, 400, 3000), sensorRect(corner, 90, 4000, 3000))
        assertArrayEquals(intArrayOf(3600, 0, 4000, 300), sensorRect(corner, 270, 4000, 3000))
        assertArrayEquals(intArrayOf(0, 0, 300, 400), sensorRect(corner, 0, 3000, 4000))
    }

    @Test fun chooseSourceSize_largestFor4k() =
        assertEquals((4000 to 3000) to false,
            chooseSourceSize(listOf(4000 to 3000, 2560 to 1920, 1440 to 1080), 30, largest = true) { 33_333_333L })

    @Test fun meterRect_centerTapIsSensorCenter() {
        val (p, _) = crops(3000, 4000)
        val r = meterRect(p, 0.5f, 0.5f, mirror = false, zoom = 1f, orientation = 90, sensorW = 4000, sensorH = 3000)
        assertEquals(2000, (r[0] + r[2]) / 2); assertEquals(1500, (r[1] + r[3]) / 2)
        // 2x zoom halves the box and keeps it centered
        val z = meterRect(p, 0.5f, 0.5f, mirror = false, zoom = 2f, orientation = 90, sensorW = 4000, sensorH = 3000)
        assertEquals((r[2] - r[0]) / 2, z[2] - z[0])
        assertEquals(2000, (z[0] + z[2]) / 2)
    }

    @Test fun meterRect_topOfViewIsTopOfUprightFrame() {
        // upright top maps to sensor x=0 side at orientation 90 (see sensorRect test)
        val (p, _) = crops(3000, 4000)
        val r = meterRect(p, 0.5f, 0f, mirror = false, zoom = 1f, orientation = 90, sensorW = 4000, sensorH = 3000)
        assertEquals(true, r[0] < 1000)
    }
}
