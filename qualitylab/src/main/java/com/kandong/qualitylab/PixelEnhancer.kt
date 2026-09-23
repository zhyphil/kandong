package com.kandong.qualitylab

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Bounded, opaque synthetic images only; intentionally independent of the capture service. */
internal object PixelEnhancer {
    private const val MAX_INPUT_PIXELS = 320 * 160
    private const val MAX_OUTPUT_PIXELS = MAX_INPUT_PIXELS * 25

    fun enlarge(pixels: IntArray, width: Int, height: Int, scale: Int, sharpen: Boolean = false): IntArray {
        require(width > 0 && height > 0 && scale in 1..5)
        val count = width.toLong() * height
        require(count <= MAX_INPUT_PIXELS && count == pixels.size.toLong())
        require(count * scale * scale <= MAX_OUTPUT_PIXELS)
        require(pixels.all { it ushr 24 == 255 }) { "Only opaque lab samples are supported" }
        if (scale == 1) return pixels.clone()
        val source = if (sharpen) sharpen(pixels, width, height) else pixels
        val outWidth = width * scale
        val outHeight = height * scale
        val xs = taps(width, outWidth, scale)
        val ys = taps(height, outHeight, scale)
        // Separable filtering: four horizontal taps, then four vertical taps.
        val rows = FloatArray(outWidth * height * 3)
        for (y in 0 until height) {
            checkCancellation()
            for (x in 0 until outWidth) for (channel in 0..2) {
                var value = 0f
                for (tap in 0..3) {
                    val pixel = source[y * width + xs.indices[x * 4 + tap]]
                    value += ((pixel ushr (16 - channel * 8)) and 255) * xs.weights[x * 4 + tap]
                }
                rows[(y * outWidth + x) * 3 + channel] = value
            }
        }
        val result = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            checkCancellation()
            for (x in 0 until outWidth) {
                var pixel = 0xff000000.toInt()
                for (channel in 0..2) {
                    var value = 0f
                    for (tap in 0..3) {
                        value += rows[(ys.indices[y * 4 + tap] * outWidth + x) * 3 + channel] *
                            ys.weights[y * 4 + tap]
                    }
                    pixel = pixel or (value.roundToInt().coerceIn(0, 255) shl (16 - channel * 8))
                }
                result[y * outWidth + x] = pixel
            }
        }
        return result
    }

    private data class Taps(val indices: IntArray, val weights: FloatArray)

    private fun taps(input: Int, output: Int, scale: Int): Taps {
        val indices = IntArray(output * 4)
        val weights = FloatArray(output * 4)
        for (i in 0 until output) {
            // Map pixel centers, matching Canvas's source/destination rectangle convention.
            val source = (i + 0.5f) / scale - 0.5f
            val first = floor(source).toInt() - 1
            var sum = 0f
            for (tap in 0..3) {
                indices[i * 4 + tap] = (first + tap).coerceIn(0, input - 1)
                weights[i * 4 + tap] = mitchell(source - first - tap)
                sum += weights[i * 4 + tap]
            }
            for (tap in 0..3) weights[i * 4 + tap] /= sum
        }
        return Taps(indices, weights)
    }

    // Mitchell & Netravali, SIGGRAPH 1988, Eq. (8); B = C = 1/3.
    // Independently implemented from the published formula; attribution in docs/QUALITY_LAB.md.
    private fun mitchell(distance: Float): Float {
        val x = abs(distance)
        return when {
            x < 1f -> ((7f * x - 12f) * x * x + 16f / 3f) / 6f
            x < 2f -> (((-7f / 3f * x + 12f) * x - 20f) * x + 32f / 3f) / 6f
            else -> 0f
        }
    }

    /** Small unsharp mask on source pixels, capped at 12/255 per channel to limit halos. */
    private fun sharpen(source: IntArray, width: Int, height: Int): IntArray {
        val result = IntArray(source.size)
        val kernel = intArrayOf(1, 2, 1)
        for (y in 0 until height) {
            checkCancellation()
            for (x in 0 until width) {
                var pixel = 0xff000000.toInt()
                for (channel in 0..2) {
                    val shift = 16 - channel * 8
                    var blur = 0f
                    for (dy in -1..1) for (dx in -1..1) {
                        val sample = source[(y + dy).coerceIn(0, height - 1) * width +
                            (x + dx).coerceIn(0, width - 1)]
                        blur += ((sample ushr shift) and 255) * kernel[dy + 1] * kernel[dx + 1]
                    }
                    val original = (source[y * width + x] ushr shift) and 255
                    val delta = ((original - blur / 16f) * 0.2f).coerceIn(-12f, 12f)
                    pixel = pixel or ((original + delta).roundToInt().coerceIn(0, 255) shl shift)
                }
                result[y * width + x] = pixel
            }
        }
        return result
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
    }
}
