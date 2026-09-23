package com.kandong.app.domain

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val valid: Boolean get() = width > 0 && height > 0
    fun intersects(other: Box): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun clip(other: Box): Box? = Box(
        maxOf(left, other.left), maxOf(top, other.top),
        minOf(right, other.right), minOf(bottom, other.bottom),
    ).takeIf { it.valid }

    fun contains(other: Box): Boolean = other.valid && left <= other.left && top <= other.top &&
        right >= other.right && bottom >= other.bottom

    fun toOverlay(originX: Int, originY: Int, width: Int, height: Int): Box? =
        Box(left - originX, top - originY, right - originX, bottom - originY)
            .clip(Box(0, 0, width, height))
}

/** Android window bounds and density-derived insets can round in opposite directions by 1px. */
fun isOutsideContent(region: Box, content: Box): Boolean {
    val interior = Box(content.left + 1, content.top + 1, content.right - 1, content.bottom - 1)
    return interior.valid && !region.intersects(interior)
}
