package com.kandong.capturelab

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FixturePixelsTest {
    private fun plane(width: Int, height: Int, row: Int, stride: Int, offset: Int = 0): ByteBuffer {
        val buffer = ByteBuffer.allocate(offset + (height - 1) * row + (width - 1) * stride + 4)
        for (y in 0 until height) for (x in 0 until width) {
            val i = offset + y * row + x * stride
            buffer.put(i, 0x29); buffer.put(i + 1, 0x7c); buffer.put(i + 2, 0xc9.toByte()); buffer.put(i + 3, 0xff.toByte())
        }
        buffer.position(offset)
        return buffer
    }

    @Test fun honorsRowPaddingPixelStrideAndBufferPositionWithoutReadingPadding() {
        val buffer = plane(12, 10, 112, 8, 13)
        val sampler = RgbaSampler(buffer, 12, 10, 112, 8)
        assertEquals(Patch.BASE, sampler.patch(Box(0, 0, 12, 10)))
        assertEquals(13, buffer.position())
    }

    @Test fun honorsLimitRatherThanCapacityAndAllowsMissingFinalRowPadding() {
        val correct = plane(12, 10, 64, 4)
        RgbaSampler(correct, 12, 10, 64, 4)
        correct.limit(correct.limit() - 1)
        rejects { RgbaSampler(correct, 12, 10, 64, 4) }
    }

    @Test fun refusesInvalidStrideDimensionsOverflowAndOutOfBoundsCoordinates() {
        val buffer = plane(12, 10, 64, 4)
        rejects { RgbaSampler(buffer, 12, 10, 40, 4) }
        rejects { RgbaSampler(buffer, 12, 10, 64, 3) }
        rejects { RgbaSampler(buffer, -1, 10, 64, 4) }
        rejects { RgbaSampler(buffer, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE) }
        val sampler = RgbaSampler(buffer, 12, 10, 64, 4)
        rejects { sampler.matches(Box(-1, 0, 11, 10), Marker.BASE) }
        rejects { sampler.matches(Box(0, 0, 12, 11), Marker.BASE) }
    }

    @Test fun checksMultipleInteriorSamplesRatherThanOnlyOnePixel() {
        val buffer = plane(12, 12, 48, 4)
        buffer.put(3 * 48 + 3 * 4, 0)
        assertEquals(Patch.OTHER, RgbaSampler(buffer, 12, 12, 48, 4).patch(Box(0, 0, 12, 12)))
    }

    @Test fun fixtureAndWitnessStayInsideMeasuredViewBounds() {
        val geometry = FixtureGeometry.local(480, 400).shift(12, 280)
        assertTrue(geometry.inside(504, 700))
        val cells = FixtureGeometry.witnessCells(geometry.witness.width, geometry.witness.height)
        assertTrue(cells.first.inside(geometry.witness.width, geometry.witness.height))
        assertTrue(cells.second.inside(geometry.witness.width, geometry.witness.height))
        assertTrue(geometry.patch.right < geometry.witness.left)
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid geometry must be refused") } catch (_: IllegalArgumentException) { }
    }
}
