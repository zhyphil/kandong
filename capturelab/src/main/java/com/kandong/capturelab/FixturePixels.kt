package com.kandong.capturelab

import java.nio.ByteBuffer
import kotlin.math.abs

internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun shift(x: Int, y: Int) = Box(left + x, top + y, right + x, bottom + y)
    fun inside(width: Int, height: Int) = left >= 0 && top >= 0 && right <= width && bottom <= height && this.width >= 8 && this.height >= 8
}

internal data class FixtureGeometry(val first: Box, val second: Box, val patch: Box, val witness: Box) {
    fun shift(x: Int, y: Int) = FixtureGeometry(first.shift(x, y), second.shift(x, y), patch.shift(x, y), witness.shift(x, y))
    fun inside(width: Int, height: Int) = listOf(first, second, patch, witness).all { it.inside(width, height) }
    companion object {
        fun local(width: Int, height: Int): FixtureGeometry {
            fun box(l: Int, t: Int, r: Int, b: Int) = Box(width * l / 100, height * t / 100, width * r / 100, height * b / 100)
            return FixtureGeometry(box(8, 12, 31, 32), box(38, 12, 61, 32), box(8, 52, 55, 85), box(68, 49, 94, 86))
        }
        fun witnessCells(width: Int, height: Int) = Pair(
            Box(width / 12, height / 6, width * 5 / 12, height * 4 / 6),
            Box(width * 7 / 12, height / 6, width * 11 / 12, height * 4 / 6))
    }
}

internal data class Marker(val first: Int, val second: Int) {
    companion object {
        const val BASE = 0xff297cc9.toInt()
        const val COVER = 0xffe45235.toInt()
        fun forPhase(sessionSalt: Int, phase: Phase): Marker {
            // The phase component never repeats within a session, including hide and clear-secure.
            val red = 36 + phase.code * 25
            val green = 40 + (sessionSalt and 127)
            val blue = 50 + ((sessionSalt ushr 7) and 127)
            val first = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            val second = (0xff shl 24) or ((255 - red) shl 16) or ((255 - green) shl 8) or (255 - blue)
            return Marker(first, second)
        }
    }
}

/** RGBA_8888 plane geometry is checked before any absolute buffer read. */
internal class RgbaSampler(
    private val buffer: ByteBuffer, private val width: Int, private val height: Int,
    private val rowStride: Int, private val pixelStride: Int
) {
    private val base = buffer.position()
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216)
        require(pixelStride >= 4 && rowStride.toLong() >= (width - 1L) * pixelStride + 4)
        require(base.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride + 4 <= buffer.limit())
    }
    private fun rgb(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        val index = (base.toLong() + y.toLong() * rowStride + x.toLong() * pixelStride).toInt()
        return ((buffer.get(index).toInt() and 255) shl 16) or
            ((buffer.get(index + 1).toInt() and 255) shl 8) or (buffer.get(index + 2).toInt() and 255)
    }
    fun matches(box: Box, expected: Int, tolerance: Int = 12): Boolean {
        require(box.inside(width, height))
        return points(box).all { (x, y) ->
            val actual = rgb(x, y)
            listOf(0, 8, 16).all { shift -> abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= tolerance }
        }
    }
    fun black(box: Box): Boolean = matches(box, 0, 12)
    fun patch(box: Box): Patch = when {
        matches(box, Marker.BASE) -> Patch.BASE
        matches(box, Marker.COVER) -> Patch.COVER
        black(box) -> Patch.BLACK
        else -> Patch.OTHER
    }
    private fun points(box: Box): List<Pair<Int, Int>> {
        val x = (box.left + box.right) / 2; val y = (box.top + box.bottom) / 2
        val dx = box.width / 4; val dy = box.height / 4
        return listOf(x to y, x - dx to y - dy, x + dx to y - dy, x - dx to y + dy, x + dx to y + dy)
    }
}
