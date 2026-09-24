package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

internal class BoxPipelineGuardTest {
    private fun point(x: Double, y: Double) = GeometryProbeContract.Point(x, y)
    private fun quad(x: Double, y: Double) = listOf(point(x, y), point(x + 5, y), point(x + 5, y + 5), point(x, y + 5))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun shapeAndValuesAreValidatedBeforeThresholdAllocation() {
        rejected { GeometryProbeContract.threshold(longArrayOf(1, 1, 4096, 4096), floatArrayOf()) }
        rejected { GeometryProbeContract.threshold(longArrayOf(1, 1, 1, 2), floatArrayOf(0f)) }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.1f, 1.1f))
            rejected { GeometryProbeContract.threshold(longArrayOf(1, 1, 1, 1), floatArrayOf(bad)) }
        assertArrayEquals(byteArrayOf(0, 0, 1, 1), GeometryProbeContract.threshold(longArrayOf(1, 1, 1, 4),
            floatArrayOf(0f, .3f, Math.nextUp(.3f), 1f)))
    }
    @Test fun halfEvenAndInclusiveMappingAreFrozen() {
        assertEquals(2.0, BoxPipelineContract.mapCoordinate(5.0, 10, 5), 0.0)
        assertEquals(4.0, BoxPipelineContract.mapCoordinate(7.0, 10, 5), 0.0)
        assertEquals(5.0, BoxPipelineContract.mapCoordinate(50.0, 10, 5), 0.0)
        assertEquals(0.0, BoxPipelineContract.mapCoordinate(-1.0, 10, 5), 0.0)
        // float32 division followed by float32 multiplication, not a double ratio.
        assertEquals(Math.rint(((37.01778030395508.toFloat() / 640f) * 639f).toDouble()),
            BoxPipelineContract.mapCoordinate(37.01778030395508, 640, 639), 0.0)
        rejected { BoxPipelineContract.mapCoordinate(Double.NaN, 1, 1) }
    }
    @Test fun finalClippingAndTruncatedNormRejectThree() {
        val raw = listOf(point(4.0, 4.0), point(0.0, 0.0), point(0.0, 4.0), point(4.0, 0.0))
        val final = BoxPipelineContract.finalQuad(raw, 4, 4)
        assertEquals(listOf(point(0.0, 0.0), point(3.0, 0.0), point(3.0, 3.0), point(0.0, 3.0)), final)
        assertFalse(BoxPipelineContract.finalSizeAccepted(final))
        assertEquals(3, BoxPipelineContract.normInt(point(0.0, 0.0), point(3.99, 0.0)))
        assertTrue(BoxPipelineContract.finalSizeAccepted(quad(0.0, 0.0)))
    }
    @Test fun pairedStableOrderUsesAdjacentYAndTenStartsNewLine() {
        val boxes = listOf(
            BoxPipelineContract.Box(7, 3, 0, quad(30.0, 0.0), .51),
            BoxPipelineContract.Box(8, 4, 1, quad(10.0, 9.0), .75),
            BoxPipelineContract.Box(9, 5, 2, quad(20.0, 18.0), .9),
            BoxPipelineContract.Box(10, 6, 3, quad(0.0, 28.0), .6),
            BoxPipelineContract.Box(11, 7, 4, quad(10.0, 9.0), .8))
        val sorted = BoxPipelineContract.readingOrder(boxes)
        assertEquals(listOf(1, 4, 2, 0, 3), sorted.map { it.finalBoxIndex })
        assertEquals(listOf(.75, .8, .9, .51, .6), sorted.map { it.score })
        assertEquals(listOf(8, 11, 9, 7, 10), sorted.map { it.contourIndex })
        assertEquals(emptyList<BoxPipelineContract.Box>(), BoxPipelineContract.readingOrder(emptyList()))
    }
    @Test fun incompleteResultCannotExposePartialBoxes() {
        val box = BoxPipelineContract.Box(0, 0, 0, quad(0.0, 0.0), .8)
        for (status in BoxPipelineContract.Status.entries.filter { it != BoxPipelineContract.Status.COMPLETE }) {
            rejected { BoxPipelineContract.Result(status, byteArrayOf(), null, 0, null, null, emptyList(), listOf(box)) }
            assertTrue(BoxPipelineContract.Result(status, byteArrayOf(), null, 0, null, null, emptyList(), emptyList()).boxes.isEmpty())
        }
    }
    @Test fun contourComparisonPreservesShortPathsDuplicatesAndEveryVertex() {
        fun p(x: Long) = PolygonOffsetKernel.IntPoint(x, 0)
        assertTrue(BoxPipelineContract.sameContour(listOf(p(0)), listOf(p(0))))
        assertTrue(BoxPipelineContract.sameContour(listOf(p(0), p(1)), listOf(p(1), p(0))))
        val a = listOf(p(0), p(1), p(1), p(2))
        assertTrue(BoxPipelineContract.sameContour(a, listOf(p(1), p(0), p(2), p(1))))
        assertFalse(BoxPipelineContract.sameContour(a, listOf(p(0), p(1), p(2))))
        assertFalse(BoxPipelineContract.sameContour(a, listOf(p(0), p(1), p(2), p(2))))
    }
    @Test fun geometryLimitsCheckAllSizesBeforeCopies() {
        assertTrue(BoxPipelineContract.geometryFits(List(4) { 8192L }))
        assertFalse(BoxPipelineContract.geometryFits(listOf(8193L)))
        assertFalse(BoxPipelineContract.geometryFits(List(4) { 8192L } + 1L))
        assertFalse(BoxPipelineContract.geometryFits(listOf(Long.MAX_VALUE)))
        assertFalse(BoxPipelineContract.geometryFits(listOf(0L)))
        assertTrue(BoxPipelineContract.geometryFits(listOf(1L, 2L)))
    }
    @Test fun cropRowUsesActualBoxIdentityScoreAndHomography() {
        val box = BoxPipelineContract.Box(17, 9, 2, quad(10.0, 15.0), .625)
        val row = BoxPipelineContract.cropRow("synthetic-1", box, 0, 64, 64)
        assertEquals("synthetic-1.box-2", row.id); assertEquals(2, row.originalIndex)
        assertEquals(0, row.readingOrder); assertEquals(.625, row.detectorScore, 0.0)
        assertEquals(box.quad, row.quad); assertEquals(GeometryProbeContract.CropPlan(5, 5), row.plan)
        assertEquals("", row.crop)
        assertArrayEquals(GeometryProbeContract.homography(box.quad, row.plan), row.referenceMatrix.toDoubleArray(), 0.0)
    }
    @Test fun finalPointOrderMatchesPinnedYRuleOnBothSides() {
        // Pinned RapidOCR orders the rightmost pair by y, not y-x.
        // These convex corners make those two rules disagree.
        val expected = listOf(point(0.0, 0.0), point(20.0, 5.0), point(40.0, 15.0), point(0.0, 10.0))
        val shuffled = listOf(expected[2], expected[0], expected[3], expected[1])
        assertEquals(expected, BoxPipelineContract.finalQuad(shuffled, 100, 100))
    }

}
