package com.kandong.ocrlab.multilingual

import org.junit.Assert.*
import org.junit.Test

class SmoothPixelsTest {
    private fun gray(value: Int) = (255 shl 24) or (value shl 16) or (value shl 8) or value
    @Test fun twoTimesInterpolatesCentersWithClampedEdges() {
        val expectedRow = intArrayOf(gray(0), gray(64), gray(191), gray(255))
        assertArrayEquals(expectedRow + expectedRow,
            SmoothPixels.resize(intArrayOf(gray(0), gray(255)), 2, 1, 2))
    }
    @Test fun threeTimesUsesExactThirdsAndPreservesEdges() {
        val row = intArrayOf(gray(0), gray(0), gray(85), gray(170), gray(255), gray(255))
        assertArrayEquals(row + row + row,
            SmoothPixels.resize(intArrayOf(gray(0), gray(255)), 2, 1, 3))
    }
    @Test fun verticalInterpolationAndSinglePixelStayOpaque() {
        assertArrayEquals(intArrayOf(gray(0), gray(0), gray(64), gray(64), gray(191), gray(191), gray(255), gray(255)),
            SmoothPixels.resize(intArrayOf(gray(0), gray(255)), 1, 2, 2))
        assertArrayEquals(IntArray(9) { 0xff123456.toInt() },
            SmoothPixels.resize(intArrayOf(0xff123456.toInt()), 1, 1, 3))
    }
    @Test fun channelsAreInterpolatedIndependentlyAndInputIsUnchanged() {
        val pixels=intArrayOf(0xffff0000.toInt(), 0xff0000ff.toInt())
        val before=pixels.copyOf()
        val row=intArrayOf(0xffff0000.toInt(), 0xffbf0040.toInt(), 0xff4000bf.toInt(), 0xff0000ff.toInt())
        assertArrayEquals(row+row, SmoothPixels.resize(pixels,2,1,2))
        assertArrayEquals(before,pixels)
    }
    @Test fun twoDimensionalWeightsRoundOnlyAfterBothAxes() {
        val result = SmoothPixels.resize(intArrayOf(gray(0), gray(0), gray(0), gray(255)), 2, 2, 2)
        assertEquals(gray(16), result[5])
        assertEquals(gray(48), result[6])
        assertEquals(gray(48), result[9])
        assertEquals(gray(143), result[10])
    }
    @Test fun invalidOrOversizedInputIsRejected() {
        rejects { SmoothPixels.resize(intArrayOf(-1),1,1,1) }
        rejects { SmoothPixels.resize(intArrayOf(-1),1,1,4) }
        rejects { SmoothPixels.resize(intArrayOf(-1),2,1,2) }
        rejects { SmoothPixels.resize(intArrayOf(0),1,1,2) }
        rejects { SmoothPixels.resize(IntArray(684) {-1},684,1,3) }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid input rejection") } catch (_: IllegalArgumentException) { }
    }
}
