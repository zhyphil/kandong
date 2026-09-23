package com.kandong.qualitylab

import org.junit.Assert.*
import org.junit.Test

class PixelEnhancerTest {
    @Test fun flatColorsStayFlatIncludingBorders() {
        for (color in intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff428096.toInt())) {
            for (scale in listOf(2, 3, 5)) for (sharp in listOf(false, true)) {
                val result = PixelEnhancer.enlarge(IntArray(6) { color }, 3, 2, scale, sharp)
                assertEquals(6 * scale * scale, result.size)
                assertTrue("Flat color changed", result.all { it == color })
            }
        }
    }

    @Test fun oneTimesPreservesPixelsWithoutAliasingInput() {
        val input = intArrayOf(0xff123456.toInt(), 0xffeeeeee.toInt())
        for (sharp in listOf(false, true)) {
            val output = PixelEnhancer.enlarge(input, 2, 1, 1, sharp)
            assertArrayEquals(input, output)
            assertNotSame(input, output)
        }
    }

    @Test fun singlePixelExpandsAtEveryScale() {
        for (scale in 1..5) {
            assertArrayEquals(IntArray(scale * scale) { 0xff102030.toInt() },
                PixelEnhancer.enlarge(intArrayOf(0xff102030.toInt()), 1, 1, scale))
        }
    }

    @Test fun scalingKeepsImageOrientationAndCenterAlignment() {
        val a = 0xff202020.toInt()
        val b = 0xffd0d0d0.toInt()
        val input = intArrayOf(a, b, b, a)
        val output = PixelEnhancer.enlarge(input, 2, 2, 3)
        assertTrue((output.first() and 255) < (output[5] and 255))
        for (i in output.indices) assertEquals(output[i], output[output.lastIndex - i])
    }

    @Test fun sharpeningIsBoundedAndDoesNotMutateInput() {
        val input = intArrayOf(64, 128, 192).map { 0xff000000.toInt() or (it * 0x010101) }.toIntArray()
        val saved = input.clone()
        val smooth = PixelEnhancer.enlarge(input, 3, 1, 3)
        val sharp = PixelEnhancer.enlarge(input, 3, 1, 3, true)
        assertArrayEquals(saved, input)
        assertFalse(smooth.contentEquals(sharp))
        for (i in sharp.indices) {
            assertEquals(255, sharp[i] ushr 24)
            assertEquals(sharp[i] and 255, (sharp[i] ushr 8) and 255)
            assertTrue(kotlin.math.abs((sharp[i] and 255) - (smooth[i] and 255)) <= 14)
        }
    }

    @Test fun interruptedWorkStopsBeforeRenderingMoreRows() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                PixelEnhancer.enlarge(IntArray(320 * 160) { -1 }, 320, 160, 5, true)
            }
        } finally {
            Thread.interrupted() // Do not leak interruption into the JUnit runner.
        }
    }

    @Test fun rejectsMalformedOrUnboundedRequestsBeforeAllocation() {
        for ((w, h, scale) in listOf(Triple(0, 1, 2), Triple(1, -1, 2),
            Triple(1, 1, 0), Triple(1, 1, 6), Triple(Int.MAX_VALUE, 2, 2), Triple(1, 2, 2))) {
            assertThrows(IllegalArgumentException::class.java) {
                PixelEnhancer.enlarge(intArrayOf(-1), w, h, scale)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PixelEnhancer.enlarge(IntArray(320 * 161) { -1 }, 320, 161, 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PixelEnhancer.enlarge(intArrayOf(0x00112233), 1, 1, 2)
        }
    }
}
