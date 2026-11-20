package com.dualframe.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanMeterTest {
    private val rad = (Math.PI / 180).toFloat()

    @Test fun steadyPan_readsItsSpeedAndScoresSmooth() {
        val m = PanMeter()
        repeat(200) { m.update(0f, 20f * rad, 0.01f) } // 2 s turning left at 20 deg/s
        assertEquals(20f, m.yaw, 0.1f)
        assertEquals(0f, m.pitch, 0.01f)
        assertTrue("smoothness ${m.smoothness}", m.smoothness > 95)
    }

    @Test fun shakyPan_scoresLow() {
        val m = PanMeter()
        repeat(200) { i -> m.update(0f, (20f + if (i % 2 == 0) 25f else -25f) * rad, 0.01f) }
        assertEquals(20f, m.yaw, 2f) // the average speed still shows
        assertTrue("smoothness ${m.smoothness}", m.smoothness < 20)
    }

    @Test fun level_isZeroUprightAndSignedWhenRolled() {
        assertEquals(0f, levelDegrees(0f, 9.81f), 1e-4f)
        assertEquals(45f, levelDegrees(9.81f, 9.81f), 1e-3f)
        assertEquals(-10f, levelDegrees(-9.81f * 0.17364818f, 9.81f * 0.98480775f), 1e-3f)
    }
}
