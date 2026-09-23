package com.kandong.graphics

import java.util.concurrent.CancellationException

/** Allocation and device caps only; the laboratory independently owns its accuracy gates. */
internal object GpuChecks {
    const val MAX_INPUT_PIXELS = 1_000_000L
    const val MAX_OUTPUT_PIXELS = 2_000_000L
    data class Shape(val width: Int, val height: Int, val count: Int)

    fun input(width: Int, height: Int): Int = bounded(width, height, MAX_INPUT_PIXELS)
    fun output(width: Int, height: Int): Shape =
        Shape(width, height, bounded(width, height, MAX_OUTPUT_PIXELS))

    private fun bounded(width: Int, height: Int, maximum: Long): Int {
        require(width > 0 && height > 0) { "Dimensions must be positive" }
        val count = width.toLong() * height.toLong()
        require(count <= maximum) { "Pixel limit exceeded" }
        return count.toInt()
    }

    // Preserve the original integer-scale API limits as well as its numerical path.
    fun integerShape(width: Int, height: Int, count: Int, scale: Int): Shape {
        require(scale in 1..5) { "Invalid integer scale" }
        val inputCount = bounded(width, height, 51_200L)
        require(inputCount == count) { "Input pixel count mismatch" }
        val outWidth = width.toLong() * scale
        val outHeight = height.toLong() * scale
        require(outWidth * outHeight <= 1_280_000L) { "Integer output pixel limit" }
        return Shape(outWidth.toInt(), outHeight.toInt(), (outWidth * outHeight).toInt())
    }

    fun deviceLimits(width: Int, height: Int, output: Shape, texture: Int, viewport: IntArray) {
        require(texture >= 2 && viewport.size == 2 && viewport.all { it > 0 }) { "Invalid GL caps" }
        require(listOf(width, height, output.width, output.height).all { it <= texture }) { "GL_MAX_TEXTURE_SIZE exceeded" }
        require(width <= viewport[0] && output.width <= viewport[0] &&
            height <= viewport[1] && output.height <= viewport[1]) { "GL_MAX_VIEWPORT_DIMS exceeded" }
    }

    fun opaque(pixels: IntArray) {
        require(pixels.all { it ushr 24 == 255 }) { "Only opaque input is supported" }
    }

    fun cancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("GPU rendering cancelled")
    }
}
