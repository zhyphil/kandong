package com.kandong.compat

import com.kandong.graphics.GpuViewport

/** Optional enhancement bounds must never restrict the original magnifier. */
internal object ClarityTarget {
    fun create(enabled: Boolean, width: Int, height: Int, scale: Float, x: Float, y: Float): GpuViewport? {
        if (!enabled) return null
        return try { GpuViewport(width, height, scale, x, y) }
            catch (_: IllegalArgumentException) { null }
    }
}
