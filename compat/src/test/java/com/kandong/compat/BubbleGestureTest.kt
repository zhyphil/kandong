package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class BubbleGestureTest {
    private fun model()=BubbleGesture(Box(20,30,300,600),56,4,8f).apply { place(Box(24,150,56,56)) }
    @Test fun tinyMovementIsTapButDragAndBacktrackNeverAre() {
        val tap=model(); tap.down(1,1,40f,170f); assertTrue(tap.up(1,1,44f,173f))
        val drag=model(); drag.down(1,1,40f,170f); drag.move(1,1,80f,170f)
        assertFalse(drag.up(1,1,40f,170f))
    }
    @Test fun finalUpMovementAlsoInvalidatesTap() {
        val g=model(); g.down(1,1,40f,170f); assertFalse(g.up(1,1,100f,170f))
    }
    @Test fun cancellationPointerLossAndMultitouchNeverResumeOrJump() {
        for(mode in 0..3) {
            val g=model(); g.down(1,1,40f,170f)
            when(mode) { 0->g.cancel(); 1->g.move(2,1,300f,400f); 2->g.move(1,2,300f,400f); else->g.move(1,1,Float.NaN,1f) }
            assertFalse(g.up(1,1,40f,170f)); assertEquals(Box(24,150,56,56),g.position)
        }
    }
    @Test fun longPressOpensOnceAndCannotBecomeTapOrDrag() {
        val g=model(); g.down(1,1,40f,170f)
        assertTrue(g.longPress()); assertFalse(g.longPress()); assertFalse(g.up(1,1,40f,170f))
        val drag=model(); drag.down(1,1,40f,170f); drag.move(1,1,100f,200f); assertFalse(drag.longPress())
    }
    @Test fun clampsAllSafeInsetsAndDocksFullyVisibleOnNearestEdge() {
        val g=model(); g.down(1,1,40f,170f); g.move(1,1,10000f,10000f); g.up(1,1,10000f,10000f)
        assertEquals(Box(260,570,56,56),g.position)
        g.down(1,1,280f,590f); g.move(1,1,-10000f,-10000f); g.up(1,1,-10000f,-10000f)
        assertEquals(Box(24,34,56,56),g.position)
    }
    @Test fun retainedPlacementKeepsEdgeAndHeight() {
        val g=model(); g.place(Box(250,310,56,56)); assertEquals(Box(260,310,56,56),g.dock())
        g.cancel(); assertEquals(Box(260,310,56,56),g.dock())
    }
}
