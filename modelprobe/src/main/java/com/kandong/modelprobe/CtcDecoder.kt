package com.kandong.modelprobe

import java.nio.FloatBuffer
import java.security.MessageDigest

data class Decoded(val raw: List<String>, val argmaxSha256: String, val shape: List<Long>)

object CtcDecoder {
    const val MAX_OUTPUT_FLOATS = 4_000_000L
    fun validateShape(shape: LongArray, pinned: LongArray, vocabulary: Int): Int {
        requireProbe(shape.size == 3 && shape.contentEquals(pinned), "OUTPUT_SHAPE")
        requireProbe(shape[0] in 1..3 && shape[1] > 0 && shape[2] == vocabulary.toLong() && vocabulary > 1, "OUTPUT_VOCABULARY")
        return ProbeInputs.product(shape, MAX_OUTPUT_FLOATS).toInt()
    }
    fun decode(values: FloatBuffer, shape: LongArray, pinned: LongArray, dictionary: List<String>): Decoded {
        val count = validateShape(shape, pinned, dictionary.size)
        requireProbe(values.remaining() == count, "OUTPUT_LENGTH")
        val data = values.slice()
        val digest = MessageDigest.getInstance("SHA-256")
        val indexBytes = ByteArray(4)
        val rows = ArrayList<String>()
        repeat(shape[0].toInt()) {
            val text = StringBuilder()
            var previous = -1
            repeat(shape[1].toInt()) {
                var best = Float.NEGATIVE_INFINITY
                var index = 0
                repeat(dictionary.size) { candidate ->
                    val score = data.get()
                    requireProbe(score.isFinite(), "OUTPUT_NONFINITE")
                    // Strict > makes ties choose the first index, including blank.
                    if (score > best) { best = score; index = candidate }
                }
                repeat(4) { byte -> indexBytes[byte] = (index ushr (byte * 8)).toByte() }
                digest.update(indexBytes)
                if (index != previous && index != 0) text.append(dictionary[index])
                previous = index // Collapse BEFORE removing blank; A,blank,A becomes AA.
            }
            rows += text.toString()
        }
        return Decoded(rows, ProbeInputs.hex(digest.digest()), shape.toList())
    }
}
