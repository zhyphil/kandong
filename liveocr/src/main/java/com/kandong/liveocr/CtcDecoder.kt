package com.kandong.liveocr

import java.nio.FloatBuffer

internal data class Decoded(val raw: List<String>, val shape: List<Long>)

internal object CtcDecoder {
    const val MAX_OUTPUT_FLOATS = 4_000_000L
    fun validateShape(shape: LongArray, pinned: LongArray, vocabulary: Int): Int {
        requireOcr(shape.size == 3 && shape.contentEquals(pinned), OcrFailure.OUTPUT_SHAPE)
        requireOcr(shape[0] in 1..3 && shape[1] > 0 && shape[2] == vocabulary.toLong() && vocabulary > 1, OcrFailure.OUTPUT_VOCABULARY)
        return OcrAssets.product(shape, MAX_OUTPUT_FLOATS).toInt()
    }
    fun decode(values: FloatBuffer, shape: LongArray, pinned: LongArray, dictionary: List<String>): Decoded {
        val count = validateShape(shape, pinned, dictionary.size)
        requireOcr(values.remaining() == count, OcrFailure.OUTPUT_LENGTH)
        val data = values.slice()
        val rows = ArrayList<String>()
        repeat(shape[0].toInt()) {
            val text = StringBuilder()
            try {
                var previous = -1
                repeat(shape[1].toInt()) {
                    var best = Float.NEGATIVE_INFINITY
                    var index = 0
                    repeat(dictionary.size) { candidate ->
                        val score = data.get()
                        requireOcr(score.isFinite(), OcrFailure.OUTPUT_NONFINITE)
                        // Strict > makes ties choose the first index, including blank.
                        if (score > best) { best = score; index = candidate }
                    }
                    if (index != previous && index != 0) {
                        OcrPageContract.characterBudget(text.length, dictionary[index].length)
                        text.append(dictionary[index])
                    }
                    previous = index // Collapse BEFORE removing blank; A,blank,A becomes AA.
                }
                rows += text.toString()
            } finally {
                // Result Strings necessarily live until the caller drops them; mutable scratch does not.
                for (i in 0 until text.length) text.setCharAt(i, '\u0000')
                text.setLength(0)
            }
        }
        return Decoded(rows, shape.toList())
    }
}
