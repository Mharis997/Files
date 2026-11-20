package com.dualframe.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TiltTest {
    @Test fun level_isZeroUprightAndSignedWhenRolled() {
        assertEquals(0f, levelDegrees(0f, 9.81f), 1e-4f)
        assertEquals(45f, levelDegrees(9.81f, 9.81f), 1e-3f)
        assertEquals(-10f, levelDegrees(-9.81f * 0.17364818f, 9.81f * 0.98480775f), 1e-3f)
    }

    @Test fun level_isUnknownWhenPhoneLiesFlat() {
        assertTrue(levelDegrees(0.2f, 0.3f).isNaN())
    }
}
