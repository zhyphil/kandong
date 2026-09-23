package com.kandong.qualitylab

import kotlin.math.abs
import kotlin.math.floor

/** Coefficients only, computed once per shape; all per-pixel color processing remains on GPU. */
internal object GpuSamplingWeights {
    fun create(output: Int, scale: Int): FloatArray {
        require(output in 1..256_000 && scale in 1..5)
        return FloatArray(output * 4).also { weights ->
            for (i in 0 until output) {
                if (i % 4096 == 0) GpuLabChecks.cancellation()
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
