package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class MagnifierGeometryTest {
    private val layout = MagnifierLayout.create(360, 800, 1f)!!
    private val initial = Box(90, 260, 150, 72)
    private fun gesture(mode: GestureMode, source: Box = initial): SourceGesture {
        val c=layout.idleControls(source,true)!!
        return SourceGesture(mode,7,c.side,source,if(mode==GestureMode.MOVE) source else c.resize,120f,280f,layout)
    }
    private fun change(g: SourceGesture, dx: Float, dy: Float) = g.update(7,1,120f+dx,280f+dy)
    @Test fun widthAndHeightCanBeChangedIndependentlyFromUpperRight() {
        assertEquals(Box(90,260,187,72),change(gesture(GestureMode.RESIZE),37f,0f).source)
        assertEquals(Box(90,237,150,95),change(gesture(GestureMode.RESIZE),0f,-23f).source)
        assertEquals(Box(90,237,187,95),change(gesture(GestureMode.RESIZE),37f,-23f).source)
    }
    @Test fun resizeKeepsBottomLeftFixedAndNeverFlips() {
        val g=gesture(GestureMode.RESIZE)
        for(dx in listOf(-10000f,-100f,0f,10000f)) for(dy in listOf(-10000f,0f,10000f)) {
            val r=change(g,dx,dy)
            assertEquals(90,r.source.left); assertEquals(332,r.source.bottom)
            assertTrue(r.source.width in layout.minWidth..layout.imageWidth)
            assertTrue(r.source.height in layout.minHeight..layout.maxHeight)
            assertTrue(r.source.inside(360,800)); assertTrue(r.grip.inside(360,800))
        }
    }
    @Test fun resizeCanReachPhysicalTopAndRightWithoutBeingBlockedByItsTouchTarget() {
        val r=change(gesture(GestureMode.RESIZE,Box(150,20,150,40)),10000f,-10000f)
        assertEquals(Box(150,0,210,60),r.source)
        assertEquals(Box(312,0,48,48),r.grip)
    }
    @Test fun directDragMovesByFingerDeltaWithoutChangingSize() {
        assertEquals(Box(127,283,150,72),change(gesture(GestureMode.MOVE),37f,23f).source)
    }
    @Test fun directDragContinuouslyReachesAllFourEdgesWithoutReleaseSnap() {
        for ((dx,dy,expected) in listOf(Triple(-1000f,-1000f,Box(0,0,150,72)),
            Triple(1000f,1000f,Box(210,728,150,72)))) {
            val g=gesture(GestureMode.MOVE); val r=change(g,dx,dy)
            assertEquals(expected,r.source); assertEquals(expected,g.finish(true))
            assertEquals(0,r.snapX); assertEquals(0,r.snapY)
        }
    }
    @Test fun directDragAcrossTheMidlineSwapsThePanelWithoutChangingTheCrop() {
        val g=gesture(GestureMode.MOVE);var bottom=true
        for(dy in 0..420 step 10) {
            val source=change(g,0f,dy.toFloat()).source
            val controls=layout.idleControls(source,bottom)!!
            bottom=layout.selectPanel(bottom,source,listOf(controls.resize))!!
            assertEquals(Box(90,260+dy,150,72),source)
        }
        assertFalse(bottom)
    }
    @Test fun cancelOrAdditionalFingerFreezesSourceUntilANewGesture() {
        for(mode in GestureMode.values()) for(cancel in 0..3) {
            val g=gesture(mode);val r=change(g,20f,-10f)
            when(cancel) { 0->g.cancel();1->g.update(8,1,150f,200f);2->g.update(7,2,150f,200f);else->g.finish(false) }
            assertEquals(r.source,g.finish(true));assertEquals(r.source,change(g,100f,100f).source)
        }
    }
    @Test fun nonFiniteCoordinatesCannotPoisonSourceGeometry() {
        val g=gesture(GestureMode.MOVE)
        assertEquals(initial,g.update(7,1,Float.NaN,0f).source)
        assertEquals(initial,change(g,100f,100f).source)
    }
    @Test fun placementGridKeepsCornerOnScreenAndFindsAnUnobscuredPanel() {
        for((w,h,d) in listOf(Triple(1176,2400,3.3375f),Triple(1080,2400,3f),Triple(360,640,1f))) {
            for(safe in listOf(0,(45*d).toInt())) {
                val l=MagnifierLayout.create(w,h,d,safe,safe)!!
                for(cw in listOf(l.minWidth,l.imageWidth)) for(ch in listOf(l.minHeight,l.maxHeight)) {
                    for(ix in 0..16) for(iy in 0..64) for(bottom in listOf(true,false)) {
                        val source=Box((w-cw)*ix/16,(h-ch)*iy/64,cw,ch)
                        val c=l.idleControls(source,bottom)!!
                        assertEquals(source,c.move);assertTrue(c.resize.inside(w,h))
                        assertNotNull("No safe panel for $source",l.selectPanel(bottom,source,listOf(c.resize)))
                    }
                }
            }
        }
    }
    @Test fun middleJitterRetainsTheCurrentPanel() {
        for(start in listOf(true,false)) {
            var bottom=start
            for(dy in listOf(-10,5,-6,12,0,-20,20)) {
                val source=Box(100,384+dy,100,32)
                bottom=layout.selectPanel(bottom,source,emptyList())!!
                assertEquals(start,bottom)
            }
        }
    }
    @Test fun unsupportedTinyDisplaysAreRejectedWithoutInvalidClamp() {
        for(w in listOf(0,1,30,100)) for(h in listOf(0,1,30,200)) assertNull(MagnifierLayout.create(w,h,1f))
        assertNull(MagnifierLayout.create(360,800,Float.NaN))
    }
}
