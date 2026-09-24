package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test
import java.nio.FloatBuffer

class CtcDecoderTest {
    private val dictionary = listOf("blank", "中", "😀", " ")
    private fun values(vararg indices: Int): FloatBuffer = FloatBuffer.wrap(indices.flatMap { index ->
        (0..3).map { if (it == index) 1f else 0f }
    }.toFloatArray())
    @Test fun blankDuplicatesAfterBlankUnicodeAndSpace() {
        val shape = longArrayOf(1, 7, 4)
        val result = CtcDecoder.decode(values(0, 1, 1, 0, 1, 2, 3), shape, shape, dictionary)
        assertEquals(listOf("中中😀 "), result.raw)
        assertEquals("994f29593e9f992ea1f3a5f1601c6ef584fb4c1809dc5292b580655fdd791a74", result.argmaxSha256)
    }
    @Test fun batchesResetDuplicateState() {
        val shape = longArrayOf(2, 2, 4)
        assertEquals(listOf("中", "中"), CtcDecoder.decode(values(1, 1, 1, 0), shape, shape, dictionary).raw)
    }
    @Test fun tiesChooseFirstIncludingBlank() {
        val shape = longArrayOf(1, 2, 4)
        val scores = FloatBuffer.wrap(floatArrayOf(1f, 1f, 1f, 1f, 0f, 1f, 1f, 0f))
        assertEquals(listOf("中"), CtcDecoder.decode(scores, shape, shape, dictionary).raw)
    }
    @Test fun allValuesMustBeFiniteEvenNonWinning() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val shape = longArrayOf(1, 1, 4)
            assertThrows(ProbeFailure::class.java) {
                CtcDecoder.decode(FloatBuffer.wrap(floatArrayOf(1f, 0f, 0f, bad)), shape, shape, dictionary)
            }
        }
    }
    @Test fun rejectsShapeVocabularyLengthAndCap() {
        assertThrows(ProbeFailure::class.java) { CtcDecoder.validateShape(longArrayOf(1, 2), longArrayOf(1, 2), 4) }
        assertThrows(ProbeFailure::class.java) { CtcDecoder.validateShape(longArrayOf(1, 2, 4), longArrayOf(1, 3, 4), 4) }
        assertThrows(ProbeFailure::class.java) { CtcDecoder.validateShape(longArrayOf(1, 2, 4), longArrayOf(1, 2, 4), 3) }
        assertThrows(ProbeFailure::class.java) { CtcDecoder.validateShape(longArrayOf(3, Long.MAX_VALUE, 4), longArrayOf(3, Long.MAX_VALUE, 4), 4) }
        val shape = longArrayOf(1, 2, 4)
        assertThrows(ProbeFailure::class.java) { CtcDecoder.decode(values(1), shape, shape, dictionary) }
    }
    @Test fun noUnicodeNormalization() {
        val shape = longArrayOf(1, 1, 2)
        val result = CtcDecoder.decode(FloatBuffer.wrap(floatArrayOf(0f, 1f)), shape, shape, listOf("blank", "e\u0301"))
        assertEquals("e\u0301", result.raw.single())
        assertNotEquals("é", result.raw.single())
    }
}
