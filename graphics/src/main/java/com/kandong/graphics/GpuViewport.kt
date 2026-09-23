package com.kandong.graphics

/**
 * Fixed output size in pixels, independent of zoom. Source pixel-center mapping is
 * `(destinationCenter - translation) / scale - .5`. Translation uses output pixels.
 * Centers outside the transformed source rectangle produce opaque white, without a halo.
 * At exactly 1x, bilinear sampling bypasses sharpening (integer translation is pixel exact).
 */
data class GpuViewport(val width: Int, val height: Int, val scale: Float,
    val translateX: Float, val translateY: Float) {
    init {
        GpuChecks.output(width, height)
        require(scale.isFinite() && scale in 1f..5f) { "Scale must be finite and within 1..5" }
        require(translateX.isFinite() && translateY.isFinite()) { "Translation must be finite" }
    }
}
