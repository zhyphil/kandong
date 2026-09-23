package com.kandong.qualitylab

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ceil

/** Pure JVM contracts, shared by the GL implementation and its real-output evaluator. */
internal object GpuLabChecks {
    const val MAX_INPUT_PIXELS = 51_200L
    const val MAX_OUTPUT_PIXELS = 1_280_000L
    const val MAX_RGB_ERROR = 2
    const val MAX_MEAN_ERROR = 0.1

    data class Shape(val width: Int, val height: Int, val count: Int)

    fun shape(width: Int, height: Int, count: Int, scale: Int): Shape {
        require(width > 0 && height > 0 && scale in 1..5) { "Invalid dimensions/scale" }
        val inputCount = width.toLong() * height.toLong()
        require(inputCount <= MAX_INPUT_PIXELS && inputCount == count.toLong()) { "Input pixel limit/count" }
        val outputWidth = width.toLong() * scale.toLong()
        val outputHeight = height.toLong() * scale.toLong()
        val outputCount = outputWidth * outputHeight
        require(outputCount <= MAX_OUTPUT_PIXELS) { "Output pixel limit" }
        return Shape(outputWidth.toInt(), outputHeight.toInt(), outputCount.toInt())
    }

    fun deviceLimits(width: Int, height: Int, output: Shape, texture: Int, viewport: IntArray) {
        require(texture > 0 && viewport.size == 2 && viewport.all { it > 0 }) { "Invalid GL caps" }
        require(listOf(width, height, output.width, output.height).all { it <= texture }) { "GL_MAX_TEXTURE_SIZE exceeded" }
        require(width <= viewport[0] && output.width <= viewport[0] &&
            height <= viewport[1] && output.height <= viewport[1]) { "GL_MAX_VIEWPORT_DIMS exceeded" }
    }

    fun opaque(pixels: IntArray) {
        require(pixels.all { it ushr 24 == 255 }) { "Only opaque synthetic input is supported" }
    }

    data class Channel(val max: Int, val sum: Long, val count: Int) {
        val mean: Double get() = sum.toDouble() / count
        val passed: Boolean get() = max <= MAX_RGB_ERROR && mean <= MAX_MEAN_ERROR
    }

    data class Metrics(
        val channels: List<Channel>, val dimensionsPassed: Boolean,
        val alpha255: Boolean, val exact: Boolean, val exactRequired: Boolean
    ) {
        val passed: Boolean get() = dimensionsPassed && alpha255 && channels.all { it.passed } && (!exactRequired || exact)
    }

    /** Each fixture and each RGB channel has its own denominator; alpha never dilutes RGB. */
    fun compare(expected: IntArray, actual: IntArray, expectedWidth: Int, expectedHeight: Int,
        actualWidth: Int, actualHeight: Int, exactRequired: Boolean): Metrics {
        require(expected.isNotEmpty() && expectedWidth > 0 && expectedHeight > 0)
        require(expectedWidth.toLong() * expectedHeight == expected.size.toLong())
        val dimensions = expectedWidth == actualWidth && expectedHeight == actualHeight &&
            expected.size == actual.size && actualWidth.toLong() * actualHeight == actual.size.toLong()
        if (!dimensions) return Metrics(emptyList(), false, false, false, exactRequired)
        val maximum = IntArray(3)
        val sums = LongArray(3)
        var alpha = true
        var exact = true
        expected.indices.forEach { i ->
            if (i % 4096 == 0) cancellation()
            alpha = alpha && actual[i] ushr 24 == 255 && expected[i] ushr 24 == 255
            exact = exact && expected[i] == actual[i]
            for (channel in 0..2) {
                val shift = 16 - 8 * channel
                val difference = abs(((expected[i] ushr shift) and 255) - ((actual[i] ushr shift) and 255))
                maximum[channel] = maxOf(maximum[channel], difference)
                sums[channel] += difference
            }
        }
        return Metrics((0..2).map { Channel(maximum[it], sums[it], expected.size) }, dimensions, alpha, exact, exactRequired)
    }

    data class Stats(val samples: List<Double>, val median: Double, val p95: Double)
    fun stats(samples: List<Double>): Stats {
        require(samples.isNotEmpty() && samples.all { it.isFinite() && it >= 0.0 })
        val ordered = samples.sorted()
        val middle = ordered.size / 2
        val median = if (ordered.size % 2 == 1) ordered[middle] else (ordered[middle - 1] + ordered[middle]) / 2
        return Stats(samples.toList(), median, ordered[ceil(0.95 * ordered.size).toInt() - 1])
    }

    fun faster(cpu: Stats, completion: Stats, total: Stats): Boolean =
        completion.median < cpu.median && completion.p95 < cpu.p95 &&
            total.median < cpu.median && total.p95 < cpu.p95

    data class Marker(val name: String, val expected: Int, val actual: Int) {
        val passed: Boolean get() = actual ushr 24 == 255 && (0..2).all {
            val shift = 16 - it * 8
            abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= MAX_RGB_ERROR
        }
    }

    /** Use only the asymmetric fixture: four distinct, unequal-channel constant corner patches. */
    fun corners(source: IntArray, width: Int, height: Int, actual: IntArray,
        outputWidth: Int, outputHeight: Int): List<Marker> {
        require(width.toLong() * height == source.size.toLong() && width > 1 && height > 1)
        require(outputWidth.toLong() * outputHeight == actual.size.toLong() && outputWidth > 1 && outputHeight > 1)
        return listOf(
            Marker("top-left", source[0], actual[0]),
            Marker("top-right", source[width - 1], actual[outputWidth - 1]),
            Marker("bottom-left", source[(height - 1) * width], actual[(outputHeight - 1) * outputWidth]),
            Marker("bottom-right", source.last(), actual.last())
        )
    }

    fun cancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Lab task cancelled")
    }
}
