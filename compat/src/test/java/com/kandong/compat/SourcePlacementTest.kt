package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class SourcePlacementTest {
    private fun at(x: Float, y: Float, scale: Int = 2, below: Boolean = true) = placeSource(
        1176, 2400, 1096 / scale, 534 / scale, x, y, 467, 160, 20, below)

    @Test fun topContentIsReachableAtEveryMagnificationWithHandleOutsideCrop() {
        for (scale in 2..4) {
            val p = at(588f, -1000f, scale)
            assertEquals("Top row must be reachable at ${scale}x", 0, p.top)
            assertTrue(p.handleTop >= p.top + p.height + 20)
            assertTrue(p.handleTop + 160 <= 2400)
        }
    }

    @Test fun movingDisplayToTopAllowsBottomContentWithHandleAbove() {
        val p = at(588f, 5000f, below = false)
        assertEquals(2400, p.top + p.height)
        assertTrue(p.handleTop + 160 + 20 <= p.top)
        assertTrue(p.handleTop >= 0)
    }

    @Test fun horizontalEdgesRemainInCaptureAndHandleRemainsOnScreen() {
        val left = at(-500f, 768f)
        val right = at(3000f, 768f)
        assertEquals(0, left.left)
        assertEquals(1176, right.left + right.width)
        assertTrue(left.handleLeft >= 0)
        assertTrue(right.handleLeft + 467 <= 1176)
    }

    @Test fun bottomHandleCanDragContinuouslyUntilSourceReachesTop() {
        var p = at(588f, 768f)
        val initialY = p.centerY
        val fingerStart = p.handleTop + 80f
        for (fingerY in listOf(fingerStart - 100, fingerStart - 300, fingerStart - 600, 300f)) {
            p = at(588f, initialY + fingerY - fingerStart)
        }
        assertEquals(0, p.top)
        assertTrue(p.handleTop >= p.height)
    }
}
