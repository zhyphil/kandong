package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class ProbeInputsTest {
    private val shape = longArrayOf(1, 3, 48, 1)
    private val raw = ByteArray(576)
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()
    private fun inflate(bytes: ByteArray, expectedRaw: ByteArray = raw) =
        ProbeInputs.inflate(bytes, shape, ProbeInputs.sha(bytes), ProbeInputs.sha(expectedRaw))
    @Test fun validInputAndExactBoundedRead() {
        assertArrayEquals(raw, inflate(gzip(raw)))
        assertArrayEquals(byteArrayOf(1, 2), ProbeInputs.bounded(ByteArrayInputStream(byteArrayOf(1, 2)), 2, 2))
        assertEquals(796608, ProbeInputs.inputBytes(longArrayOf(3, 3, 48, 461)))
    }
    @Test fun dimensionsAndOverflowRejected() {
        for (s in listOf(longArrayOf(0, 3, 48, 1), longArrayOf(4, 3, 48, 1), longArrayOf(1, 4, 48, 1),
            longArrayOf(1, 3, 47, 1), longArrayOf(1, 3, 48, 849), longArrayOf(1, 3, 48, Long.MAX_VALUE))) {
            assertThrows(ProbeFailure::class.java) { ProbeInputs.inputBytes(s) }
        }
        assertThrows(ProbeFailure::class.java) { ProbeInputs.product(longArrayOf(Long.MAX_VALUE, 8), Long.MAX_VALUE) }
        assertThrows(ProbeFailure::class.java) { ProbeInputs.product(longArrayOf(1200001), 1200000) }
    }
    @Test fun boundedRejectsTruncatedExtraAndBadCap() {
        assertThrows(ProbeFailure::class.java) { ProbeInputs.bounded(ByteArrayInputStream(byteArrayOf(1)), 2, 2) }
        assertThrows(ProbeFailure::class.java) { ProbeInputs.bounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2, 2) }
        assertThrows(ProbeFailure::class.java) { ProbeInputs.bounded(ByteArrayInputStream(raw), 0) }
    }
    @Test fun truncatedCorruptAndExtraGzipRejected() {
        val good = gzip(raw)
        assertThrows(ProbeFailure::class.java) { inflate(good.copyOf(good.size - 3)) }
        val corrupt = good.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }
        assertThrows(ProbeFailure::class.java) { inflate(corrupt) }
        assertThrows(ProbeFailure::class.java) { inflate(gzip(raw + byteArrayOf(0))) }
        assertThrows(ProbeFailure::class.java) { inflate(gzip(raw.copyOf(572))) }
        assertThrows(ProbeFailure::class.java) { inflate(ByteArray(ProbeInputs.MAX_GZIP + 1)) }
    }
    @Test fun digestsAndPathsAreEnforced() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ProbeInputs.sha(byteArrayOf()))
        val gz = gzip(raw)
        assertThrows(ProbeFailure::class.java) { ProbeInputs.inflate(gz, shape, "0".repeat(64), ProbeInputs.sha(raw)) }
        assertThrows(ProbeFailure::class.java) { ProbeInputs.inflate(gz, shape, ProbeInputs.sha(gz), "0".repeat(64)) }
        ProbeInputs.validatePath("probes/ch-en-quality-16.f32z")
        for (path in listOf("/tmp/input", "probes/../models/x", "https://test", "probes/unknown.f32z", "probes/ch-en-quality-16.f32.gz")) {
            assertThrows(ProbeFailure::class.java) { ProbeInputs.validatePath(path) }
        }
    }
    @Test fun floatNaNInfinityOutOfRangeRejectedAndEndpointsAccepted() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.001f, 1.001f)) {
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
            assertThrows(ProbeFailure::class.java) { ProbeInputs.validateFloats(bytes) }
        }
        val valid = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(-1f).putFloat(0f).putFloat(1f).array()
        ProbeInputs.validateFloats(valid)
        assertThrows(ProbeFailure::class.java) { ProbeInputs.validateFloats(byteArrayOf(0)) }
        val nan = raw.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN) }
        assertThrows(ProbeFailure::class.java) { inflate(gzip(nan), nan) }
    }
}
