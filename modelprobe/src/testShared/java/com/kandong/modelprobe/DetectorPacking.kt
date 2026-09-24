package com.kandong.modelprobe

import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Test-only: consumes already resized opaque pixels. No Android resize, boxes or recognition. */
object DetectorPacking {
    const val MAX_SOURCE_DIMENSION = 4096
    const val MAX_RESIZED_DIMENSION = 2048
    const val MAX_PIXELS = 1_048_576
    data class Plan(val width: Int, val height: Int, val effectiveLimit: Int) {
        val inputShape get() = longArrayOf(1, 3, height.toLong(), width.toLong())
        val outputShape get() = longArrayOf(1, 1, height.toLong(), width.toLong())
    }

    fun plan(sourceWidth: Int, sourceHeight: Int): Plan {
        require(sourceWidth in 1..MAX_SOURCE_DIMENSION && sourceHeight in 1..MAX_SOURCE_DIMENSION)
        val longest = maxOf(sourceWidth, sourceHeight)
        val limit = when { longest < 960 -> 960; longest < 1500 -> 1500; else -> 2000 }
        val ratio = if (longest > limit) limit.toDouble() / longest else 1.0
        // Pinned upstream: truncate FIRST, then Python round (ties to even), independently per axis.
        fun axis(value: Int) = (Math.rint((value * ratio).toInt() / 32.0) * 32).toInt()
        val width = axis(sourceWidth); val height = axis(sourceHeight)
        pixels(width, height)
        return Plan(width, height, limit)
    }

    fun pixels(width: Int, height: Int): Int {
        require(width in 1..MAX_RESIZED_DIMENSION && height in 1..MAX_RESIZED_DIMENSION)
        require(width % 32 == 0 && height % 32 == 0)
        val count = width.toLong() * height
        require(count in 1..MAX_PIXELS.toLong()) { "Detector diagnostic pixel budget exceeded" }
        return count.toInt()
    }

    fun elements(shape: LongArray, channels: Int): Int {
        require(channels == 1 || channels == 3)
        require(shape.size == 4 && shape[0] == 1L && shape[1] == channels.toLong())
        require(shape[2] in 1..MAX_RESIZED_DIMENSION.toLong() && shape[3] in 1..MAX_RESIZED_DIMENSION.toLong())
        return pixels(shape[3].toInt(), shape[2].toInt()) * channels
    }

    fun pack(width: Int, height: Int, argb: IntArray): FloatArray {
        val plane = pixels(width, height)
        require(argb.size == plane)
        require(argb.all { it ushr 24 == 255 }) { "Opaque precomposited pixels required" }
        // All dimensions, products, length and alpha are checked before allocating the tensor.
        val result = FloatArray(plane * 3)
        val scale = (1.0 / 255.0).toFloat()
        for (i in argb.indices) for (channel in 0..2) {
            val value = (argb[i] ushr (channel * 8)) and 255 // BGR planes, row-major within each.
            val q: Float = value.toFloat() * scale
            result[channel * plane + i] = ((q.toDouble() - 0.5) / 0.5).toFloat()
        }
        return result
    }
}

/** Frozen numeric gate. It records failures; callers must persist the report before asserting. */
object DetectorComparison {
    const val ATOL = 1e-5
    const val RTOL = 1e-4
    const val MEAN_ABS_MAX = 1e-6
    const val THRESHOLD = 0.3f
    const val NEAR_BAND = 1e-4f
    data class Metrics(
        val count: Int, val bitDifferences: Int, val maxAbsolute: Double, val meanAbsolute: Double,
        val maxRelative: Double, val toleranceViolations: Int, val nonFinite: Int, val outOfRange: Int,
        val maskSha256: String, val positivePixels: Int, val maskFlips: Int,
        val nearThresholdActual: Int, val nearThresholdReference: Int
    ) {
        val passed get() = nonFinite == 0 && outOfRange == 0 && toleranceViolations == 0 &&
            meanAbsolute <= MEAN_ABS_MAX && maskFlips == 0
    }

    fun compare(actual: FloatBuffer, reference: FloatArray, referenceMask: ByteArray): Metrics {
        require(reference.isNotEmpty() && reference.size <= DetectorPacking.MAX_PIXELS)
        require(actual.remaining() == reference.size && referenceMask.size == reference.size)
        require(reference.all { it.isFinite() && it in 0f..1f })
        require(referenceMask.indices.all { referenceMask[it] == if (reference[it] > THRESHOLD) 1.toByte() else 0.toByte() })
        val values = actual.duplicate()
        var bits = 0; var violations = 0; var nonFinite = 0; var outOfRange = 0
        var positives = 0; var flips = 0; var nearActual = 0; var nearReference = 0
        var maximum = 0.0; var sum = 0.0; var relative = 0.0
        val maskDigest = MessageDigest.getInstance("SHA-256")
        for (i in reference.indices) {
            val a = values.get(); val r = reference[i]
            if (a.toRawBits() != r.toRawBits()) bits++
            if (!a.isFinite()) { nonFinite++; violations++ }
            else {
                if (a !in 0f..1f) outOfRange++
                val delta = abs(a.toDouble() - r.toDouble())
                maximum = maxOf(maximum, delta); sum += delta
                relative = maxOf(relative, if (r == 0f) { if (delta == 0.0) 0.0 else Double.POSITIVE_INFINITY } else delta / abs(r.toDouble()))
                if (delta > ATOL + RTOL * abs(r.toDouble())) violations++
            }
            val mask: Byte = if (a > THRESHOLD) 1 else 0
            maskDigest.update(mask)
            if (mask == 1.toByte()) positives++
            if (mask != referenceMask[i]) flips++
            // Float32 subtraction/band matches the host's np.float32 threshold diagnostic.
            if (abs(a - THRESHOLD) <= NEAR_BAND) nearActual++
            if (abs(r - THRESHOLD) <= NEAR_BAND) nearReference++
        }
        return Metrics(reference.size, bits, if (nonFinite == 0) maximum else Double.POSITIVE_INFINITY,
            if (nonFinite == 0) sum / reference.size else Double.POSITIVE_INFINITY,
            if (nonFinite == 0) relative else Double.POSITIVE_INFINITY, violations, nonFinite, outOfRange,
            maskDigest.digest().joinToString("") { "%02x".format(it) }, positives, flips, nearActual, nearReference)
    }
}
