package com.kandong.app.domain

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun measuredOriginIsUsedAndClipped() {
        assertEquals(Box(10, 20, 90, 100), Box(30, 70, 110, 170).toOverlay(20, 50, 90, 100))
        assertEquals(Box(0, 0, 20, 30), Box(-10, 10, 40, 80).toOverlay(20, 50, 100, 100))
    }
    @Test fun outsideAndEmptyRectsCannotDraw() {
        assertNull(Box(-50, -50, -10, -10).toOverlay(0, 0, 100, 100))
        assertNull(Box(10, 10, 10, 40).toOverlay(0, 0, 100, 100))
    }
    @Test fun systemBarsAllowOnlyOnePixelOfInsetRounding() {
        val content = Box(0, 63, 1080, 1857)
        assertTrue(isOutsideContent(Box(0, 0, 1080, 64), content))
        assertFalse(isOutsideContent(Box(0, 0, 1080, 65), content))
        assertFalse(isOutsideContent(Box(0, 0, 1080, 300), content))
    }
    @Test fun touchingEdgesAreNotOcclusion() {
        assertFalse(Box(0, 0, 100, 100).intersects(Box(100, 0, 150, 100)))
        assertTrue(Box(0, 0, 100, 100).intersects(Box(99, 0, 150, 100)))
    }
}
