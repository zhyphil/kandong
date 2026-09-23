package com.kandong.compat

import kotlin.math.min
import kotlin.math.roundToInt

/** All values are capture-screen pixels; no Android state or view-local coordinates. */
internal data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right get() = left + width
    val bottom get() = top + height
    val centerY get() = top + height / 2f
    fun moved(dx: Int, dy: Int) = copy(left = left + dx, top = top + dy)
    fun expanded(n: Int) = Box(left - n, top - n, width + 2 * n, height + 2 * n)
    fun intersects(b: Box) = left < b.right && right > b.left && top < b.bottom && bottom > b.top
    fun inside(w: Int, h: Int) = width > 0 && height > 0 && left >= 0 && top >= 0 && right <= w && bottom <= h
}
internal data class GripSide(val below: Boolean, val right: Boolean)
internal data class Controls(val side: GripSide, val move: Box, val resize: Box)
internal data class MagnifierLayout(
    val width: Int, val height: Int, val imageWidth: Int, val imageHeight: Int,
    val topPanel: Box, val bottomPanel: Box, val grip: Int, val moveWidth: Int,
    val gap: Int, val hysteresis: Int, val snapThreshold: Int,
    val minWidth: Int, val minHeight: Int, val maxHeight: Int
) {
    fun panel(bottom: Boolean) = if (bottom) bottomPanel else topPanel
    fun fits(bottom: Boolean, source: Box, controls: List<Box>) =
        (listOf(source) + controls).none { it.expanded(gap).intersects(panel(bottom)) }
    fun selectPanel(currentBottom: Boolean, source: Box, controls: List<Box>): Boolean? {
        val currentFits = fits(currentBottom, source, controls)
        val otherFits = fits(!currentBottom, source, controls)
        if (!currentFits) return if (otherFits) !currentBottom else null
        val preferred = when {
            source.centerY < height / 2f - hysteresis -> true
            source.centerY > height / 2f + hysteresis -> false
            else -> currentBottom
        }
        return if (preferred != currentBottom && otherFits) preferred else currentBottom
    }
    fun idleControls(source: Box, currentBottom: Boolean): Controls? {
        val candidates = listOf(currentBottom, !currentBottom).flatMap { below ->
            listOf(true, false).mapNotNull { right ->
                val y = if (below) source.bottom + gap else source.top - gap - grip
                if (y < 0 || y + grip > height) null else {
                    val total = moveWidth + gap + grip
                    val desired = if (right) source.right - total else source.left
                    val x = desired.coerceIn(0, width - total)
                    val resizeX = if (right) x + moveWidth + gap else x
                    val moveX = if (right) x else x + grip + gap
                    Controls(GripSide(below, right), Box(moveX, y, moveWidth, grip), Box(resizeX, y, grip, grip))
                }
            }
        }
        // Prefer the corner that needs no horizontal displacement, especially for narrow edge crops.
        val nearest = candidates.sortedBy {
            kotlin.math.abs(it.resize.left - if (it.side.right) source.right - grip else source.left)
        }
        return nearest.firstOrNull { fits(currentBottom, source, listOf(it.move, it.resize)) }
            ?: nearest.firstOrNull { fits(!currentBottom, source, listOf(it.move, it.resize)) }
    }
    companion object {
        fun create(w: Int, h: Int, density: Float, safeTop: Int = 0, safeBottom: Int = 0): MagnifierLayout? {
            if (w <= 0 || h <= 0 || !density.isFinite() || density <= 0f) return null
            fun dp(n: Int) = (n * density).roundToInt().coerceAtLeast(1)
            val marginTop = maxOf(dp(32), safeTop); val marginBottom = maxOf(dp(32), safeBottom)
            val chrome = dp(48) + 2 * dp(2); val grip = dp(48); val gap = dp(6); val hysteresis = dp(24)
            val imageWidth = w - dp(24)
            val imageHeight = minOf(dp(144), (h - marginTop - marginBottom - 2 * chrome - grip - 3 * gap - 2 * hysteresis) / 3)
            val panelHeight = imageHeight + chrome
            val freeGap = h - marginTop - marginBottom - 2 * panelHeight
            val maxHeight = minOf(imageHeight, freeGap - grip - 3 * gap - 2 * hysteresis)
            if (w < dp(80) + grip + gap || imageWidth < dp(48) || maxHeight < dp(32)) return null
            val panelWidth = imageWidth + 2 * dp(2)
            val x = (w - panelWidth) / 2
            return MagnifierLayout(w, h, imageWidth, imageHeight,
                Box(x, marginTop, panelWidth, panelHeight), Box(x, h - marginBottom - panelHeight, panelWidth, panelHeight),
                grip, dp(80), gap, hysteresis, dp(8), dp(48), dp(32), maxHeight)
        }
    }
}
internal fun actualScale(viewportWidth: Int, viewportHeight: Int, crop: Box): Float =
    if (viewportWidth <= 0 || viewportHeight <= 0 || crop.width <= 0 || crop.height <= 0) 0f
    else min(viewportWidth.toFloat() / crop.width, viewportHeight.toFloat() / crop.height)

/** Fit a source rectangle to the display aspect ratio, within screen and grip limits. */
internal fun proportionalSize(viewportWidth: Int, viewportHeight: Int, requestedWidth: Float,
    widthRange: IntRange, heightRange: IntRange): Pair<Int, Int>? {
    val ratio = viewportWidth.toDouble() / viewportHeight
    val low = maxOf(widthRange.first, kotlin.math.ceil(heightRange.first * ratio).toInt())
    val high = minOf(widthRange.last, kotlin.math.floor(heightRange.last * ratio).toInt())
    if (low > high) return null
    val width = requestedWidth.roundToInt().coerceIn(low, high)
    return width to (width / ratio).roundToInt().coerceIn(heightRange)
}
