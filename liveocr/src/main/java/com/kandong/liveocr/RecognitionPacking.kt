package com.kandong.liveocr

import kotlin.math.ceil

/** Pure packing boundary only. It does not resize, detect, crop, read a screen or run a model. */
internal object RecognitionPacking {
    const val HEIGHT = 48
    const val MAX_BATCH = 4
    const val MAX_WIDTH = 2048
    data class Size(val width: Int, val height: Int)
    data class Resized(val width: Int, val height: Int, val argb: IntArray)
    data class Plan(val width: Int, val resizedWidths: List<Int>, val tensorRowToInput: List<Int>)

    fun plan(sizes: List<Size>): Plan {
        require(sizes.size in 1..MAX_BATCH)
        require(sizes.all { it.width in 1..4096 && it.height in 1..2048 })
        val ratios = sizes.map { it.width.toDouble() / it.height }
        val width = (HEIGHT * maxOf(320.0 / HEIGHT, ratios.max())).toInt()
        require(width in 320..MAX_WIDTH)
        // Explicit stable tie behavior; do not rely on an upstream sort implementation.
        val order = sizes.indices.sortedWith(compareBy<Int> { ratios[it] }.thenBy { it })
        return Plan(width, ratios.map { minOf(width, ceil(HEIGHT * it).toInt()) }, order)
    }

    /** Resized rows remain in original crop order. Returned tensor is NCHW with B,G,R planes. */
    fun pack(sizes: List<Size>, rows: List<Resized>): FloatArray {
        val plan = plan(sizes)
        require(rows.size == sizes.size)
        rows.forEachIndexed { index, row ->
            require(row.width == plan.resizedWidths[index] && row.height == HEIGHT)
            require(row.argb.size == row.width * HEIGHT)
            require(row.argb.all { (it ushr 24) == 255 }) { "Opaque precomposited input required" }
        }
        val plane = HEIGHT * plan.width
        val result = FloatArray(rows.size * 3 * plane) // Padding remains positive float zero.
        plan.tensorRowToInput.forEachIndexed { batch, inputIndex ->
            val row = rows[inputIndex]
            for (y in 0 until HEIGHT) for (x in 0 until row.width) {
                val pixel = row.argb[y * row.width + x]
                for (channel in 0..2) {
                    val value = (pixel ushr (channel * 8)) and 255 // B, G, R from ARGB.
                    // Keep float32 operation order; algebraic simplification changes rounding.
                    result[batch * 3 * plane + channel * plane + y * plan.width + x] =
                        (value.toFloat() / 255f - 0.5f) / 0.5f
                }
            }
        }
        return result
    }
}
