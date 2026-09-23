package com.kandong.compat

/** Screen-pixel geometry, independent of Android so reachability can be regression-tested. */
internal data class SourcePlacement(
    val left: Int, val top: Int, val width: Int, val height: Int,
    val handleLeft: Int, val handleTop: Int
) {
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}

internal fun placeSource(
    screenWidth: Int, screenHeight: Int, width: Int, height: Int,
    requestedX: Float, requestedY: Float,
    handleWidth: Int, handleHeight: Int, gap: Int, handleBelow: Boolean
): SourcePlacement {
    // A control's height must not reserve otherwise readable screen content.
    val left = (requestedX - width / 2f).toInt().coerceIn(0, screenWidth - width)
    val top = (requestedY - height / 2f).toInt().coerceIn(0, screenHeight - height)
    val handleLeft = (left + (width - handleWidth) / 2f).toInt().coerceIn(0, screenWidth - handleWidth)
    // Keep the side stable throughout a drag: no handle jumping away from the finger.
    val handleTop = (if (handleBelow) top + height + gap else top - handleHeight - gap)
        .coerceIn(0, screenHeight - handleHeight)
    return SourcePlacement(left, top, width, height, handleLeft, handleTop)
}
