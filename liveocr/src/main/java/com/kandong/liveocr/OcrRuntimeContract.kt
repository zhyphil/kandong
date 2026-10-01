package com.kandong.liveocr

import java.util.Collections
import java.util.concurrent.CancellationException

data class OcrBlock(val id: String, val text: String, val left: Int, val top: Int,
    val right: Int, val bottom: Int, val score: Double)
data class OcrPage(val blocks: List<OcrBlock>, val rawCandidateCount: Int,
    /** Positions whose recognizer returned no text; retain source pixels, never invent words. */
    val unreadable: List<OcrBlock> = emptyList())

/** Fixed codes only: native messages, pixels and recognized text never become exception messages. */
class LiveOcrException internal constructor(reason: OcrFailure,
    val boundaryDiagnostics: OcrBoundaryDiagnostics? = null) : RuntimeException(reason.name) {
    val code: String = reason.name
}

/** Numeric-only failure metadata. Never retains candidates, recognized text or image data. */
data class OcrBoundaryDiagnostics(val candidates: Int, val owned: Int, val clipped: Int,
    val unresolved: Int, val examples: List<OcrBoundaryExample>)
data class OcrBoundaryExample(val fragmentBounds: List<Int>, val fragmentOwned: Boolean,
    val completeMatches: Int, val bestOtherBounds: List<Int>, val bestOtherOwned: Boolean,
    val bestOtherClipped: Boolean, val overlapPercent: Int)

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
        // run() completes every strip before publication. Cores partition the whole
        // frame; halos only provide neighboring context. A clipped, unowned halo box
        // may merge/split words differently from the owner strip's detector, so its
        // shape is not evidence that the owner's full region was missing. Keep raw
        // counts, but never let that duplicate veto the independently processed core.
        // An OWNED box clipped by our internal read boundary remains a real problem.
        val unresolved = owned.filter { it.clippedAtStripBoundary }
        if(unresolved.isNotEmpty()) throw LiveOcrException(OcrFailure.STRIP_BOUNDARY_AMBIGUITY,
            boundaryDiagnostics(candidates,owned,unresolved))
        requireOcr(candidates.isEmpty() || owned.isNotEmpty(), OcrFailure.UNOWNED_CANDIDATES)
        val blocks = owned.map { row ->
            val left = kotlin.math.floor(row.quad.minOf { it.x }).toInt()
            val top = kotlin.math.floor(row.quad.minOf { it.y }).toInt()
            // Pixel-index quads become half-open Android bounds.
            val right = (kotlin.math.ceil(row.quad.maxOf { it.x }).toInt() + 1).coerceAtMost(width)
            val bottom = (kotlin.math.ceil(row.quad.maxOf { it.y }).toInt() + 1).coerceAtMost(height)
            requireOcr(left in 0 until right && top in 0 until bottom && right <= width && bottom <= height,
                OcrFailure.PIPELINE_FAILED)
            OcrBlock(row.id, row.text.ifBlank { "" }, left, top, right, bottom, row.score)
        }.sortedWith(compareBy<OcrBlock> { it.top }.thenBy { it.left }.thenBy { it.id })
        current.check()
        return OcrPage(Collections.unmodifiableList(blocks.filter { it.text.isNotEmpty() }), candidates.size,
            Collections.unmodifiableList(blocks.filter { it.text.isEmpty() }))
    }

    private fun boundaryDiagnostics(all: List<Candidate>, owned: List<Candidate>, unresolved: List<Candidate>): OcrBoundaryDiagnostics {
        fun bounds(c: Candidate)=listOf(c.quad.minOf { it.x }.toInt(),c.quad.minOf { it.y }.toInt(),
            c.quad.maxOf { it.x }.toInt(),c.quad.maxOf { it.y }.toInt())
        fun coverage(a: Candidate,b: Candidate): Double {
            val x=bounds(a); val y=bounds(b)
            val intersection=(minOf(x[2],y[2])-maxOf(x[0],y[0])).coerceAtLeast(0).toDouble()*
                (minOf(x[3],y[3])-maxOf(x[1],y[1])).coerceAtLeast(0)
            return intersection/((x[2]-x[0]).coerceAtLeast(1).toDouble()*(x[3]-x[1]).coerceAtLeast(1))
        }
        val examples=unresolved.take(8).map { fragment ->
            val others=all.filter { it.id.substringBefore('/')!=fragment.id.substringBefore('/') }
            val best=others.maxByOrNull { coverage(fragment,it) }?.takeIf { coverage(fragment,it)>0 }
            OcrBoundaryExample(bounds(fragment),fragment.ownsCoreCenter,
                owned.count { !it.clippedAtStripBoundary && it.id.substringBefore('/')!=fragment.id.substringBefore('/') &&
                    containsFragment(it.quad,fragment.quad) },best?.let(::bounds) ?: emptyList(),
                best?.ownsCoreCenter ?: false,best?.clippedAtStripBoundary ?: false,
                best?.let { (coverage(fragment,it)*100).toInt() } ?: 0)
        }
        return OcrBoundaryDiagnostics(all.size,owned.size,all.count { it.clippedAtStripBoundary },unresolved.size,examples)
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
