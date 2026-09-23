package com.kandong.graphics

import org.junit.Assert.*
import org.junit.Test

class GpuViewportTest {
    private fun table(input: Int, output: Int, scale: Float = 1f, pan: Float = 0f) =
        GpuSamplingWeights.viewport(input, output, scale, pan)
    private fun first(t: FloatArray, i: Int) = t[t.size / 2 + i * 4].toInt()
    private fun inside(t: FloatArray, i: Int) = t[t.size / 2 + i * 4 + 1] == 1f
    private fun weights(t: FloatArray, i: Int) = t.copyOfRange(i * 4, i * 4 + 4)

    @Test fun oneTimesIntegerTranslationIsExactWithWhiteMargins() {
        val t = table(3, 7, pan = 2f)
        assertEquals(listOf(false, false, true, true, true, false, false), (0..6).map { inside(t, it) })
        for (i in 2..4) {
            assertEquals(i - 3, first(t, i))
            assertArrayEquals(floatArrayOf(0f, 1f, 0f, 0f), weights(t, i), 0f)
        }
    }

    @Test fun fractionalPanAtOneTimesUsesBilinearWeights() {
        val t = table(3, 5, pan = 0.25f)
        assertEquals(0, first(t, 2))
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.75f, 0f), weights(t, 2), 0f)
        assertFalse(inside(t, 3))
        // Negative first taps are clamped by the shader to the first source pixel.
        assertEquals(-2, first(t, 0))
        assertTrue(inside(t, 0))
    }

    @Test fun centeredOddSizedViewportAndSinglePixelAxisKeepSymmetricMargins() {
        val x = table(1, 9, 2.5f, (9f - 2.5f) / 2f)
        assertEquals(listOf(3, 4, 5), (0..8).filter { inside(x, it) })
        for (i in 3..5) assertEquals(1f, weights(x, i).sum(), 0.000001f)
        val y = table(5, 7, 1.25f, (7f - 5f * 1.25f) / 2f)
        assertTrue((0..6).all { inside(y, it) })
    }

    @Test fun continuousZoomAndPanFollowPixelCentersAndPreserveNormalization() {
        for (scale in listOf(1.001f, 1.3f, 2.25f, 3.7f, 4.999f, 5f)) {
            val t = table(17, 29, scale, -3.25f)
            for (i in 0 until 29) {
                val source = (i + 0.5f + 3.25f) / scale - 0.5f
                if (inside(t, i)) {
                    assertEquals(kotlin.math.floor(source).toInt() - 1, first(t, i))
                    assertEquals(1f, weights(t, i).sum(), 0.000001f)
                    assertTrue(weights(t, i).all { it.isFinite() })
                }
            }
        }
    }

    @Test fun integerCoefficientsMatchAcceptedLaboratoryPathBitForBit() {
        for (input in listOf(1, 3, 17, 320)) for (scale in 2..5) {
            val t = table(input, input * scale, scale.toFloat())
            val original = GpuSamplingWeights.create(input * scale, scale)
            assertArrayEquals(original, t.copyOfRange(0, original.size), 0f)
            for (i in 0 until input * scale) {
                val numerator = 2 * i + 1 - scale
                assertEquals((if (numerator < 0) -1 else numerator / (2 * scale)) - 1, first(t, i))
                assertTrue(inside(t, i))
            }
        }
    }

    @Test fun farOutsidePansStayWhiteWithoutIndexOverflow() {
        for (pan in listOf(-Float.MAX_VALUE, Float.MAX_VALUE, -100f, 100f)) {
            val t = table(1, 7, 5f, pan)
            assertTrue(t.all { it == 0f })
        }
        val edge = table(1, 3, pan = 0.5f)
        assertTrue(inside(edge, 0)) // Left edge inclusive.
        assertFalse(inside(edge, 1)) // Right edge exclusive.
    }

    @Test fun viewportBoundsUseLongAndRejectInvalidTransforms() {
        assertEquals(2_000_000, GpuChecks.output(1000, 2000).count)
        assertEquals(1_000_000, GpuChecks.input(1000, 1000))
        for ((w, h) in listOf(0 to 1, -1 to 1, Int.MAX_VALUE to Int.MAX_VALUE, 2001 to 1000)) {
            assertThrows(IllegalArgumentException::class.java) { GpuViewport(w, h, 2f, 0f, 0f) }
        }
        assertThrows(IllegalArgumentException::class.java) { GpuChecks.input(1001, 1000) }
        for (scale in listOf(0f, 0.999f, 5.001f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { GpuViewport(1, 1, scale, 0f, 0f) }
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { GpuViewport(1, 1, 1f, bad, 0f) }
            assertThrows(IllegalArgumentException::class.java) { GpuViewport(1, 1, 1f, 0f, bad) }
        }
        assertEquals(100, GpuViewport(100, 200, 5f, -123.5f, 0.25f).width)
    }

    @Test fun bothTextureAndViewportCapsAreCheckedForEachAxis() {
        val output = GpuChecks.output(1000, 2000)
        GpuChecks.deviceLimits(100, 100, output, 2048, intArrayOf(2048, 2048))
        for (caps in listOf(intArrayOf(999, 2048), intArrayOf(2048, 1999))) {
            assertThrows(IllegalArgumentException::class.java) { GpuChecks.deviceLimits(100, 100, output, 2048, caps) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpuChecks.deviceLimits(1, 4097, GpuChecks.output(1, 1), 4096, intArrayOf(8192, 8192))
        }
    }

    @Test fun integerApiRetainsOriginalShapeLimits() {
        assertEquals(1_280_000, GpuChecks.integerShape(320, 160, 51_200, 5).count)
        assertThrows(IllegalArgumentException::class.java) { GpuChecks.integerShape(321, 160, 51_360, 1) }
        assertThrows(IllegalArgumentException::class.java) { GpuChecks.integerShape(Int.MAX_VALUE, Int.MAX_VALUE, 1, 5) }
    }
}
