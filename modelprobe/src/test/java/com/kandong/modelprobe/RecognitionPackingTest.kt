package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import java.util.zip.GZIPInputStream

class RecognitionPackingTest {
    private fun bytes(path: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream(path)).use { it.readBytes() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun tensorBytes(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).also { b -> values.forEach { b.putFloat(it) } }.array()

    @Test fun frozenUpstreamCropsMatchEveryFloatBitAndRowOrder() {
        val props = Properties().apply { load(ByteArrayInputStream(bytes("manifest.properties"))) }
        val cases = props.getProperty("cases").split(",")
        assertEquals(listOf("en-quality-16", "fr-nonrefundable-16", "zh-hans-quality-16",
            "zh-hant-quality-16", "mixed-quality-16", "color-controls"), cases)
        for (id in cases) {
            fun p(key: String) = props.getProperty("$id.$key")
            val count = p("count").toInt()
            val sizes = (0 until count).map { RecognitionPacking.Size(p("$it.sourceWidth").toInt(), p("$it.sourceHeight").toInt()) }
            val rows = (0 until count).map {
                val raw = GZIPInputStream(ByteArrayInputStream(bytes(p("$it.resizedArgb")))).use { input -> input.readBytes() }
                val pixels = IntArray(raw.size / 4)
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
                RecognitionPacking.Resized(p("$it.resizedWidth").toInt(), 48, pixels)
            }
            val plan = RecognitionPacking.plan(sizes)
            assertEquals(id, p("width").toInt(), plan.width)
            assertEquals(id, p("order").split(",").map { it.toInt() }, plan.tensorRowToInput)
            val expected = GZIPInputStream(ByteArrayInputStream(bytes(p("expectedTensor")))).use { it.readBytes() }
            assertEquals(id, p("tensorSha256"), sha(expected))
            assertArrayEquals(id, expected, tensorBytes(RecognitionPacking.pack(sizes, rows)))
        }
    }

    @Test fun bgrPlanesAndZeroPaddingHaveDistinctMeaning() {
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())
        val pixels = IntArray(3 * 48) { colors[it % 3] }
        val result = RecognitionPacking.pack(listOf(RecognitionPacking.Size(3, 48)),
            listOf(RecognitionPacking.Resized(3, 48, pixels)))
        val plane = 48 * 320
        assertEquals(-1f, result[0], 0f) // Red has zero blue.
        assertEquals(1f, result[2], 0f)
        assertEquals(1f, result[plane + 1], 0f)
        assertEquals(1f, result[2 * plane], 0f)
        assertEquals(-1f, result[2 * plane + 2], 0f)
        for (c in 0..2) for (y in 0 until 48) for (x in 3 until 320) {
            assertEquals(0, result[c * plane + y * 320 + x].toRawBits())
        }
    }

    @Test fun batchFloorCropCeilClampAndStableTies() {
        // 48*530/37 = 687.567...: batch floor caps the row's ceil.
        val single = RecognitionPacking.plan(listOf(RecognitionPacking.Size(530, 37)))
        assertEquals(687, single.width)
        assertEquals(listOf(687), single.resizedWidths)
        val plan = RecognitionPacking.plan(listOf(RecognitionPacking.Size(41, 11),
            RecognitionPacking.Size(17, 7), RecognitionPacking.Size(34, 14)))
        assertEquals(320, plan.width)
        assertEquals(listOf(179, 117, 117), plan.resizedWidths)
        assertEquals(listOf(1, 2, 0), plan.tensorRowToInput)
    }

    @Test fun rejectInvalidDimensionsAndBoundAllocation() {
        val invalid = listOf(emptyList(), List(5) { RecognitionPacking.Size(1, 1) },
            listOf(RecognitionPacking.Size(0, 20)), listOf(RecognitionPacking.Size(10, -1)),
            listOf(RecognitionPacking.Size(Int.MAX_VALUE, 1)), listOf(RecognitionPacking.Size(4096, 1)),
            listOf(RecognitionPacking.Size(1, 2049)))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { RecognitionPacking.plan(it) } }
        val edge = RecognitionPacking.plan(List(4) { RecognitionPacking.Size(2048, 48) })
        assertEquals(2048, edge.width)
        assertTrue(4 * 3 * 48 * edge.width <= 1_200_000)
    }

    @Test fun rejectWrongCountShapeLengthAndUncompositedAlpha() {
        val sizes = listOf(RecognitionPacking.Size(1, 48))
        val opaque = IntArray(48) { 0xffffffff.toInt() }
        val badRows = listOf(emptyList(), listOf(RecognitionPacking.Resized(2, 48, opaque)),
            listOf(RecognitionPacking.Resized(1, 47, opaque)),
            listOf(RecognitionPacking.Resized(1, 48, opaque.copyOf(47))),
            listOf(RecognitionPacking.Resized(1, 48, IntArray(48) { 0x00ffffff })))
        badRows.forEach { assertThrows(IllegalArgumentException::class.java) { RecognitionPacking.pack(sizes, it) } }
    }
}
