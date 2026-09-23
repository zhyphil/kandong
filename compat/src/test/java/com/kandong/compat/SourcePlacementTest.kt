package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class SourcePlacementTest {
    private val layout = MagnifierLayout.create(1176, 2400, 534f / 160f)!!
    @Test fun directSelectionOwnsMoveAndResizeTargetStaysAtUpperRight() {
        val crop = Box(300, 768, 500, 220)
        for (bottom in listOf(true, false)) {
            val c = layout.idleControls(crop, bottom)!!
            assertEquals(crop, c.move)
            assertEquals(GripSide(false, true), c.side)
            assertEquals(crop.right, c.resize.left + c.resize.width / 2)
            assertEquals(crop.top, c.resize.top + c.resize.height / 2)
        }
    }
    @Test fun allPhysicalEdgesRemainReachableAndResizeTargetStaysOnScreen() {
        for (x in listOf(0, 676)) for(y in listOf(0, 2180)) {
            val crop = Box(x, y, 500, 220)
            val controls = layout.idleControls(crop, y < 1200)!!
            assertEquals(crop, controls.move)
            assertTrue(controls.resize.inside(1176, 2400))
            assertEquals(160, controls.resize.width)
            assertEquals(160, controls.resize.height)
            assertNotNull(layout.selectPanel(y < 1200, crop, listOf(controls.resize)))
        }
    }
    @Test fun minimumCropStillLeavesPartOfTheFrameAvailableForDirectDragging() {
        val crop=Box(400,800,layout.minWidth,layout.minHeight)
        val corner=layout.idleControls(crop,true)!!.resize
        assertTrue(corner.left > crop.left)
        assertTrue(corner.top < crop.bottom)
    }
    @Test fun panelDirectionNeverRelocatesTheResizeCorner() {
        val crop=Box(300,1000,500,220)
        assertEquals(layout.idleControls(crop,true),layout.idleControls(crop,false))
    }
    @Test fun tinyDisplayDeclinesControlsInsteadOfCreatingInvalidClampOrEmptyCrop() {
        assertNull(MagnifierLayout.create(20, 30, 1f))
    }
}
