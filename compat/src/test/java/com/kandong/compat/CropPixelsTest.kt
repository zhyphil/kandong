package com.kandong.compat

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CropPixelsTest {
    @Test fun onlyChosenPixelsAreCopiedAndRowPaddingIsExcluded() {
        val input = ByteArray(40) { it.toByte() } // width4, row stride20, two rows
        val buffer = ByteBuffer.wrap(input).apply { position(7) }
        val crop = CropPixels.copy(buffer,4,2,20,4,1,0,2,2)
        assertArrayEquals((input.sliceArray(4..11)+input.sliceArray(24..31)),crop)
        assertEquals(7,buffer.position())
    }
    @Test fun croppedBottomRightDoesNotRequireTrailingRowPadding() {
        val input = ByteArray(36) { it.toByte() }
        assertArrayEquals(input.sliceArray(32..35),CropPixels.copy(ByteBuffer.wrap(input),4,2,20,4,3,1,1,1))
    }
    @Test fun staleGeometryCannotReadOutsideTheFrame() {
        for (crop in listOf(intArrayOf(-1,0,1,1),intArrayOf(3,0,2,1),intArrayOf(0,2,1,1),intArrayOf(0,0,0,1))) {
            assertThrows(IllegalArgumentException::class.java) { CropPixels.copy(ByteBuffer.allocate(40),4,2,20,4,crop[0],crop[1],crop[2],crop[3]) }
        }
    }
    @Test fun unsupportedPixelFormatAndIncompleteBufferFailBeforeCopy() {
        assertThrows(IllegalArgumentException::class.java) { CropPixels.copy(ByteBuffer.allocate(40),4,2,20,3,0,0,1,1) }
        assertThrows(IllegalArgumentException::class.java) { CropPixels.copy(ByteBuffer.allocate(25),4,2,20,4,0,1,2,1) }
    }
}
