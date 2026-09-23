package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class MagnifierGeometryTest {
    private val layout = MagnifierLayout.create(360, 800, 1f)!!
    private val initial = Box(90, 260, 150, 72)
    private fun resize(side: GripSide, source: Box = initial): SourceGesture {
        val grip = Box(if (side.right) source.right - 48 else source.left,
            if (side.below) source.bottom + 6 else source.top - 54, 48, 48)
        return SourceGesture(GestureMode.RESIZE, 7, side, source, grip, grip.left + 24f, grip.top + 24f, layout)
    }
    private fun change(g: SourceGesture, dx: Float, dy: Float) =
        g.update(7, 1, g.startGrip.left + 24f + dx, g.startGrip.top + 24f + dy)

    @Test fun actualUniformScaleUsesLimitingViewportAxisAndNonPresetRatios() {
        assertEquals(1096f / 730, actualScale(1096, 480, Box(0, 0, 730, 200)), .0001f)
        assertEquals(480f / 180, actualScale(1096, 480, Box(0, 0, 400, 180)), .0001f)
        assertEquals(2f, actualScale(1096, 480, Box(0, 0, 548, 240)), 0f)
        assertEquals(0f, actualScale(0, 480, initial), 0f)
    }
    @Test fun shrinkingEitherAxisIncreasesMagnificationWithoutChangingTextProportions() {
        val source = Box(90, 260, 168, 72)
        for ((dx, dy) in listOf(-37f to 0f, 0f to -23f, -37f to -23f)) {
            val resized = change(resize(GripSide(true, true), source), dx, dy).source
            assertTrue(resized.width < source.width)
            assertTrue(resized.height < source.height)
            assertTrue(actualScale(layout.imageWidth, layout.imageHeight, resized) >
                actualScale(layout.imageWidth, layout.imageHeight, source))
            assertEquals(layout.imageWidth.toFloat() / layout.imageHeight,
                resized.width.toFloat() / resized.height, .04f)
            assertEquals(source.left, resized.left); assertEquals(source.top, resized.top)
        }
    }
    @Test fun growingEitherAxisDecreasesMagnificationAndKeepsWholeCropVisible() {
        val source = Box(90, 260, 168, 72)
        for ((dx, dy) in listOf(37f to 0f, 0f to 23f, 37f to 23f)) {
            val resized = change(resize(GripSide(true, true), source), dx, dy).source
            assertTrue(resized.width > source.width); assertTrue(resized.height > source.height)
            val factor = actualScale(layout.imageWidth, layout.imageHeight, resized)
            assertTrue(factor < actualScale(layout.imageWidth, layout.imageHeight, source))
            assertTrue(resized.width * factor <= layout.imageWidth + .01f)
            assertTrue(resized.height * factor <= layout.imageHeight + .01f)
            assertTrue(layout.imageWidth - resized.width * factor < factor + .1f)
            assertTrue(layout.imageHeight - resized.height * factor < factor + .1f)
        }
    }
    @Test fun allCornersKeepOppositeAnchorAndClampWithoutFlipping() {
        for (below in listOf(true, false)) for (right in listOf(true, false)) {
            val g = resize(GripSide(below, right))
            for (delta in listOf(-10000f, -100f, 0f, 100f, 10000f)) {
                val r = change(g, delta, delta)
                assertEquals(if (right) initial.left else initial.right, if (right) r.source.left else r.source.right)
                assertEquals(if (below) initial.top else initial.bottom, if (below) r.source.top else r.source.bottom)
                assertTrue(r.source.width in layout.minWidth..layout.imageWidth)
                assertTrue(r.source.height in layout.minHeight..layout.maxHeight)
                assertTrue(r.source.inside(360, 800)); assertTrue(r.grip.inside(360, 800))
                assertFalse(r.source.intersects(r.grip))
                assertEquals(0, r.snapX); assertEquals(0, r.snapY)
                assertEquals(r.source, g.finish(true))
            }
        }
    }
    @Test fun placementGridAlwaysFindsOutsideControlsAndOnePanelIncludingSafeInsets() {
        for ((w, h, d) in listOf(Triple(1176, 2400, 3.3375f), Triple(1080, 2400, 3f), Triple(360, 640, 1f))) {
            for (safe in listOf(0, (45 * d).toInt())) {
                val l = MagnifierLayout.create(w, h, d, safe, safe)!!
                assertTrue(l.topPanel.top >= (32 * d).toInt())
                assertTrue(h - l.bottomPanel.bottom >= (32 * d).toInt())
                for (cw in listOf(l.minWidth, l.imageWidth)) for (ch in listOf(l.minHeight, l.maxHeight)) {
                    for (ix in 0..16) for (iy in 0..64) for (bottom in listOf(true, false)) {
                        val s = Box((w - cw) * ix / 16, (h - ch) * iy / 64, cw, ch)
                        val c = l.idleControls(s, bottom)
                        assertNotNull("No controls for $l $s", c)
                        c!!
                        assertTrue(c.move.inside(w, h)); assertTrue(c.resize.inside(w, h))
                        assertFalse(s.intersects(c.move)); assertFalse(s.intersects(c.resize)); assertFalse(c.move.intersects(c.resize))
                        assertNotNull(l.selectPanel(bottom, s, listOf(c.move, c.resize)))
                    }
                }
            }
        }
    }
    @Test fun middleJitterHasDeadbandAndSwappingNeverChangesCrop() {
        for (startBottom in listOf(true, false)) {
            var bottom = startBottom
            for (dy in listOf(-10, 5, -6, 12, 0, -20, 20)) {
                val s = Box(100, 384 + dy, 100, 32)
                bottom = layout.selectPanel(bottom, s, emptyList())!!
                assertEquals(startBottom, bottom)
                assertEquals(Box(100, 384 + dy, 100, 32), s)
            }
        }
        assertEquals(false, layout.selectPanel(true, Box(100, 470, 100, 32), emptyList()))
        assertEquals(true, layout.selectPanel(false, Box(100, 290, 100, 32), emptyList()))
        assertEquals(true, layout.selectPanel(false, Box(100, 40, 100, 32), emptyList()))
    }
    @Test fun activeMoveSideAndPointerStayFrozenAcrossBothPanelDirections() {
        for (below in listOf(true, false)) {
            val c = Box(90, if (below) 200 else 580, 150, 72)
            val grip = Box(90, if (below) c.bottom + 6 else c.top - 54, 80, 48)
            val side = GripSide(below, true)
            val g = SourceGesture(GestureMode.MOVE, 9, side, c, grip, 130f, grip.top + 24f, layout)
            var bottom = below
            for (y in if (below) (grip.top + 24..780 step 12) else (grip.top + 24 downTo 20 step 12)) {
                val r = g.update(9, 1, 130f, y.toFloat())
                bottom = layout.selectPanel(bottom, r.source, listOf(r.grip))!!
                assertEquals(side, g.side); assertEquals(9, g.pointerId)
                assertTrue(r.grip.inside(360, 800)); assertFalse(r.source.intersects(r.grip))
            }
            assertEquals(!below, bottom)
        }
    }
    @Test fun screenReachableLongDragSnapsTopAndBottomOnlyOnUp() {
        for (below in listOf(true, false)) {
            val s = Box(90, if (below) 200 else 480, 150, 72)
            val grip = Box(90, if (below) s.bottom + 6 else s.top - 54, 80, 48)
            val g = SourceGesture(GestureMode.MOVE, 1, GripSide(below, true), s, grip, 130f, grip.top + 24f, layout)
            val r = g.update(1, 1, 130f, if (below) 799f else 0f)
            assertTrue(r.grip.inside(360, 800)); assertFalse(r.grip.intersects(r.source))
            assertEquals(if (below) 1 else -1, r.snapY)
            assertEquals(r.source, g.result.source)
            val snapped = g.finish(true)
            assertEquals(if (below) 800 else 0, if (below) snapped.bottom else snapped.top)
            assertNotNull(layout.idleControls(snapped, !below))
        }
    }
    @Test fun horizontalOvershootAndCornerSnapUseScreenCoordinates() {
        val s = Box(90, 200, 48, 72)
        for (right in listOf(true, false)) {
            // The wider move control can reach the screen before the narrow source.
            val grip = Box(if (right) 90 else 58, 278, 80, 48)
            val g = SourceGesture(GestureMode.MOVE, 1, GripSide(true, right), s, grip,
                grip.left + 40f, grip.top + 24f, layout)
            val r = g.update(1, 1, if (right) 359f else 0f, 799f)
            assertEquals(if (right) 1 else -1, r.snapX); assertEquals(1, r.snapY)
            assertTrue(r.grip.inside(360, 800))
            val final = g.finish(true)
            assertEquals(if (right) 360 else 0, if (right) final.right else final.left)
            assertEquals(800, final.bottom)
        }
    }
    @Test fun reverseCancelAndPointerLossClearPendingSnap() {
        fun moving(): SourceGesture {
            val s = Box(90, 200, 150, 72); val grip = Box(90, 278, 80, 48)
            return SourceGesture(GestureMode.MOVE, 3, GripSide(true, true), s, grip, 130f, 302f, layout)
        }
        val reverse = moving()
        assertEquals(1, reverse.update(3, 1, 130f, 799f).snapY)
        val backed = reverse.update(3, 1, 130f, 650f)
        assertEquals(0, backed.snapY); assertEquals(backed.source, reverse.finish(true))
        for (cancel in 0..3) {
            val g = moving(); val r = g.update(3, 1, 130f, 799f)
            when (cancel) { 0 -> g.cancel(); 1 -> g.update(4, 1, 130f, 799f); 2 -> g.update(3, 2, 130f, 799f); else -> g.finish(false) }
            assertEquals(0, g.result.snapY); assertEquals(r.source, g.finish(true))
            assertEquals(r.source, g.update(3, 1, 130f, 10f).source)
        }
    }
    @Test fun narrowEdgeCropChoosesTheAdjacentCorner() {
        for (left in listOf(0, 312)) {
            val source = Box(left, 300, 48, 32)
            val controls = layout.idleControls(source, true)!!
            assertEquals(source.left, controls.resize.left)
            assertFalse(source.intersects(controls.resize))
        }
    }
    @Test fun proportionalResizeReachesMinMaxAndScreenLimits() {
        val source = Box(12, 260, 150, 72)
        val g = resize(GripSide(true, true), source)
        assertEquals(source.copy(width = 75, height = layout.minHeight), change(g, -1000f, -1000f).source)
        assertEquals(source.copy(width = layout.imageWidth, height = layout.maxHeight), change(g, 1000f, 1000f).source)
        val top = resize(GripSide(false, true), Box(90, 80, 150, 72))
        val r = change(top, 1000f, -1000f)
        assertTrue(r.grip.inside(360, 800)); assertEquals(0, r.grip.top)
        assertEquals(152, r.source.bottom)
    }
    @Test fun allCornersShrinkTowardAnchorAndNeverAccumulateAspectDrift() {
        for (below in listOf(true, false)) for (right in listOf(true, false)) {
            var source = Box(90, 260, 168, 72)
            val inwardX = if (right) -1f else 1f
            val inwardY = if (below) -1f else 1f
            for (step in 0..19) {
                val previous = source
                source = change(resize(GripSide(below, right), source), inwardX * 2, inwardY * 2).source
                assertTrue(source.width <= previous.width); assertTrue(source.height <= previous.height)
                assertEquals(if (right) previous.left else previous.right, if (right) source.left else source.right)
                assertEquals(if (below) previous.top else previous.bottom, if (below) source.top else source.bottom)
                assertTrue(kotlin.math.abs(source.height - source.width.toFloat() * layout.imageHeight / layout.imageWidth) <= .5f)
            }
        }
    }
    @Test fun unsupportedTinyDisplaysAreRejectedWithoutInvalidClamp() {
        for (w in listOf(0, 1, 30, 100)) for (h in listOf(0, 1, 30, 200))
            assertNull(MagnifierLayout.create(w, h, 1f))
        assertNull(MagnifierLayout.create(360, 800, Float.NaN))
    }
}
