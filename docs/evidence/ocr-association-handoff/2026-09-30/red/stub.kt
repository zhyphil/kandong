package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureCheckpoint

/** Test-only API skeleton for the publication-boundary regressions. */
internal object FullPageOcrPublication {
    data class Strip(val index: Int, val read: FullPageStripPlanner.Rect, val core: FullPageStripPlanner.Rect,
        val detectorWidth: Int, val detectorHeight: Int, val status: String, val boxes: Int)
    data class Input(val complete: Boolean, val reason: String?, val candidates: List<FullPageOcrContract.Candidate>,
        val strips: List<Strip>, val detectorInvocations: Int, val recognitionInvocations: Int,
        val frameCloseAttempts: Int, val frameClosed: Int, val transportRejection: String?)
    data class Outcome(val published: Boolean, val reason: String?, val association: FullPageOcrAssociation.Result?)
    class Guard(val metadata: RgbaFrameMetadata, private val source: () -> CaptureCheckpoint) {
        fun checkpoint(): CaptureCheckpoint = source()
        fun isCurrent(): Boolean = true
    }
    class Pending {
        fun publish(scopeSucceeded: Boolean, resourcesClosed: Boolean): Outcome = Outcome(false, "NOT_IMPLEMENTED", null)
        fun discard() {}
    }
    fun stage(sourceBatch: String, pageId: String, meta: RgbaFrameMetadata,
        model: FullPageOcrContract.Model, detectorSha: String, dictionarySha: String,
        input: Input, guard: Guard): Pending = Pending()
}
