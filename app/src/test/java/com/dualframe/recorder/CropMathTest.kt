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

    @Test fun crops_slidersMovePortraitSidewaysAndLandscapeUpDown() {
        val (pLeft, lTop) = crops(3000, 4000, portraitPos = 0f, landscapePos = 0f)
        assertEquals(0f, pLeft.x, 1e-5f)
        assertEquals(1f - 0.421875f, lTop.y, 1e-5f) // GL y is bottom-up: top edge at 1
        val (pRight, lBottom) = crops(3000, 4000, portraitPos = 1f, landscapePos = 1f)
        assertEquals(1f - 0.5625f, pRight.x, 1e-5f)
        assertEquals(0f, lBottom.y, 1e-5f)
        assertEquals(0.125f, pRight.y, 1e-5f) // portrait stays vertically centered
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

    @Test fun recordedFraction_unionOfBothFrames() {
        val (p, l) = crops(3000, 4000)
        // (1687.5*3000 + 3000*1687.5 - 1687.5^2) / 12M
        assertEquals(0.6064453f, recordedFraction(p, l), 1e-5f)
    }

    @Test fun screenCrop_dropsBarsAndRoundsTo16() {
        val (c, h) = screenCrop(2340, 84, 63)
        assertEquals(2192, h)
        assertCrop(Crop(0f, 63f / 2340, 1f, 2192f / 2340), c)
    }

    @Test fun screenBox_mapsScreenPixelsIntoOutput() {
        // output covers screen rows 84..2276 (2192 tall); box from (108,184) to (540,1280)
        assertCrop(Crop(0.1f, 1f - (1280f - 84) / 2192, 0.4f, (1280f - 184) / 2192),
            screenBox(108, 184, 540, 1280, 1080, 84, 2192))
    }
}
