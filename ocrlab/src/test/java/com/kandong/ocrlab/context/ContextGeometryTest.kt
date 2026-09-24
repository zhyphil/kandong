package com.kandong.ocrlab.context

import org.junit.Assert.*
import org.junit.Test

class ContextGeometryTest {
    private val roi = ContextRect(100.0, 200.0, 300.0, 400.0)
    @Test fun handCalculatedMappingAndMirrorClipping() {
        val source = ContextRect(110.0, 220.0, 150.0, 240.0)
        assertEquals(ContextRect(10.0, 20.0, 90.0, 60.0), ContextGeometry.map(source, roi, MirrorTransform(2.0, 200.0, 100.0, 5.0, 10.0)))
        assertEquals(ContextRect(10.0, 20.0, 80.0, 50.0), ContextGeometry.map(source, roi, MirrorTransform(2.0, 80.0, 50.0, 5.0, 10.0)))
    }
    @Test fun partialIntersectionAndTouchOnly() {
        assertEquals(ContextRect(0.0, 0.0, 40.0, 60.0), ContextGeometry.map(ContextRect(90.0, 190.0, 120.0, 230.0), roi, MirrorTransform(2.0, 200.0, 100.0)))
        assertNull(ContextGeometry.intersect(roi, ContextRect(300.0, 200.0, 320.0, 220.0)))
        assertNull(ContextGeometry.map(ContextRect(110.0, 220.0, 150.0, 240.0), roi, MirrorTransform(2.0, 20.0, 20.0, 180.0, 180.0)))
    }
    @Test fun scaleLimitsAndPanAreClampedInSourceUnits() {
        assertEquals(MirrorTransform(1.0, 80.0, 50.0, 120.0, 0.0), ContextGeometry.transform(roi, MirrorTransform(1.0, 80.0, 50.0, Double.MAX_VALUE, -Double.MAX_VALUE)))
        assertEquals(MirrorTransform(5.0, 80.0, 50.0, 184.0, 190.0), ContextGeometry.transform(roi, MirrorTransform(5.0, 80.0, 50.0, Double.MAX_VALUE, Double.MAX_VALUE)))
        assertEquals(ContextRect(50.0, 100.0, 250.0, 200.0), ContextGeometry.map(ContextRect(110.0, 220.0, 150.0, 240.0), roi, MirrorTransform(5.0, 400.0, 400.0)))
    }
    @Test fun invalidGeometryRejectedWithoutNonfiniteOutput() {
        val invalid = listOf(
            MirrorTransform(0.0, 80.0, 50.0), MirrorTransform(5.1, 80.0, 50.0),
            MirrorTransform(Double.NaN, 80.0, 50.0), MirrorTransform(2.0, Double.POSITIVE_INFINITY, 50.0),
            MirrorTransform(2.0, 80.0, 0.0), MirrorTransform(2.0, 80.0, -1.0),
            MirrorTransform(2.0, 80.0, 50.0, Double.NaN), MirrorTransform(2.0, 80.0, 50.0, 0.0, Double.NEGATIVE_INFINITY),
            MirrorTransform(2.0, Double.MAX_VALUE, 50.0),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { ContextGeometry.transform(roi, it) } }
        listOf(ContextRect(0.0, 0.0, 0.0, 1.0), ContextRect(2.0, 0.0, 1.0, 1.0), ContextRect(Double.NaN, 0.0, 1.0, 1.0), ContextRect(-Double.MAX_VALUE, 0.0, Double.MAX_VALUE, 1.0)).forEach {
            assertFalse(it.valid())
            assertThrows(IllegalArgumentException::class.java) { ContextGeometry.intersect(it, roi) }
        }
    }
}
