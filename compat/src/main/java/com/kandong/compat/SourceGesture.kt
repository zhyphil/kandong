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
            val x = dx.coerceIn(-start.left, layout.width - start.right)
            val y = dy.coerceIn(-start.top, layout.height - start.bottom)
            val source = start.moved(x, y)
            GestureResult(source, source)
        } else resize(dx, dy)
        return result
    }
    private fun resize(dx: Int, dy: Int): GestureResult {
        // Upper-right corner; the opposite bottom-left corner stays fixed.
        val width = (start.width + dx).coerceIn(layout.minWidth, minOf(layout.imageWidth, layout.width - start.left))
        val height = (start.height - dy).coerceIn(layout.minHeight, minOf(layout.maxHeight, start.bottom))
        val source = Box(start.left, start.bottom - height, width, height)
        return GestureResult(source, layout.idleControls(source, true)!!.resize)
    }
    fun finish(up: Boolean): Box {
        if (!up) cancel()
        return result.source
    }
}
