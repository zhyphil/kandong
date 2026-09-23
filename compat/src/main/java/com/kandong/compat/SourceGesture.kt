package com.kandong.compat

import kotlin.math.roundToInt

internal enum class GestureMode { MOVE, RESIZE }
internal data class GestureResult(val source: Box, val grip: Box, val snapX: Int = 0, val snapY: Int = 0)

/** A DOWN snapshot is immutable until UP/CANCEL, even when the display panel swaps. */
internal class SourceGesture(
    val mode: GestureMode, val pointerId: Int, val side: GripSide,
    val start: Box, val startGrip: Box, private val downX: Float, private val downY: Float,
    private val layout: MagnifierLayout
) {
    var result = GestureResult(start, startGrip); private set
    private var cancelled = false
    fun cancel() { cancelled = true; result = result.copy(snapX = 0, snapY = 0) }
    fun update(pointer: Int, count: Int, rawX: Float, rawY: Float): GestureResult {
        if (cancelled) return result
        if (pointer != pointerId || count != 1 || !rawX.isFinite() || !rawY.isFinite()) { cancel(); return result }
        val dx = (rawX - downX).roundToInt(); val dy = (rawY - downY).roundToInt()
        result = if (mode == GestureMode.MOVE) {
            val x = dx.coerceIn(-minOf(start.left, startGrip.left), layout.width - maxOf(start.right, startGrip.right))
            val y = dy.coerceIn(-minOf(start.top, startGrip.top), layout.height - maxOf(start.bottom, startGrip.bottom))
            fun snap(delta: Int) = when { delta > layout.snapThreshold -> 1; delta < -layout.snapThreshold -> -1; else -> 0 }
            GestureResult(start.moved(x, y), startGrip.moved(x, y), snap(dx - x), snap(dy - y))
        } else resize(dx, dy)
        return result
    }
    private fun resize(dx: Int, dy: Int): GestureResult {
        val cornerX = if (side.right) start.right else start.left
        val cornerY = if (side.below) start.bottom else start.top
        val anchorX = if (side.right) start.left else start.right
        val anchorY = if (side.below) start.top else start.bottom
        val offsetX = startGrip.left - cornerX; val offsetY = startGrip.top - cornerY
        fun range(anchor: Int, positive: Boolean, minimum: Int, maximum: Int,
                  screen: Int, offset: Int, control: Int): IntRange {
            val lowCorner = maxOf(0, -offset)
            val highCorner = minOf(screen, screen - offset - control)
            return if (positive) maxOf(minimum, lowCorner - anchor)..minOf(maximum, highCorner - anchor)
                else maxOf(minimum, anchor - highCorner)..minOf(maximum, anchor - lowCorner)
        }
        val widths = range(anchorX, side.right, layout.minWidth, layout.imageWidth,
            layout.width, offsetX, startGrip.width)
        val heights = range(anchorY, side.below, layout.minHeight, layout.maxHeight,
            layout.height, offsetY, startGrip.height)
        val ratio = layout.imageWidth.toFloat() / layout.imageHeight
        val targetWidth = start.width + if (side.right) dx else -dx
        val targetHeight = start.height + if (side.below) dy else -dy
        // Project the finger's position onto the proportional resize diagonal. Either axis
        // changes both dimensions, so shrinking a corner always increases magnification.
        val desiredWidth = (ratio * targetWidth + targetHeight) / (ratio * ratio + 1f) * ratio
        val size = proportionalSize(layout.imageWidth, layout.imageHeight, desiredWidth, widths, heights)
            ?: return result
        val x = anchorX + if (side.right) size.first else -size.first
        val y = anchorY + if (side.below) size.second else -size.second
        val crop = Box(minOf(x, anchorX), minOf(y, anchorY), kotlin.math.abs(x - anchorX), kotlin.math.abs(y - anchorY))
        return GestureResult(crop, startGrip.moved(x - cornerX, y - cornerY))
    }
    fun finish(up: Boolean): Box {
        if (!up) cancel()
        val r = result
        if (!up || cancelled || mode != GestureMode.MOVE) return r.source
        val x = when (r.snapX) { -1 -> 0; 1 -> layout.width - r.source.width; else -> r.source.left }
        val y = when (r.snapY) { -1 -> 0; 1 -> layout.height - r.source.height; else -> r.source.top }
        return r.source.copy(left = x, top = y)
    }
}
