package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream

class DetectorPackingTest {
    private fun bytes(path: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("detector-v1/$path"))
        .use { it.readNBytes(2 * 1024 * 1024 + 1) }.also { require(it.size <= 2 * 1024 * 1024) }
    private fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    private fun inflate(path: String, size: Int) = GZIPInputStream(ByteArrayInputStream(bytes(path))).use { it.readNBytes(size + 1) }
        .also { assertEquals(size, it.size) }
    private fun serialized(values: FloatArray) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .also { b -> values.forEach { b.putFloat(it) } }.array()

    @Test fun allTenFrozenCasesMatchIndependentInputBytes() {
        assertEquals("74aa1e39c8d3187ee2388ad07bb228e3c876c0486ccd02c47208942d26f2c80a", sha(bytes("manifest.json")))
        val raw = bytes("manifest.properties")
        assertEquals("2108ab792352beccceff752db3847b46d6ccc97b814b322cecdeb7e145e58084", sha(raw))
        val p = Properties().apply { load(ByteArrayInputStream(raw)) }
        val ids = p.getProperty("cases").split(",")
        assertEquals(listOf("en-quality-16", "fr-nonrefundable-16", "zh-hans-quality-16", "zh-hant-quality-16",
            "mixed-quality-16", "blank-negative-24", "color-control", "wide-959", "wide-1499", "wide-2001"), ids)
        ids.forEach { id ->
            fun value(key: String) = p.getProperty("$id.$key")
            val plan = DetectorPacking.plan(value("sourceWidth").toInt(), value("sourceHeight").toInt())
            assertEquals(id, value("width").toInt(), plan.width); assertEquals(id, value("height").toInt(), plan.height)
            val pixels = IntArray(plan.width * plan.height)
            ByteBuffer.wrap(inflate(value("resizedArgb"), pixels.size * 4)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
            val expected = inflate(value("input"), pixels.size * 12)
            assertEquals(id, value("inputSha256"), sha(expected))
            assertArrayEquals(id, expected, serialized(DetectorPacking.pack(plan.width, plan.height, pixels)))
        }
    }

    @Test fun limitBoundariesTruncationTiesEvenAndTranspose() {
        val widths = listOf(959, 960, 1280, 1499, 1500, 2001, 2500)
        val expected = listOf(960, 960, 1280, 1504, 1504, 1984, 1984)
        val limits = listOf(960, 1500, 1500, 1500, 2000, 2000, 2000)
        widths.forEachIndexed { i, w ->
            val plan = DetectorPacking.plan(w, 100)
            assertEquals(expected[i], plan.width); assertEquals(limits[i], plan.effectiveLimit)
            assertEquals(if (w == 2500) 64 else 96, plan.height)
            val transpose = DetectorPacking.plan(100, w)
            assertEquals(plan.width, transpose.height); assertEquals(plan.height, transpose.width)
        }
        assertEquals(DetectorPacking.Plan(64, 32, 960), DetectorPacking.plan(48, 17))
        assertEquals(DetectorPacking.Plan(32, 64, 960), DetectorPacking.plan(17, 48))
        assertEquals(64, DetectorPacking.plan(80, 80).width)
        assertEquals(128, DetectorPacking.plan(112, 112).width)
        // 81 * (2000/2001) truncates to 80; rounding first would yield 96, not 64.
        assertEquals(64, DetectorPacking.plan(2001, 81).height)
        assertEquals(1_048_576, DetectorPacking.pixels(1024, 1024))
        assertEquals(1_048_576, DetectorPacking.pixels(2048, 512))
    }

    @Test fun channelsRowsAndAllGrayLevelsKeepDetectorOperationOrder() {
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xff123456.toInt())
        val pixels = IntArray(32 * 32) { if (it < 4) colors[it] else 0xff000000.toInt() or ((it % 256) * 0x010101) }
        val actual = DetectorPacking.pack(32, 32, pixels)
        assertEquals(-1f, actual[0], 0f); assertEquals(1f, actual[2], 0f)
        assertEquals(1f, actual[1025], 0f); assertEquals(1f, actual[2048], 0f)
        assertNotEquals(actual[3], actual[1027]); assertNotEquals(actual[1027], actual[2051])
        var recognitionDifferences = 0
        for (gray in 0..255) {
            val i = 256 + gray
            val expected = ((gray.toFloat() * (1.0 / 255).toFloat()).toDouble() * 2 - 1).toFloat()
            assertEquals(expected.toRawBits(), actual[i].toRawBits())
            assertEquals(actual[i].toRawBits(), actual[1024 + i].toRawBits())
            if (actual[i].toRawBits() != ((gray / 255f - 0.5f) / 0.5f).toRawBits()) recognitionDifferences++
        }
        assertEquals(111, recognitionDifferences)
    }

    @Test fun rejectInvalidSourceDimensionsZeroRoundAndBudgetsBeforeAllocation() {
        listOf(0 to 100, -1 to 100, Int.MAX_VALUE to 20, 4097 to 20, 20 to 4097,
            48 to 16, 16 to 80, 1 to 1, 4096 to 4096, 1056 to 1024).forEach { (w, h) ->
            assertThrows(IllegalArgumentException::class.java) { DetectorPacking.plan(w, h) }
        }
        listOf(0 to 32, 31 to 32, 2049 to 32, 2048 to 544, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (w, h) ->
            assertThrows(IllegalArgumentException::class.java) { DetectorPacking.pack(w, h, intArrayOf()) }
        }
        assertThrows(IllegalArgumentException::class.java) { DetectorPacking.pack(32, 32, IntArray(1023)) }
        assertThrows(IllegalArgumentException::class.java) { DetectorPacking.pack(32, 32, IntArray(1024) { 0x7fffffff }) }
        listOf(longArrayOf(1, 3, Long.MAX_VALUE, 32), longArrayOf(1, 3, 32), longArrayOf(2, 3, 32, 32),
            longArrayOf(1, 1, 32, 32), longArrayOf(1, 3, 2048, 2048)).forEach {
            assertThrows(IllegalArgumentException::class.java) { DetectorPacking.elements(it, 3) }
        }
    }

    private fun compare(a: FloatArray, r: FloatArray) = DetectorComparison.compare(FloatBuffer.wrap(a), r,
        ByteArray(r.size) { if (r[it] > 0.3f) 1 else 0 })

    @Test fun strictThresholdHasNoEqualityPositiveAndNoPermittedFlips() {
        val below = Math.nextDown(0.3f); val above = Math.nextUp(0.3f)
        val r = floatArrayOf(below, 0.3f, above, 0f, 1f)
        val exact = compare(r, r)
        assertTrue(exact.passed); assertEquals(2, exact.positivePixels); assertEquals(3, exact.nearThresholdActual)
        val flipped = compare(floatArrayOf(below, above, above, 0f, 1f), r)
        assertEquals(0, flipped.toleranceViolations); assertEquals(1, flipped.maskFlips); assertFalse(flipped.passed)
        assertNotEquals(exact.maskSha256, flipped.maskSha256)
    }

    @Test fun toleranceMeanBitsRangeAndNonFiniteGatesAreIndependent() {
        val signedZero = compare(floatArrayOf(-0f), floatArrayOf(0f))
        assertTrue(signedZero.passed); assertEquals(1, signedZero.bitDifferences)
        val mean = compare(floatArrayOf(0.000002f), floatArrayOf(0f))
        assertEquals(0, mean.toleranceViolations); assertFalse(mean.passed)
        assertEquals(Double.POSITIVE_INFINITY, mean.maxRelative, 0.0)
        assertFalse(compare(floatArrayOf(0.21f), floatArrayOf(0.2f)).passed)
        assertEquals(1, compare(floatArrayOf(0.21f), floatArrayOf(0.2f)).toleranceViolations)
        val rtolAllowed = compare(FloatArray(100) { if (it == 0) 0.50004f else 0.5f }, FloatArray(100) { 0.5f })
        assertTrue(rtolAllowed.passed)
        val range = compare(floatArrayOf(-0.0000001f), floatArrayOf(0f))
        assertEquals(0, range.toleranceViolations); assertEquals(1, range.outOfRange); assertFalse(range.passed)
        val upperRange = compare(floatArrayOf(Math.nextUp(1f)), floatArrayOf(1f))
        assertEquals(0, upperRange.toleranceViolations); assertEquals(1, upperRange.outOfRange); assertFalse(upperRange.passed)
        // Dilute the single absolute-error sample so the independent mean gate does not mask this boundary.
        val absoluteAllowed = compare(FloatArray(100) { if (it == 0) 0.00001f else 0f }, FloatArray(100))
        assertTrue(absoluteAllowed.passed)
        val absoluteRejected = compare(FloatArray(100) { if (it == 0) Math.nextUp(0.00001f) else 0f }, FloatArray(100))
        assertEquals(1, absoluteRejected.toleranceViolations); assertFalse(absoluteRejected.passed)
        for (f in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val result = compare(floatArrayOf(f), floatArrayOf(0f))
            assertFalse(result.passed); assertEquals(1, result.nonFinite); assertEquals(1, result.toleranceViolations)
        }
        assertThrows(IllegalArgumentException::class.java) { compare(floatArrayOf(0f), floatArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { compare(floatArrayOf(0f, 0f), floatArrayOf(0f)) }
        assertThrows(IllegalArgumentException::class.java) { compare(floatArrayOf(0f), floatArrayOf(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { DetectorComparison.compare(FloatBuffer.wrap(floatArrayOf(0.3f)), floatArrayOf(0.3f), byteArrayOf(1)) }
    }
}
