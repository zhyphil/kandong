package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

class FullPageStripPlannerTest {
    private fun invalid(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun targetPhoneAndEmulatorExceedOldWholeImageBudgetButFitStrips() {
        for ((width, count) in listOf(1176 to 4, 1080 to 3)) {
            invalid { DetectorPacking.plan(width, 2400) }
            val plan = FullPageStripPlanner.plan(width, 2400)
            assertEquals(width, plan.sourceWidth); assertEquals(2400, plan.sourceHeight)
            assertEquals(count, plan.strips.size)
            for (strip in plan.strips) {
                assertTrue(strip.read.width.toLong() * strip.read.height <= DetectorPacking.MAX_PIXELS)
                assertTrue(strip.detector.width.toLong() * strip.detector.height <= DetectorPacking.MAX_PIXELS)
                assertEquals(DetectorPacking.plan(strip.read.width, strip.read.height), strip.detector)
            }
        }
    }

    @Test fun everySourceRowHasExactlyOneCoreOwnerAndIsReadAtFullWidth() {
        for (width in listOf(32, 320, 1080, 1176, 1536, 2048)) {
            for (height in listOf(32, 333, 2400, 4096)) {
                if (width.toLong() * height > FullPageStripPlanner.MAX_FRAME_PIXELS) continue
                val plan = FullPageStripPlanner.plan(width, height)
                assertTrue(plan.strips.size in 1..FullPageStripPlanner.MAX_STRIPS)
                assertEquals(0, plan.strips.first().core.top)
                assertEquals(height, plan.strips.last().core.bottom)
                for (strip in plan.strips) {
                    assertEquals(0, strip.read.left); assertEquals(width, strip.read.right)
                    assertEquals(0, strip.core.left); assertEquals(width, strip.core.right)
                    assertTrue(strip.read.top in 0..strip.core.top)
                    assertTrue(strip.read.bottom in strip.core.bottom..height)
                    assertTrue(strip.read.width.toLong() * strip.read.height <= DetectorPacking.MAX_PIXELS)
                    assertEquals(DetectorPacking.plan(strip.read.width, strip.read.height), strip.detector)
                }
                for (y in 0 until height) for (x in listOf(.5, width - .5)) {
                    assertEquals("$width x $height at $x,$y", 1,
                        plan.strips.count { it.ownsSourceCenter(x, y + .5) })
                    assertTrue(plan.strips.any { y >= it.read.top && y < it.read.bottom })
                }
            }
        }
    }

    @Test fun readHaloOverlapsButCoreSeamsAreUnambiguous() {
        val plan = FullPageStripPlanner.plan(1176, 2400)
        for ((previous, next) in plan.strips.zipWithNext()) {
            val seam = previous.core.bottom
            assertEquals(seam, next.core.top)
            assertEquals(seam + 64, previous.read.bottom)
            assertEquals(seam - 64, next.read.top)
            assertFalse(previous.ownsSourceCenter(500.0, seam.toDouble()))
            assertTrue(next.ownsSourceCenter(500.0, seam.toDouble()))
        }
        assertFalse(plan.strips.first().ownsSourceCenter(-.1, 0.0))
        assertFalse(plan.strips.last().ownsSourceCenter(1176.0, 2399.0))
        assertFalse(plan.strips.last().ownsSourceCenter(500.0, 2400.0))
    }

    @Test fun detectorEdgesAndInteriorMapBackToOriginalScreenCoordinates() {
        for (strip in FullPageStripPlanner.plan(1176, 2400).strips) {
            assertEquals(0.0, strip.sourceX(0.0), 0.0)
            assertEquals(1176.0, strip.sourceX(strip.detector.width.toDouble()), 0.0)
            assertEquals(strip.read.top.toDouble(), strip.sourceY(0.0), 0.0)
            assertEquals(strip.read.bottom.toDouble(), strip.sourceY(strip.detector.height.toDouble()), 0.0)
            for (fraction in listOf(.125, .5, .875)) {
                assertEquals(1176 * fraction, strip.sourceX(strip.detector.width * fraction), 1e-9)
                assertEquals(strip.read.top + strip.read.height * fraction,
                    strip.sourceY(strip.detector.height * fraction), 1e-9)
            }
        }
    }

    @Test fun mappingRejectsNonfiniteOrOutsideDetectorCoordinates() {
        val s = FullPageStripPlanner.plan(1176, 2400).strips[1]
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -.01)) {
            invalid { s.sourceX(bad) }; invalid { s.sourceY(bad) }
            assertFalse(s.ownsSourceCenter(bad, 500.0)); assertFalse(s.ownsSourceCenter(500.0, bad))
        }
        invalid { s.sourceX(s.detector.width + .01) }
        invalid { s.sourceY(s.detector.height + .01) }
    }

    @Test fun rejectsUnsupportedGeometryAndWholeFrameBudgetBeforePlanning() {
        for ((w, h) in listOf(0 to 2400, -1 to 2400, 31 to 2400, 1176 to 31,
            2049 to 1000, 2400 to 1176, 1080 to 4097, 2048 to 4096, Int.MAX_VALUE to Int.MAX_VALUE)) {
            invalid { FullPageStripPlanner.plan(w, h) }
        }
        assertEquals(4_194_304, 1024 * 4096)
        assertEquals(4096, FullPageStripPlanner.plan(1024, 4096).sourceHeight)
    }

    @Test fun adaptiveDetectorThresholdsAndRoundingEdgesStayWithinBothBudgets() {
        for (w in listOf(959, 960, 961, 1499, 1500, 1501, 1999, 2000, 2001, 2031, 2032, 2033, 2048)) {
            for (h in listOf(831, 832, 833, 1500, 2400, 4096)) {
                if (w.toLong() * h > FullPageStripPlanner.MAX_FRAME_PIXELS) continue
                for (s in FullPageStripPlanner.plan(w, h).strips) {
                    assertTrue(s.read.width.toLong() * s.read.height <= 1_048_576)
                    assertTrue(s.detector.width.toLong() * s.detector.height <= 1_048_576)
                    assertEquals(0, s.detector.width % 32)
                    assertEquals(0, s.detector.height % 32)
                    assertTrue(s.read.top >= 0 && s.read.bottom <= h)
                }
            }
        }
    }

    @Test fun smallPageHasOneUncroppedStripAndCollectionCannotBeChanged() {
        val p = FullPageStripPlanner.plan(320, 333)
        assertEquals(1, p.strips.size)
        assertEquals(FullPageStripPlanner.Rect(0, 0, 320, 333), p.strips.single().read)
        assertEquals(p.strips.single().read, p.strips.single().core)
        assertThrows(UnsupportedOperationException::class.java) { (p.strips as MutableList).clear() }
    }
}
