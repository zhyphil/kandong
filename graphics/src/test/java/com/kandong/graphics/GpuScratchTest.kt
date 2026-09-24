package com.kandong.graphics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class GpuScratchTest {
    private fun copy(scratch: GpuScratch, bytes: ByteArray, w: Int, h: Int,
        output: GpuChecks.Shape = GpuChecks.output(1, 1), texture: Int = 4096,
        viewport: IntArray = intArrayOf(4096, 4096)) =
        scratch.copyRgba(bytes, w, h, output, texture, viewport)

    @Test fun uploadReusesOwnedDirectBufferAndAlwaysCopiesCurrentContent() {
        val scratch = GpuScratch()
        val bytes = byteArrayOf(1, 2, 3, -1, 4, 5, 6, -1)
        val first = copy(scratch, bytes, 2, 1)
        assertTrue(first.isDirect)
        bytes.fill(17)
        assertEquals(1, first.get(0).toInt()) // Caller mutation alone cannot alter the owned copy.
        first.position(first.limit())
        val second = copy(scratch, bytes, 1, 2) // Same area, different axes.
        assertSame(first, second)
        assertEquals(0, second.position())
        assertEquals(8, second.limit())
        val actual = ByteArray(second.remaining())
        second.get(actual)
        assertArrayEquals(bytes, actual)
        repeat(20) { assertSame(first, copy(scratch, bytes, 2, 1)) }
        assertEquals(1, scratch.stats.uploadAllocations)
    }

    @Test fun uploadGrowthIsAmortizedAndShrinkLimitsExcludeOldBytes() {
        val scratch = GpuScratch()
        val initial = copy(scratch, ByteArray(16) { 1 }, 4, 1)
        val grown = copy(scratch, ByteArray(20) { 2 }, 5, 1)
        assertNotSame(initial, grown)
        assertEquals(32, grown.capacity())
        assertSame(grown, copy(scratch, ByteArray(24) { 3 }, 3, 2))
        val small = copy(scratch, byteArrayOf(9, 8, 7, -1), 1, 1)
        assertSame(grown, small)
        assertEquals(4, small.remaining())
        assertArrayEquals(byteArrayOf(9, 8, 7, -1), ByteArray(4).also { small.get(it) })
        assertFalse(small.hasRemaining())
        assertEquals(2, scratch.stats.uploadAllocations)
    }

    @Test fun invalidInputAndDeviceCapsFailBeforeScratchAllocation() {
        val scratch = GpuScratch()
        for ((w, h) in listOf(0 to 1, -1 to 1, 1001 to 1000, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { copy(scratch, ByteArray(4), w, h) }
        }
        for (size in listOf(0, 3, 5, 8)) {
            assertThrows(IllegalArgumentException::class.java) { copy(scratch, ByteArray(size), 1, 1) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            copy(scratch, ByteArray(12), 3, 1, texture = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            copy(scratch, ByteArray(4), 1, 1, GpuChecks.output(3, 1), texture = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            copy(scratch, ByteArray(12), 1, 3, viewport = intArrayOf(2, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            copy(scratch, ByteArray(4), 1, 1, GpuChecks.output(1, 3), viewport = intArrayOf(2, 2))
        }
        assertEquals(GpuScratchStats(0, 0, 0, 0, 0, 0), scratch.stats)
    }

    @Test fun readbackAndArgbReuseGrowthAndShrinkWithExplicitChannelOrder() {
        val scratch = GpuScratch()
        val initial = scratch.readback(2)
        initial.put(byteArrayOf(0x12, 0x34, 0x56, -1, -128, 0x7f, 0, 1)).flip()
        initial.order(ByteOrder.LITTLE_ENDIAN)
        val pixels = scratch.toArgb(initial, 2)
        assertArrayEquals(intArrayOf(0xff123456.toInt(), 0x01807f00), pixels)
        val grown = scratch.readback(3)
        assertNotSame(initial, grown)
        assertEquals(16, grown.capacity())
        grown.put(ByteArray(12) { -1 }).flip()
        val largerPixels = scratch.toArgb(grown, 3)
        assertNotSame(pixels, largerPixels)
        assertEquals(4, largerPixels.size)
        assertEquals(-1, largerPixels[2])
        val small = scratch.readback(1)
        assertSame(grown, small)
        assertEquals(4, small.limit())
        small.order(ByteOrder.BIG_ENDIAN)
        small.put(byteArrayOf(1, 2, 3, 4)).flip()
        assertSame(largerPixels, scratch.toArgb(small, 1))
        assertEquals(0x04010203, largerPixels[0])
        assertEquals(-1, largerPixels[1]) // Tail is unused, not converted or read past the valid limit.
        assertFalse(small.hasRemaining())
        repeat(20) {
            val buffer = scratch.readback(1).apply { put(byteArrayOf(5, 6, 7, 8)); flip() }
            assertSame(largerPixels, scratch.toArgb(buffer, 1))
        }
        assertEquals(0x08050607, largerPixels[0])
        assertEquals(2, scratch.stats.readbackAllocations)
        assertEquals(2, scratch.stats.argbAllocations)
    }

    @Test fun invalidOutputCountAndBufferLengthDoNotAllocate() {
        val scratch = GpuScratch()
        for (count in listOf(0, -1, 2_000_001, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { scratch.readback(count) }
            assertThrows(IllegalArgumentException::class.java) { scratch.toArgb(ByteBuffer.allocate(0), count) }
        }
        for (length in listOf(0, 3, 5, 8)) {
            assertThrows(IllegalArgumentException::class.java) { scratch.toArgb(ByteBuffer.allocate(length), 1) }
        }
        assertEquals(GpuScratchStats(0, 0, 0, 0, 0, 0), scratch.stats)
    }

    @Test fun capacitiesAreBoundedEvenNearIntegerOverflow() {
        assertEquals(4_000_000, GpuScratch.grownCapacity(3_000_000, 3_000_004, 4_000_000))
        assertEquals(8_000_000, GpuScratch.grownCapacity(5_000_000, 5_000_004, 8_000_000))
        assertEquals(Int.MAX_VALUE, GpuScratch.grownCapacity(Int.MAX_VALUE - 1, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(16, GpuScratch.grownCapacity(16, 4, 32))
        for (required in listOf(0, -1, 33, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GpuScratch.grownCapacity(16, required, 32) }
        }
    }

    @Test fun clearDropsAllReferencesAndNextUseAllocatesFreshScratch() {
        val scratch = GpuScratch()
        val upload = copy(scratch, ByteArray(4), 1, 1)
        val readback = scratch.readback(1)
        val pixels = scratch.toArgb(readback, 1)
        scratch.clear()
        scratch.clear()
        assertEquals(GpuScratchStats(0, 0, 0, 1, 1, 1), scratch.stats)
        assertNotSame(upload, copy(scratch, ByteArray(4), 1, 1))
        val next = scratch.readback(1)
        assertNotSame(readback, next)
        assertNotSame(pixels, scratch.toArgb(next, 1))
    }

    @Test fun cancellationPreventsAllocationAndClearStillWorks() {
        val scratch = GpuScratch()
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) { copy(scratch, ByteArray(4), 1, 1) }
            assertThrows(CancellationException::class.java) { scratch.readback(1) }
            assertThrows(CancellationException::class.java) { scratch.toArgb(ByteBuffer.allocate(4), 1) }
            scratch.clear()
            assertEquals(GpuScratchStats(0, 0, 0, 0, 0, 0), scratch.stats)
        } finally { Thread.interrupted() }
    }
}
