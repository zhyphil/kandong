package com.kandong.compat

/** Capture and viewport dimensions are physical pixels. Zoom never changes the selected source. */
internal class MagnifierViewport {
    var scale = 2f; private set
    var sourceWidth = 0; private set
    var sourceHeight = 0; private set
    var viewWidth = 0; private set
    var viewHeight = 0; private set
    var panX = 0f; private set
    var panY = 0f; private set
    val maxPanX get() = maxOf(0f, sourceWidth * scale - viewWidth)
    val maxPanY get() = maxOf(0f, sourceHeight * scale - viewHeight)
    val canPan get() = maxPanX > 0f || maxPanY > 0f
    val translateX get() = if (maxPanX > 0f) -panX else (viewWidth - sourceWidth * scale) / 2f
    val translateY get() = if (maxPanY > 0f) -panY else (viewHeight - sourceHeight * scale) / 2f

    fun selectSource(width: Int, height: Int) {
        sourceWidth = width.coerceAtLeast(0); sourceHeight = height.coerceAtLeast(0)
        panX = 0f; panY = 0f
    }
    fun setViewport(width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(0); viewHeight = height.coerceAtLeast(0)
        clampPan()
    }
    fun setScale(value: Float, focusX: Float = viewWidth / 2f, focusY: Float = viewHeight / 2f) {
        if (!value.isFinite() || !focusX.isFinite() || !focusY.isFinite()) return
        // Slider uses the centre; pinch uses the fingers' focal point. Clamp only at source edges.
        val centreX = (focusX - translateX) / scale
        val centreY = (focusY - translateY) / scale
        scale = value.coerceIn(1f, 5f)
        panX = centreX * scale - focusX
        panY = centreY * scale - focusY
        clampPan()
    }
    fun dragBy(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        panX -= dx; panY -= dy
        clampPan()
    }
    private fun clampPan() {
        panX = panX.coerceIn(0f, maxPanX); panY = panY.coerceIn(0f, maxPanY)
    }
}
