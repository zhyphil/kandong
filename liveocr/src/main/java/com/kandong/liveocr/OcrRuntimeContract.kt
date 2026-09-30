package com.kandong.liveocr

import java.util.Collections
import java.util.concurrent.CancellationException

data class OcrBlock(val id: String, val text: String, val left: Int, val top: Int,
    val right: Int, val bottom: Int, val score: Double)
data class OcrPage(val blocks: List<OcrBlock>, val rawCandidateCount: Int)

/** Fixed codes only: native messages, pixels and recognized text never become exception messages. */
class LiveOcrException internal constructor(reason: OcrFailure) : RuntimeException(reason.name) {
    val code: String = reason.name
}

internal enum class OcrFailure {
    INVALID_INPUT, UNSUPPORTED_LANGUAGE, MAIN_THREAD, BUSY, CANCELLED_OR_STALE,
    ASSET_MISSING, ASSET_HASH, ASSET_LENGTH, DICTIONARY, MODEL_NAMES, RUNTIME_VERSION,
    OUTPUT_MISSING, OUTPUT_TYPE, OUTPUT_DTYPE, OUTPUT_SHAPE, OUTPUT_LENGTH,
    OUTPUT_VOCABULARY, OUTPUT_NONFINITE, SHAPE_CAP, PIPELINE_FAILED, CLEANUP_UNCERTAIN,
    PAGE_BOX_BUDGET, PAGE_TEXT_BUDGET, RECOGNITION_WIDTH_BUDGET, UNREADABLE_BOX,
    STRIP_BOUNDARY_AMBIGUITY, UNOWNED_CANDIDATES
}

internal fun requireOcr(ok: Boolean, reason: OcrFailure) {
    if (!ok) throw LiveOcrException(reason)
}

/** Cancellation is sticky. A callback failure is also a cancellation, never a usable result. */
internal class OcrCurrent(private val isCurrent: () -> Boolean) {
    private val owner = Thread.currentThread()
    private var rejected = false
    fun check() {
        val current = !rejected && Thread.currentThread() === owner &&
            !Thread.currentThread().isInterrupted && try { isCurrent() } catch (_: Exception) { false }
        if (!current) {
            rejected = true
            // Geometry's cancellation branch rethrows this instead of converting it to a status.
            throw CancellationException("CANCELLED_OR_STALE")
        }
    }
}

/** Curated FullPageOcrContract guards and offset mapping, without fixture or receipt identity. */
internal object OcrPageContract {
    const val MAX_BOXES_PER_STRIP = 64
    const val MAX_BOXES_PER_PAGE = 128
    const val MAX_RAW_CHARS = 8192

    data class Candidate(val id: String, val text: String, val quad: List<GeometryProbeContract.Point>,
        val score: Double, val ownsCoreCenter: Boolean, val clippedAtStripBoundary: Boolean)

    fun outputShape(model: OcrModel, width: Int): LongArray {
        requireOcr(model in OcrAssets.MODELS, OcrFailure.ASSET_HASH)
        requireOcr(width in 320..RecognitionPacking.MAX_WIDTH, OcrFailure.RECOGNITION_WIDTH_BUDGET)
        // Pinned graph: ceil stride-2 convolutions then floor pool; 452 -> 56, not 57.
        return longArrayOf(1, (width + 3L) / 8, model.vocabulary.toLong()).also {
            // Validate output allocation before resizing or entering native inference.
            CtcDecoder.validateShape(it,it,model.vocabulary)
        }
    }

    fun boxBudget(stripCount: Int, pageCount: Int) {
        requireOcr(stripCount in 0..MAX_BOXES_PER_STRIP && pageCount in 0..MAX_BOXES_PER_PAGE &&
            stripCount.toLong() + pageCount <= MAX_BOXES_PER_PAGE, OcrFailure.PAGE_BOX_BUDGET)
    }

    fun characterBudget(used: Int, added: Int): Int {
        requireOcr(used in 0..MAX_RAW_CHARS && added >= 0 && used.toLong() + added <= MAX_RAW_CHARS,
            OcrFailure.PAGE_TEXT_BUDGET)
        return used + added
    }

    fun candidate(strip: FullPageStripPlanner.Strip, box: BoxPipelineContract.Box,
        text: String, pageHeight: Int): Candidate {
        require(box.quad.size == 4 && box.quad.all {
            it.x.isFinite() && it.y.isFinite() && it.x in 0.0..(strip.read.width - 1.0) &&
                it.y in 0.0..(strip.read.height - 1.0)
        })
        require(box.score.isFinite() && box.score in 0.0..1.0)
        require(listOf(box.contourIndex, box.rawBoxIndex, box.finalBoxIndex).all { it in 0 until 1000 })
        // DB already mapped detector coordinates to strip pixels. Do not scale them a second time.
        val pageQuad = box.quad.map { GeometryProbeContract.Point(it.x + strip.read.left, it.y + strip.read.top) }
        val clipped = (strip.read.top > 0 && box.quad.any { it.y <= 0.0 }) ||
            (strip.read.bottom < pageHeight && box.quad.any { it.y >= strip.read.height - 1.0 })
        return Candidate("s${strip.index}/c${box.contourIndex}-r${box.rawBoxIndex}-f${box.finalBoxIndex}",
            text, pageQuad, box.score,
            strip.ownsSourceCenter(pageQuad.sumOf { it.x } / 4, pageQuad.sumOf { it.y } / 4), clipped)
    }

    fun publish(candidates: List<Candidate>, width: Int, height: Int, current: OcrCurrent): OcrPage {
        current.check()
        requireOcr(candidates.size <= MAX_BOXES_PER_PAGE, OcrFailure.PAGE_BOX_BUDGET)
        var chars = 0
        candidates.forEach { chars = characterBudget(chars, it.text.length) }
        val owned = candidates.filter { it.ownsCoreCenter }
        // Strip halos can see the end of a line already captured in full by its owner.
        // Discard that redundant fragment only with one complete geometric counterpart
        // from a different strip. Keep rejecting orphans, ambiguity and clipped owners.
        requireOcr(candidates.none { fragment -> fragment.clippedAtStripBoundary &&
            (fragment.ownsCoreCenter || owned.count { complete ->
                !complete.clippedAtStripBoundary &&
                    complete.id.substringBefore('/') != fragment.id.substringBefore('/') &&
                    containsFragment(complete.quad,fragment.quad)
            } != 1)
        }, OcrFailure.STRIP_BOUNDARY_AMBIGUITY)
        requireOcr(candidates.isEmpty() || owned.isNotEmpty(), OcrFailure.UNOWNED_CANDIDATES)
        requireOcr(owned.none { it.text.isBlank() }, OcrFailure.UNREADABLE_BOX)
        val blocks = owned.map { row ->
            val left = kotlin.math.floor(row.quad.minOf { it.x }).toInt()
            val top = kotlin.math.floor(row.quad.minOf { it.y }).toInt()
            // Pixel-index quads become half-open Android bounds.
            val right = (kotlin.math.ceil(row.quad.maxOf { it.x }).toInt() + 1).coerceAtMost(width)
            val bottom = (kotlin.math.ceil(row.quad.maxOf { it.y }).toInt() + 1).coerceAtMost(height)
            requireOcr(left in 0 until right && top in 0 until bottom && right <= width && bottom <= height,
                OcrFailure.PIPELINE_FAILED)
            OcrBlock(row.id, row.text, left, top, right, bottom, row.score)
        }.sortedWith(compareBy<OcrBlock> { it.top }.thenBy { it.left }.thenBy { it.id })
        current.check()
        return OcrPage(Collections.unmodifiableList(blocks), candidates.size)
    }

    private fun containsFragment(full: List<GeometryProbeContract.Point>, fragment: List<GeometryProbeContract.Point>): Boolean {
        if(full.size!=4 || fragment.size!=4 || (full+fragment).any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val area=full.indices.sumOf { i -> val a=full[i]; val b=full[(i+1)%4]; a.x*b.y-b.x*a.y }
        if(kotlin.math.abs(area)<1e-3) return false
        // Three physical pixels account for independent detector rounding at the seam;
        // compare convex polygons rather than axis-aligned boxes on slanted text.
        return fragment.all { p ->
            val crosses=full.indices.map { i ->
                val a=full[i]; val b=full[(i+1)%4]
                val dx=b.x-a.x; val dy=b.y-a.y
                val length=kotlin.math.hypot(dx,dy)
                if(length==0.0) return false
                ((dx*(p.y-a.y)-dy*(p.x-a.x))/length)
            }
            crosses.all { it>=-3.0 } || crosses.all { it<=3.0 }
        }
    }
}
