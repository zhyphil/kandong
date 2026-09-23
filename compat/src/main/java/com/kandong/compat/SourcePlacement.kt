package com.kandong.compat

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
    fun idleControls(source: Box, @Suppress("UNUSED_PARAMETER") currentBottom: Boolean): Controls? {
        if (!source.inside(width, height)) return null
        // The crop itself is the move target. A transparent 48dp target straddles its upper-right edge.
        val resize = Box((source.right - grip / 2).coerceIn(0, width - grip),
            (source.top - grip / 2).coerceIn(0, height - grip), grip, grip)
        return Controls(GripSide(false, true), source, resize)
    }
    companion object {
        const val CONTROL_DP = 48
        const val BORDER_DP = 2
        const val CHROME_DP = 96
        fun create(w: Int, h: Int, density: Float, safeTop: Int = 0, safeBottom: Int = 0): MagnifierLayout? {
            if (w <= 0 || h <= 0 || !density.isFinite() || density <= 0f) return null
            fun dp(n: Int) = (n * density).roundToInt().coerceAtLeast(1)
            val marginTop = maxOf(dp(32), safeTop); val marginBottom = maxOf(dp(32), safeBottom)
            val chrome = 2 * dp(CONTROL_DP) + 2 * dp(BORDER_DP); val grip = dp(CONTROL_DP); val gap = dp(6); val hysteresis = dp(24)
            val imageWidth = w - dp(24)
            val imageHeight = minOf(dp(144), (h - marginTop - marginBottom - 2 * chrome - grip - 3 * gap - 2 * hysteresis) / 3)
            val panelHeight = imageHeight + chrome
            val freeGap = h - marginTop - marginBottom - 2 * panelHeight
            val maxHeight = minOf(imageHeight, freeGap - grip - 3 * gap - 2 * hysteresis)
            if (imageWidth < 3 * dp(CONTROL_DP) + dp(56) || imageWidth < dp(48) || maxHeight < dp(32)) return null
            val panelWidth = imageWidth + 2 * dp(2)
            val x = (w - panelWidth) / 2
            return MagnifierLayout(w, h, imageWidth, imageHeight,
                Box(x, marginTop, panelWidth, panelHeight), Box(x, h - marginBottom - panelHeight, panelWidth, panelHeight),
                grip, dp(CONTROL_DP), gap, hysteresis, dp(8), dp(48), dp(32), maxHeight)
        }
    }
}
