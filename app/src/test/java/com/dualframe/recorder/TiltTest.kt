package com.dualframe.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class TiltTest {
    private fun assertVec(ex: Float, ey: Float, v: Pair<Float, Float>) {
        assertEquals(ex, v.first, 1e-6f); assertEquals(ey, v.second, 1e-6f)
    }

    @Test fun rotateVec_turnsClockwiseOnScreen() {
        assertVec(0f, 1f, rotateVec(1f, 0f, 90)) // right -> down
        assertVec(-1f, 0f, rotateVec(0f, 1f, 90)) // down -> left
        assertVec(0f, -1f, rotateVec(1f, 0f, -90)) // right -> up
        assertVec(1f, 0f, rotateVec(0f, 1f, -90)) // down -> right
        assertVec(-1f, 0f, rotateVec(1f, 0f, 180))
    }

    @Test fun snapOrientation_needsToBeClearlyPastHalfway() {
        assertEquals(90, snapOrientation(80, 0))
        assertEquals(0, snapOrientation(50, 0)) // in the dead zone: keep the current one
        assertEquals(90, snapOrientation(50, 90))
        assertEquals(0, snapOrientation(350, 270))
        assertEquals(270, snapOrientation(-1, 270)) // flat on a table: unknown
    }

    @Test fun viewRotation_countersTheTilt() {
        assertEquals(0, viewRotationFor(0))
        assertEquals(-90, viewRotationFor(90)) // left side up
        assertEquals(90, viewRotationFor(270)) // right side up
        assertEquals(180, viewRotationFor(180))
    }
}
