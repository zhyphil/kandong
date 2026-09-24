package com.kandong.modelprobe

import java.util.Collections

/** Test-only geometry candidate. No image access, consent, capture freshness or OCR claim. */
internal object FullPageStripPlanner {
    const val HALO = 64
    const val MAX_FRAME_PIXELS = 4_194_304
    const val MAX_STRIPS = 16

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
    class Strip internal constructor(
        val index: Int, val read: Rect, val core: Rect, val detector: DetectorPacking.Plan,
    ) {
        fun sourceX(detectorX: Double): Double {
            require(detectorX.isFinite() && detectorX in 0.0..detector.width.toDouble())
            return read.left + detectorX * read.width / detector.width
        }
        fun sourceY(detectorY: Double): Double {
            require(detectorY.isFinite() && detectorY in 0.0..detector.height.toDouble())
            return read.top + detectorY * read.height / detector.height
        }
        // Geometric ownership only; this is not an OCR fragment merging/deduplication rule.
        fun ownsSourceCenter(x: Double, y: Double): Boolean = x.isFinite() && y.isFinite() &&
            x >= core.left && x < core.right && y >= core.top && y < core.bottom
    }
    class Plan internal constructor(val sourceWidth: Int, val sourceHeight: Int, strips: List<Strip>) {
        val strips: List<Strip> = Collections.unmodifiableList(ArrayList(strips))
    }
    fun plan(width: Int, height: Int): Plan {
        // Explicit candidate scope: portrait-sized widths; never silently crop or rotate a page.
        require(width in 32..2048 && height in 32..4096)
        require(width.toLong() * height <= MAX_FRAME_PIXELS)
        // Respect BOTH the existing source-image and packed detector budgets. Keep the frozen
        // detector planner unchanged, including its adaptive resizing and ties-to-even rounding.
        val maxReadHeight = (2048 downTo 32 step 32).firstOrNull { h ->
            if (width.toLong() * h > DetectorPacking.MAX_PIXELS) false
            else try { DetectorPacking.plan(width, h); true } catch (_: IllegalArgumentException) { false }
        } ?: throw IllegalArgumentException("No strip fits the existing detector budget")
        val maxCoreHeight = maxReadHeight - 2 * HALO
        require(maxCoreHeight >= 32)
        val count = (height + maxCoreHeight - 1) / maxCoreHeight
        require(count in 1..MAX_STRIPS)
        val strips = (0 until count).map { i ->
            // Even cores avoid a very thin final strip; every row belongs to exactly one core.
            val core = Rect(0, i * height / count, width, (i + 1) * height / count)
            val read = Rect(0, maxOf(0, core.top - HALO), width, minOf(height, core.bottom + HALO))
            require(read.width.toLong() * read.height <= DetectorPacking.MAX_PIXELS)
            Strip(i, read, core, DetectorPacking.plan(read.width, read.height))
        }
        return Plan(width, height, strips)
    }
}
