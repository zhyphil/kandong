package com.kandong.graphics

import org.junit.Assert.*
import org.junit.Test

class GpuInputTest {
    @Test fun rgbaSnapshotIsDirectReadOnlyAndIndependentOfCallerMutation() {
        val bytes = byteArrayOf(1, 2, 3, -1, 5, 6, 7, -1)
        val input = GpuC.Input.fromRgba(bytes, 1, 2)
        bytes.fill(0)
        val actual = ByteArray(8)
        val buffer = input.uploadBytes()
        assertTrue(buffer.isDirect)
        assertTrue(buffer.isReadOnly)
        buffer.get(actual)
        assertArrayEquals(byteArrayOf(1, 2, 3, -1, 5, 6, 7, -1), actual)
        assertEquals(0, input.uploadBytes().position())
        assertEquals(2, input.count)
    }

    @Test fun invalidByteLengthsAndDimensionsFailBeforeAllocation() {
        for (length in listOf(0, 3, 5, 8)) {
            assertThrows(IllegalArgumentException::class.java) { GpuC.Input.fromRgba(ByteArray(length), 1, 1) }
        }
        for ((w, h) in listOf(0 to 1, -1 to 1, 1001 to 1000, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GpuC.Input.fromRgba(ByteArray(4), w, h) }
        }
    }

    @Test fun integerInputRemainsOpaqueAndRetainsArgbChannelOrder() {
        val input = GpuC.Input.pack(intArrayOf(0xff123456.toInt()), 1, 1)
        val bytes = ByteArray(4)
        input.uploadBytes().get(bytes)
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, -1), bytes)
        assertThrows(IllegalArgumentException::class.java) { GpuC.Input.pack(intArrayOf(0x7f123456), 1, 1) }
    }
}
