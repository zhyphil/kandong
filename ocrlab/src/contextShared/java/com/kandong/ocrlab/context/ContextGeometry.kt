package com.kandong.ocrlab.context

import kotlin.math.max
import kotlin.math.min

object ContextGeometry {
    fun intersect(a: ContextRect, b: ContextRect): ContextRect? {
        require(a.valid() && b.valid())
        val result = ContextRect(max(a.left, b.left), max(a.top, b.top), min(a.right, b.right), min(a.bottom, b.bottom))
        return result.takeIf { it.width > 0 && it.height > 0 }
    }
    fun transform(roi: ContextRect, value: MirrorTransform): MirrorTransform {
        require(roi.valid())
        require(value.scale.isFinite() && value.scale in 1.0..5.0)
        require(value.width.isFinite() && value.width > 0 && value.width <= 10_000)
        require(value.height.isFinite() && value.height > 0 && value.height <= 10_000)
        require(value.panX.isFinite() && value.panY.isFinite())
        return value.copy(
            panX = value.panX.coerceIn(0.0, max(0.0, roi.width - value.width / value.scale)),
            panY = value.panY.coerceIn(0.0, max(0.0, roi.height - value.height / value.scale)),
        )
    }
    fun map(visible: ContextRect, roi: ContextRect, value: MirrorTransform): ContextRect? {
        val t = transform(roi, value)
        val clipped = intersect(visible, roi) ?: return null
        val mapped = ContextRect(
            (clipped.left - roi.left - t.panX) * t.scale,
            (clipped.top - roi.top - t.panY) * t.scale,
            (clipped.right - roi.left - t.panX) * t.scale,
            (clipped.bottom - roi.top - t.panY) * t.scale,
        )
        return intersect(mapped, ContextRect(0.0, 0.0, t.width, t.height))
    }
}
