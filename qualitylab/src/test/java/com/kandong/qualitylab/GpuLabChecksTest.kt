package com.kandong.qualitylab

import org.junit.Assert.*
import org.junit.Test

class GpuLabChecksTest {
    @Test fun dimensionsUseLongBeforeMultiplicationAndRejectMalformedInput() {
        val shape = GpuLabChecks.shape(320, 160, 51_200, 5)
        assertEquals(GpuLabChecks.Shape(1600, 800, 1_280_000), shape)
        for ((w, h, n, scale) in listOf(
            listOf(Int.MAX_VALUE, Int.MAX_VALUE, 1, 5), listOf(Int.MAX_VALUE, 2, 1, 1),
            listOf(320, 161, 51_520, 5), listOf(0, 1, 0, 1), listOf(-1, 1, 1, 1),
            listOf(1, 1, 1, 0), listOf(1, 1, 1, 6), listOf(1, 2, 1, 2)
        )) assertThrows(IllegalArgumentException::class.java) { GpuLabChecks.shape(w, h, n, scale) }
    }

    @Test fun deviceCapsCheckBothAxesAndViewportNotJustPixelCount() {
        val shape = GpuLabChecks.shape(1, 51_200, 51_200, 1)
        assertThrows(IllegalArgumentException::class.java) { GpuLabChecks.deviceLimits(1, 51_200, shape, 4096, intArrayOf(4096, 4096)) }
        val normal = GpuLabChecks.shape(320, 160, 51_200, 5)
        GpuLabChecks.deviceLimits(320, 160, normal, 2048, intArrayOf(2048, 1024))
        assertThrows(IllegalArgumentException::class.java) { GpuLabChecks.deviceLimits(320, 160, normal, 2048, intArrayOf(1024, 1024)) }
        assertThrows(IllegalArgumentException::class.java) { GpuLabChecks.deviceLimits(320, 160, normal, 2048, intArrayOf(2048, 799)) }
    }

    @Test fun nonOpaqueInputsAreRejected() {
        GpuLabChecks.opaque(intArrayOf(-1, 0xff123456.toInt()))
        assertThrows(IllegalArgumentException::class.java) { GpuLabChecks.opaque(intArrayOf(0x7f123456)) }
    }

    private fun compare(expected: IntArray, actual: IntArray, exact: Boolean = false) =
        GpuLabChecks.compare(expected, actual, expected.size, 1, actual.size, 1, exact)

    @Test fun perChannelMeanCannotBeDilutedByOtherChannelsOrAlpha() {
        val reference = IntArray(10) { 0xff808080.toInt() }
        val actual = reference.clone().apply { this[0] += 2 shl 16 }
        val metrics = compare(reference, actual)
        assertEquals(0.2, metrics.channels[0].mean, 0.000001)
        assertEquals(2, metrics.channels[0].max)
        assertTrue(metrics.channels[1].passed)
        assertTrue(metrics.channels[2].passed)
        assertFalse(metrics.passed)
    }

    @Test fun meanThresholdIsInclusiveAndMaxThresholdIndependent() {
        val reference = IntArray(20) { 0xff808080.toInt() }
        val boundary = reference.clone().apply { this[0] += 2 }
        assertTrue(compare(reference, boundary).passed)
        val referenceLarge = IntArray(100) { 0xff808080.toInt() }
        val spike = referenceLarge.clone().apply { this[0] += 3 }
        assertEquals(0.03, compare(referenceLarge, spike).channels[2].mean, 0.000001)
        assertFalse(compare(referenceLarge, spike).passed)
    }

    @Test fun fixturesAreNotAveragedTogether() {
        val bad = compare(intArrayOf(0xff808080.toInt()), intArrayOf(0xff808081.toInt()))
        val largePerfect = compare(IntArray(51_200) { -1 }, IntArray(51_200) { -1 })
        assertFalse(listOf(bad, largePerfect).all { it.passed })
    }

    @Test fun oneTimesRequiresExactEvenWhenToleranceWouldPass() {
        val reference = IntArray(20) { 0xff808080.toInt() }
        val actual = reference.clone().apply { this[0]++ }
        assertTrue(compare(reference, actual).passed)
        assertFalse(compare(reference, actual, true).passed)
        assertTrue(compare(reference, reference.clone(), true).passed)
    }

    @Test fun alphaAndDimensionsAreSeparateHardFailures() {
        assertFalse(compare(intArrayOf(-1), intArrayOf(0x7fffffff)).passed)
        val reference = IntArray(6) { -1 }
        assertFalse(GpuLabChecks.compare(reference, reference, 3, 2, 2, 3, false).passed)
        assertFalse(compare(reference, IntArray(5) { -1 }).passed)
    }

    @Test fun asymmetricMarkersDetectRowFlipChannelSwapAndRotation() {
        val fixture = GpuLabFixtures.small().single { it.name == "asymmetric-colored-corners" }
        fun passes(pixels: IntArray) = GpuLabChecks.corners(fixture.pixels, fixture.width, fixture.height,
            pixels, fixture.width, fixture.height).all { it.passed }
        assertTrue(passes(fixture.pixels))
        val swapped = fixture.pixels.map { (it and 0xff00ff00.toInt()) or ((it and 255) shl 16) or ((it ushr 16) and 255) }.toIntArray()
        assertFalse(passes(swapped))
        val flipped = IntArray(fixture.pixels.size) { i ->
            fixture.pixels[(fixture.height - 1 - i / fixture.width) * fixture.width + i % fixture.width]
        }
        assertFalse(passes(flipped))
        assertFalse(passes(fixture.pixels.reversedArray()))
    }

    @Test fun allFixturesAreBoundedOpaqueDeterministicAndCoverDegenerateAxes() {
        val fixtures = GpuLabFixtures.small()
        assertEquals(11, fixtures.size)
        assertTrue(fixtures.any { it.width == 1 && it.height > 1 })
        assertTrue(fixtures.any { it.height == 1 && it.width > 1 })
        fixtures.zip(GpuLabFixtures.small()).forEach { (a, b) ->
            GpuLabChecks.shape(a.width, a.height, a.pixels.size, 5)
            GpuLabChecks.opaque(a.pixels)
            assertArrayEquals(a.pixels, b.pixels)
        }
    }

    @Test fun nearestRankP95ForFifteenIsMaxAndMedianIsEighth() {
        val stats = GpuLabChecks.stats((15 downTo 1).map { it.toDouble() })
        assertEquals(8.0, stats.median, 0.0)
        assertEquals(15.0, stats.p95, 0.0)
        assertEquals(15.0, stats.samples.first(), 0.0)
        assertThrows(IllegalArgumentException::class.java) { GpuLabChecks.stats(listOf(Double.NaN)) }
    }

    @Test fun timingGateRequiresBothMedianAndTailForCompletionAndTotalStrictlyLower() {
        val cpu = GpuLabChecks.stats(List(15) { 10.0 })
        val faster = GpuLabChecks.stats(List(15) { 9.0 })
        val tail = GpuLabChecks.stats(List(14) { 8.0 } + 11.0)
        assertTrue(GpuLabChecks.faster(cpu, faster, faster))
        assertFalse(GpuLabChecks.faster(cpu, cpu, faster))
        assertFalse(GpuLabChecks.faster(cpu, faster, cpu))
        assertFalse(GpuLabChecks.faster(cpu, tail, faster))
        assertFalse(GpuLabChecks.faster(cpu, faster, tail))
    }
}
