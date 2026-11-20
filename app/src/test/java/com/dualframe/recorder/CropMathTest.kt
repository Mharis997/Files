package com.dualframe.recorder

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

    @Test fun squareCrops_s23Upright() {
        val (p, l) = squareCrops(3000, 4000)
        assertCrop(Crop(0.21875f, 0.125f, 0.5625f, 0.75f), p)
        assertCrop(Crop(0f, 0.2890625f, 1f, 0.421875f), l)
        // same zoom: source px per output px equal
        assertEquals(p.w * 3000 / 1080, l.w * 3000 / 1920, 1e-4f)
    }

    @Test fun chooseSourceSize_picksSmallestAtLeast1920() =
        assertEquals((2560 to 1920) to false,
            chooseSourceSize(listOf(4000 to 3000, 2560 to 1920, 1440 to 1080, 1920 to 1080)) { 33_333_333L })

    @Test fun chooseSourceSize_skipsSlowSizes() =
        assertEquals((4000 to 3000) to false,
            chooseSourceSize(listOf(4000 to 3000, 2560 to 1920)) { if (it.first == 2560) 50_000_000L else 33_333_333L })

    @Test fun chooseSourceSize_fallsBackLowDetail() =
        assertEquals((1440 to 1080) to true,
            chooseSourceSize(listOf(1440 to 1080, 640 to 480, 1920 to 1080)) { 33_333_333L })

    @Test fun chooseSourceSize_noneIsNull() =
        assertNull(chooseSourceSize(listOf(1920 to 1080)) { 33_333_333L })

    @Test fun texMatrix_rotations() {
        val full = Crop(0f, 0f, 1f, 1f)
        // upright top-left (0,1) -> sensor (0,0) at 90, (1,1) at 270, unchanged at 0
        assertUv(0f, 0f, texMatrix(full, 90), 0f, 1f)
        assertUv(1f, 1f, texMatrix(full, 270), 0f, 1f)
        assertUv(0f, 1f, texMatrix(full, 0), 0f, 1f)
        assertUv(1f, 0f, texMatrix(full, 180), 0f, 1f)
        // crop applied before rotation: quad (0,0) -> upright (crop.x, crop.y)
        assertUv(1f - 0.125f, 0.21875f, texMatrix(Crop(0.21875f, 0.125f, 0.5625f, 0.75f), 90), 0f, 0f)
    }
}
