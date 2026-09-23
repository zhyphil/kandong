package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

/** Original four edge-reachability regressions, now exercising the production source and control placement. */
class SourcePlacementTest {
    private val layout = MagnifierLayout.create(1176, 2400, 534f / 160f)!!
    private fun at(left: Int, top: Int, divisor: Int = 2) =
        Box(left, top, layout.imageWidth / divisor, layout.imageHeight / divisor)

    @Test fun topContentIsReachableAtEveryMagnificationWithHandleOutsideCrop() {
        for (divisor in 2..4) {
            val crop = at(300, 0, divisor)
            val controls = layout.idleControls(crop, true)!!
            assertEquals(0, crop.top)
            assertTrue(controls.move.top >= crop.bottom + layout.gap)
            assertTrue(controls.resize.top >= crop.bottom + layout.gap)
            assertTrue(controls.move.inside(1176, 2400))
        }
    }
    @Test fun movingDisplayToTopAllowsBottomContentWithHandleAbove() {
        val crop = at(300, 0).let { it.copy(top = 2400 - it.height) }
        val controls = layout.idleControls(crop, false)!!
        assertEquals(2400, crop.bottom)
        assertTrue(controls.move.bottom + layout.gap <= crop.top)
        assertTrue(controls.resize.bottom + layout.gap <= crop.top)
        assertTrue(controls.move.inside(1176, 2400))
    }
    @Test fun horizontalEdgesRemainInCaptureAndHandleRemainsOnScreen() {
        val left = at(0, 768)
        val right = left.copy(left = 1176 - left.width)
        for (crop in listOf(left, right)) {
            val controls = layout.idleControls(crop, true)!!
            assertTrue(crop.inside(1176, 2400))
            assertTrue(controls.move.inside(1176, 2400))
            assertTrue(controls.resize.inside(1176, 2400))
        }
        assertEquals(0, left.left); assertEquals(1176, right.right)
    }
    @Test fun bottomHandleCanDragContinuouslyUntilSourceReachesTop() {
        val crop = at(300, 768)
        val controls = layout.idleControls(crop, true)!!
        val x = controls.move.left + controls.move.width / 2f
        val y = controls.move.top + controls.move.height / 2f
        val gesture = SourceGesture(GestureMode.MOVE, 1, controls.side, crop, controls.move, x, y, layout)
        for (fingerY in listOf(y - 100, y - 300, y - 600, 0f)) gesture.update(1, 1, x, fingerY)
        assertEquals(0, gesture.result.source.top)
        assertTrue(gesture.result.grip.top >= gesture.result.source.bottom)
        assertTrue(gesture.result.grip.inside(1176, 2400))
    }
    @Test fun tinyDisplayDeclinesControlsInsteadOfCreatingInvalidClampOrEmptyCrop() {
        assertNull(MagnifierLayout.create(20, 30, 1f))
    }
}
