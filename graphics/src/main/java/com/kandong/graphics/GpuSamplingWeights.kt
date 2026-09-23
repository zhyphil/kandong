package com.kandong.graphics

import kotlin.math.abs
import kotlin.math.floor

/** Coefficients only, computed once per shape; all per-pixel color processing remains on GPU. */
internal object GpuSamplingWeights {
    /** RGBA32F rows: four weights, then (first source index, inside flag, 0, 0). */
    fun viewport(input: Int, output: Int, scale: Float, translation: Float): FloatArray {
        require(input in 1..GpuChecks.MAX_INPUT_PIXELS.toInt())
        require(output in 1..GpuChecks.MAX_OUTPUT_PIXELS.toInt())
        require(scale.isFinite() && scale in 1f..5f && translation.isFinite())
        return FloatArray(output * 8).also { table ->
            for (i in 0 until output) {
                if (i % 4096 == 0) GpuChecks.cancellation()
                val center = (i + 0.5f - translation) / scale
                // Skip before converting to Int: finite but huge pans must remain safe.
                if (center < 0f || center >= input.toFloat()) continue
                val source = center - 0.5f
                val first = floor(source).toInt() - 1
                table[output * 4 + i * 4] = first.toFloat()
                table[output * 4 + i * 4 + 1] = 1f
                if (scale == 1f) {
                    val fraction = source - floor(source)
                    table[i * 4 + 1] = 1f - fraction
                    table[i * 4 + 2] = fraction
                } else {
                    var sum = 0f
                    for (tap in 0..3) {
                        val x = abs(source - first - tap)
                        val value = when {
                            x < 1f -> ((7f * x - 12f) * x * x + 16f / 3f) / 6f
                            x < 2f -> (((-7f / 3f * x + 12f) * x - 20f) * x + 32f / 3f) / 6f
                            else -> 0f
                        }
                        table[i * 4 + tap] = value
                        sum += value
                    }
                    for (tap in 0..3) table[i * 4 + tap] /= sum
                }
            }
        }
    }

    fun create(output: Int, scale: Int): FloatArray {
        require(output in 1..256_000 && scale in 1..5)
        return FloatArray(output * 4).also { weights ->
            for (i in 0 until output) {
                if (i % 4096 == 0) GpuChecks.cancellation()
                val source = (i + 0.5f) / scale - 0.5f
                val first = floor(source).toInt() - 1
                var sum = 0f
                for (tap in 0..3) {
                    val x = abs(source - first - tap)
                    val value = when {
                        x < 1f -> ((7f * x - 12f) * x * x + 16f / 3f) / 6f
                        x < 2f -> (((-7f / 3f * x + 12f) * x - 20f) * x + 32f / 3f) / 6f
                        else -> 0f
                    }
                    weights[i * 4 + tap] = value
                    sum += value
                }
                for (tap in 0..3) weights[i * 4 + tap] /= sum
            }
        }
    }
}
