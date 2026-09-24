package com.kandong.graphics

import java.nio.ByteBuffer

/** Numeric allocation diagnostics only; never contains image content. Counts survive clear(). */
data class GpuScratchStats(val uploadCapacityBytes: Int, val readbackCapacityBytes: Int,
    val argbCapacityPixels: Int, val uploadAllocations: Int, val readbackAllocations: Int,
    val argbAllocations: Int)

/** Private-to-renderer, owner-thread scratch. None of these mutable arrays escape in an Input. */
internal class GpuScratch {
    private var upload: ByteBuffer? = null
    private var readback: ByteBuffer? = null
    private var argb: IntArray? = null
    private var uploadAllocations = 0
    private var readbackAllocations = 0
    private var argbAllocations = 0

    val stats get() = GpuScratchStats(upload?.capacity() ?: 0, readback?.capacity() ?: 0,
        argb?.size ?: 0, uploadAllocations, readbackAllocations, argbAllocations)

    fun copyRgba(bytes: ByteArray, width: Int, height: Int, output: GpuChecks.Shape,
        maxTexture: Int, maxViewport: IntArray): ByteBuffer {
        val count = GpuChecks.input(width, height)
        require(bytes.size.toLong() == count.toLong() * 4L) { "RGBA byte count mismatch" }
        GpuChecks.output(output.width, output.height)
        GpuChecks.deviceLimits(width, height, output, maxTexture, maxViewport)
        GpuChecks.cancellation()
        if ((upload?.capacity() ?: 0) < bytes.size) {
            upload = ByteBuffer.allocateDirect(grownCapacity(upload?.capacity() ?: 0,
                bytes.size, (GpuChecks.MAX_INPUT_PIXELS * 4L).toInt()))
            uploadAllocations++
        }
        // Always overwrite, including when the same caller-owned byte array was mutated.
        return checkNotNull(upload).apply { clear(); put(bytes); flip(); GpuChecks.cancellation() }
    }

    fun readback(count: Int): ByteBuffer {
        require(count > 0 && count.toLong() <= GpuChecks.MAX_OUTPUT_PIXELS) { "Output pixel limit" }
        GpuChecks.cancellation()
        val bytes = count * 4
        if ((readback?.capacity() ?: 0) < bytes) {
            readback = ByteBuffer.allocateDirect(grownCapacity(readback?.capacity() ?: 0,
                bytes, (GpuChecks.MAX_OUTPUT_PIXELS * 4L).toInt()))
            readbackAllocations++
        }
        return checkNotNull(readback).apply { clear(); limit(bytes) }
    }

    fun toArgb(buffer: ByteBuffer, count: Int): IntArray {
        require(count > 0 && count.toLong() <= GpuChecks.MAX_OUTPUT_PIXELS) { "Output pixel limit" }
        require(buffer.remaining().toLong() == count.toLong() * 4L) { "Readback byte count mismatch" }
        GpuChecks.cancellation()
        if ((argb?.size ?: 0) < count) {
            argb = IntArray(grownCapacity(argb?.size ?: 0, count, GpuChecks.MAX_OUTPUT_PIXELS.toInt()))
            argbAllocations++
        }
        val pixels = checkNotNull(argb)
        // Only convert the valid region; capacity may belong to a previous, larger viewport.
        for (index in 0 until count) {
            if (index % 4096 == 0) GpuChecks.cancellation()
            val r = buffer.get().toInt() and 255
            val g = buffer.get().toInt() and 255
            val b = buffer.get().toInt() and 255
            val a = buffer.get().toInt() and 255
            pixels[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pixels
    }

    fun clear() { upload = null; readback = null; argb = null }

    internal companion object {
        fun grownCapacity(current: Int, required: Int, maximum: Int): Int {
            require(current in 0..maximum && required in 1..maximum) { "Scratch capacity limit" }
            if (required <= current) return current
            return maxOf(required.toLong(), current.toLong() * 2L).coerceAtMost(maximum.toLong()).toInt()
        }
    }
}
