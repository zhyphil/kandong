package com.kandong.qualitylab

import android.graphics.Bitmap
import com.kandong.graphics.GpuC
import com.kandong.graphics.GpuScratchStats
import com.kandong.graphics.GpuViewport
import org.json.JSONArray
import org.json.JSONObject

/** Only generated pixels; separate from the independent-reference cases and timed benchmarks. */
internal object ViewportReuseChecks {
    fun run(): JSONObject {
        val cases = JSONArray()
        var previous: Bitmap? = null
        var previousPixels: IntArray? = null
        var bytes = ByteArray(0)
        var allPassed = true
        val gpu = GpuC()
        try {
            // Repeat, swap equal-area axes, grow both buffers, then shrink each independently.
            val shapes = listOf(
                intArrayOf(3, 2, 7, 5), intArrayOf(3, 2, 7, 5), intArrayOf(2, 3, 5, 7),
                intArrayOf(31, 17, 67, 41), intArrayOf(31, 17, 67, 41), intArrayOf(17, 31, 41, 67),
                intArrayOf(5, 1, 67, 41), intArrayOf(31, 17, 3, 13),
                intArrayOf(1, 5, 3, 13), intArrayOf(1, 1, 1, 1), intArrayOf(1, 1, 1, 1))
            for ((index, shape) in shapes.withIndex()) {
                GpuLabChecks.cancellation()
                val (w, h, ow, oh) = shape
                val sameArray = bytes.size == w * h * 4
                if (!sameArray) bytes = ByteArray(w * h * 4)
                for (i in bytes.indices) bytes[i] = if (i % 4 == 3) (-1).toByte()
                    else ((i * 37 + index * 61 + i / 4 * 13) % 256).toByte()
                val scale = listOf(1f, 1.37f, 2.64f, 5f)[index % 4]
                val viewport = GpuViewport(ow, oh, scale, if (index % 2 == 0) 0f else -0.25f, 0f)
                val immutable = gpu.renderViewport(GpuC.Input.fromRgba(bytes, w, h), viewport).bitmap
                val expected = try { pixels(immutable) } finally { immutable.recycle() }
                val live = gpu.renderViewportRgba(bytes, w, h, viewport).bitmap
                try {
                    val actual = pixels(live)
                    val previousUnchanged = previous?.let { it !== live && pixels(it).contentEquals(previousPixels!!) } ?: true
                    val exact = expected.contentEquals(actual)
                    allPassed = allPassed && exact && previousUnchanged
                    cases.put(JSONObject().put("source", "${w}x$h").put("viewport", "${ow}x$oh")
                        .put("scale", scale).put("sameArrayUpdated", sameArray).put("exactInputMatch", exact)
                        .put("mismatchedPixels", expected.indices.count { expected[it] != actual[it] })
                        .put("previousBitmapUnchanged", previousUnchanged).put("scratch", stats(gpu.scratchStats)))
                    previous?.recycle()
                    previous = live
                    previousPixels = actual
                } finally {
                    if (previous !== live) live.recycle()
                }
            }
            // Fixed dimensions after warmup: no upload/readback/ARGB expansion may occur.
            val stableBefore = gpu.scratchStats
            repeat(8) { index ->
                bytes[0] = (index * 29).toByte()
                val viewport = GpuViewport(1, 1, 1f, 0f, 0f)
                val immutable = gpu.renderViewport(GpuC.Input.fromRgba(bytes, 1, 1), viewport).bitmap
                val expected = try { pixels(immutable) } finally { immutable.recycle() }
                val live = gpu.renderViewportRgba(bytes, 1, 1, viewport).bitmap
                try {
                    val exact = expected.contentEquals(pixels(live))
                    val previousUnchanged = pixels(checkNotNull(previous)).contentEquals(previousPixels!!)
                    allPassed = allPassed && exact && previousUnchanged
                    cases.put(JSONObject().put("stableIteration", index).put("sameArrayUpdated", true)
                        .put("exactInputMatch", exact).put("previousBitmapUnchanged", previousUnchanged))
                } finally { live.recycle() }
            }
            val stableAfter = gpu.scratchStats
            val stable = stableBefore == stableAfter
            gpu.close()
            val closed = gpu.scratchStats
            val released = closed.uploadCapacityBytes == 0 && closed.readbackCapacityBytes == 0 && closed.argbCapacityPixels == 0
            return JSONObject().put("passed", allPassed && stable && released).put("cases", cases)
                .put("stableScratch", stable).put("beforeStable", stats(stableBefore)).put("afterStable", stats(stableAfter))
                .put("releasedOnClose", released).put("afterClose", stats(closed))
        } finally {
            previous?.recycle()
            gpu.close()
        }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun stats(value: GpuScratchStats) = JSONObject()
        .put("uploadCapacityBytes", value.uploadCapacityBytes).put("readbackCapacityBytes", value.readbackCapacityBytes)
        .put("argbCapacityPixels", value.argbCapacityPixels).put("uploadAllocations", value.uploadAllocations)
        .put("readbackAllocations", value.readbackAllocations).put("argbAllocations", value.argbAllocations)
}
