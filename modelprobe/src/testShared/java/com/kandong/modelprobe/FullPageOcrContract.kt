package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import java.util.Collections
import java.util.concurrent.CancellationException

/** Independent diagnostic; no change to the frozen three-box crop contract. */
internal object FullPageOcrContract {
    const val MAX_BOXES_PER_STRIP = 64
    const val MAX_BOXES_PER_PAGE = 128
    const val MAX_RAW_CHARS = 8192
    const val MAX_WIDTH = 1024
    data class Model(val id: String, val sha: String, val vocabulary: Int)
    val models = listOf(
        Model("ch", "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5", 18385),
        Model("latin", "b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a", 504))
    fun outputShape(id: String, sha: String, vocabulary: Int, width: Int): LongArray {
        require(models.any { it.id == id && it.sha == sha && it.vocabulary == vocabulary }) { "MODEL_IDENTITY" }
        require(width in 320..MAX_WIDTH) { "RECOGNITION_WIDTH_BUDGET" }
        // Pinned graph: two ceil stride-2 convolutions, then floor width-2/stride-2 pool.
        // In particular 452 -> 56, NOT ceil(452/8) = 57.
        return longArrayOf(1, (width + 3L) / 8, vocabulary.toLong())
    }
    fun boxBudget(stripCount: Int, pageCount: Int) {
        require(stripCount in 0..MAX_BOXES_PER_STRIP && pageCount in 0..MAX_BOXES_PER_PAGE &&
            stripCount.toLong() + pageCount <= MAX_BOXES_PER_PAGE) { "PAGE_BOX_BUDGET" }
    }
    fun characterBudget(used: Int, added: Int): Int {
        require(used in 0..MAX_RAW_CHARS && added >= 0 && used.toLong() + added <= MAX_RAW_CHARS) { "PAGE_TEXT_BUDGET" }
        return used + added
    }
    class Provenance(val version: CaptureVersion, val pageFixtureId: String, val stripIndex: Int,
        val read: FullPageStripPlanner.Rect, val core: FullPageStripPlanner.Rect,
        val contourIndex: Int, val rawBoxIndex: Int, val finalBoxIndex: Int, val stripReadingOrder: Int,
        val localQuad: List<GeometryProbeContract.Point>, val pageQuad: List<GeometryProbeContract.Point>,
        val detectorScore: Double, val ownsCoreCenter: Boolean, val id: String)
    data class Candidate(val provenance: Provenance, val modelId: String, val rawText: String,
        val recognitionWidth: Int, val recognitionTime: Int)
    fun provenance(version: CaptureVersion, page: String, strip: FullPageStripPlanner.Strip,
        box: BoxPipelineContract.Box, rank: Int): Provenance {
        require(page.matches(Regex("[a-z0-9-]{1,64}")) && rank in 0 until MAX_BOXES_PER_STRIP)
        require(listOf(box.contourIndex, box.rawBoxIndex, box.finalBoxIndex).all { it in 0 until 1000 })
        require(box.score.isFinite() && box.score in 0.0..1.0 && box.quad.size == 4)
        require(box.quad.all { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..(strip.read.width - 1.0) &&
            it.y in 0.0..(strip.read.height - 1.0) })
        val local = Collections.unmodifiableList(ArrayList(box.quad))
        // DB has already mapped detector coordinates to source-strip pixels. Offset only.
        val pageQuad = Collections.unmodifiableList(local.map { GeometryProbeContract.Point(it.x + strip.read.left, it.y + strip.read.top) })
        val id = listOf(version.session, version.snapshot, version.page, version.revision, version.window, version.display)
            .joinToString("-") + "/$page/s${strip.index}/c${box.contourIndex}-r${box.rawBoxIndex}-f${box.finalBoxIndex}"
        return Provenance(version, page, strip.index, strip.read, strip.core, box.contourIndex, box.rawBoxIndex,
            box.finalBoxIndex, rank, local, pageQuad, box.score,
            strip.ownsSourceCenter(pageQuad.sumOf { it.x } / 4, pageQuad.sumOf { it.y } / 4), id)
    }
    /** Solely a temporary one-row numerical adapter. Never derive provenance from this copy. */
    fun numericalRow(row: GeometryProbeContract.Row): GeometryProbeContract.Row =
        row.copy(id = "full-page-local.box-0", originalIndex = 0, readingOrder = 0)
    fun commit(complete: Boolean, candidates: List<Candidate>): List<Candidate> {
        if (!complete) return emptyList()
        require(candidates.size <= MAX_BOXES_PER_PAGE && candidates.sumOf { it.rawText.length.toLong() } <= MAX_RAW_CHARS)
        return Collections.unmodifiableList(ArrayList(candidates)) // Includes overlap, empty and fragment rows.
    }
    class Current(private val meta: RgbaFrameMetadata, private val checkpoint: () -> CaptureCheckpoint) {
        private var lastNow = meta.acquiredAtMillis
        fun check() {
            val c = checkpoint()
            if (!c.active || c.version != meta.version || c.nowMillis < lastNow ||
                c.nowMillis - meta.acquiredAtMillis >= meta.ttlMillis) throw CancellationException("CANCELLED_OR_STALE")
            lastNow = c.nowMillis
        }
    }
}
