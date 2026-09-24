package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

class GeometryProbeGuardTest {
    private fun p(x: Double, y: Double) = GeometryProbeContract.Point(x, y)
    private fun rectangle(w: Double, h: Double) = listOf(p(0.0, 0.0), p(w, 0.0), p(w, h), p(0.0, h))
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun dimensionsAndN1hwAreBoundedBeforeArrayAllocation() {
        assertEquals(1_048_576, GeometryProbeContract.pixels(1024, 1024))
        for ((w, h) in listOf(0 to 1, -1 to 1, 4097 to 1, 4096 to 4096, Int.MAX_VALUE to Int.MAX_VALUE)) {
            rejects { GeometryProbeContract.pixels(w, h) }
        }
        for (shape in listOf(longArrayOf(1, 1, 1), longArrayOf(2, 1, 1, 1), longArrayOf(1, 3, 1, 1),
            longArrayOf(1, 1, Long.MAX_VALUE, 2), longArrayOf(1, 1, 1024, 1025))) {
            rejects { GeometryProbeContract.probabilityPixels(shape) }
        }
        rejects { GeometryProbeContract.threshold(longArrayOf(1, 1, 2, 2), floatArrayOf(0f)) }
        rejects { GeometryProbeContract.bgr(1, 1, intArrayOf(0x00123456)) }
        assertArrayEquals(byteArrayOf(0x56, 0x34, 0x12), GeometryProbeContract.bgr(1, 1, intArrayOf(0xff123456.toInt())))
    }

    @Test fun thresholdIsStrictAndNonFiniteProbabilitiesAreRejected() {
        val values = floatArrayOf(Math.nextDown(0.3f), 0.3f, Math.nextUp(0.3f), 0f, 1f)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, 1), GeometryProbeContract.threshold(longArrayOf(1, 1, 1, 5), values))
        for (f in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, Math.nextUp(1f))) {
            rejects { GeometryProbeContract.threshold(longArrayOf(1, 1, 1, 1), floatArrayOf(f)) }
        }
    }

    @Test fun quadMustBeOrderedUniqueStrictlyConvexAndInsideSource() {
        val good = rectangle(10.0, 12.0)
        assertEquals(10, GeometryProbeContract.cropPlan(32, 32, good).width)
        val invalid = listOf(good.take(3), listOf(good[0], good[1], good[1], good[3]),
            listOf(good[0], good[2], good[1], good[3]), rectangle(0.0, 12.0),
            listOf(p(0.0, 0.0), p(12.0, 0.0), p(1.0, 1.0), p(0.0, 12.0)),
            listOf(p(0.0, 0.0), p(4.0, 0.0), p(8.0, 0.0), p(0.0, 8.0)),
            rectangle(32.0, 12.0), rectangle(Double.NaN, 12.0), rectangle(Double.POSITIVE_INFINITY, 12.0),
            rectangle(-1.0, 12.0), rectangle(0.9, 0.9))
        invalid.forEach { rejects { GeometryProbeContract.cropPlan(32, 32, it) } }
    }

    @Test fun cropNormRoundsInFloat32BeforeTruncationAndRotationUsesPixelIndices() {
        val plan = GeometryProbeContract.cropPlan(32, 32, rectangle(10.0 - 1e-8, 15.0))
        assertEquals(10, plan.width); assertEquals(15, plan.height); assertTrue(plan.rotate)
        assertFalse(GeometryProbeContract.cropPlan(32, 32, rectangle(10.0, 14.0)).rotate)
        val rotated = GeometryProbeContract.rotatePixel(p(0.0, 14.0), plan)
        assertEquals(p(14.0, 9.0), rotated)
        assertEquals(p(0.0, 14.0), GeometryProbeContract.unrotatePixel(rotated, plan))
    }

    @Test fun runBudgetBoundsContourWrappersWithoutDiscardingCandidateCount() {
        val w = 128; val mask = ByteArray(w * w)
        for (y in 0 until 32) for (x in 0 until 32) mask[(y * 4 + 1) * w + x * 4 + 1] = 1
        assertEquals(2176L, GeometryProbeContract.rowRuns(w, w, mask))
        assertTrue(GeometryProbeContract.rowRuns(w, w, mask) <= GeometryProbeContract.CONTOUR_BUDGET)
        val checker = ByteArray(w * w) { ((it / w + it % w) % 2).toByte() }
        assertTrue(GeometryProbeContract.rowRuns(w, w, checker) > GeometryProbeContract.CONTOUR_BUDGET)
        rejects { GeometryProbeContract.rowRuns(1, 1, byteArrayOf(2)) }
        assertEquals(GeometryProbeContract.Status.INCOMPLETE_CANDIDATE_LIMIT, GeometryProbeContract.contourStatus(1024))
        assertEquals(GeometryProbeContract.Status.COMPLETE, GeometryProbeContract.contourStatus(1000))
    }

    @Test fun homographyHasFiniteInverseAndRejectsRelativeSingularities() {
        val q = listOf(p(2.0, 3.0), p(20.0, 4.0), p(19.0, 29.0), p(3.0, 28.0))
        val plan = GeometryProbeContract.cropPlan(32, 32, q)
        val matrix = GeometryProbeContract.homography(q, plan)
        val inverse = GeometryProbeContract.inverse(matrix)
        q.zip(GeometryProbeContract.target(plan)).forEach { (source, target) ->
            val actual = GeometryProbeContract.project(matrix, source)
            assertEquals(target.x, actual.x, 1e-6); assertEquals(target.y, actual.y, 1e-6)
            val roundtrip = GeometryProbeContract.project(inverse, actual)
            assertEquals(source.x, roundtrip.x, 1e-6); assertEquals(source.y, roundtrip.y, 1e-6)
            val rotated = GeometryProbeContract.rotatePixel(actual, plan)
            val restored = GeometryProbeContract.project(inverse, GeometryProbeContract.unrotatePixel(rotated, plan))
            assertEquals(source.x, restored.x, 1e-6); assertEquals(source.y, restored.y, 1e-6)
        }
        for (matrixBad in listOf(DoubleArray(9), doubleArrayOf(1.0, 0.0, 0.0, 1.0, 1e-13, 0.0, 0.0, 0.0, 1.0),
            doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))) {
            rejects { GeometryProbeContract.inverse(matrixBad) }
        }
        rejects { GeometryProbeContract.project(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, -1.0 + 1e-13), p(1.0, 0.0)) }
    }
}
