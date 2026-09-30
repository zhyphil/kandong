package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import java.util.concurrent.CancellationException

/** Test-only, synchronous publication boundary. Asset authentication remains the caller's duty. */
internal object FullPageOcrPublication {
    data class Strip(val index: Int, val read: FullPageStripPlanner.Rect, val core: FullPageStripPlanner.Rect,
        val detectorWidth: Int, val detectorHeight: Int, val status: String, val boxes: Int)
    data class Input(val complete: Boolean, val reason: String?, val candidates: List<FullPageOcrContract.Candidate>,
        val strips: List<Strip>, val detectorInvocations: Int, val recognitionInvocations: Int,
        val frameCloseAttempts: Int, val frameClosed: Int, val transportRejection: String?)
    data class Outcome(val published: Boolean, val reason: String?, val association: FullPageOcrAssociation.Result?)
    class Guard(val metadata: RgbaFrameMetadata, source: () -> CaptureCheckpoint) {
        private var source: (() -> CaptureCheckpoint)? = source
        private var lastNow = metadata.acquiredAtMillis
        private var metadataChecked = false

        // Lazy validation: construction must not fail before run() owns and closes the Image.
        fun checkpoint(): CaptureCheckpoint {
            val callback = source ?: throw CancellationException("CANCELLED_OR_STALE")
            try {
                if (!metadataChecked) {
                    val v = metadata.version
                    require(listOf(v.session, v.snapshot, v.page, v.revision).all { it >= 0 } &&
                        v.window >= 0 && v.display >= 0 && metadata.acquiredAtMillis >= 0 &&
                        metadata.ttlMillis in 1..60_000 &&
                        metadata.acquiredAtMillis <= Long.MAX_VALUE - metadata.ttlMillis) { "INVALID_METADATA" }
                    FullPageStripPlanner.plan(metadata.width, metadata.height)
                    metadataChecked = true
                }
                val c = callback()
                if (!c.active || c.version != metadata.version || c.nowMillis < lastNow ||
                    c.nowMillis - metadata.acquiredAtMillis >= metadata.ttlMillis)
                    throw CancellationException("CANCELLED_OR_STALE")
                lastNow = c.nowMillis
                return c
            } catch (e: Exception) {
                finish() // Sticky even if the next callback would return the original version/time.
                throw e
            }
        }
        fun isCurrent(): Boolean = try { checkpoint(); true } catch (_: Exception) { false }
        internal fun finish() { source = null }
    }
    class Pending internal constructor(private var association: FullPageOcrAssociation.Result?,
        private var guard: Guard?, private val rejection: String? = null) {
        private var consumed = false
        fun publish(scopeSucceeded: Boolean, resourcesClosed: Boolean): Outcome {
            if (consumed) return Outcome(false, "ALREADY_CONSUMED", null)
            consumed = true
            return try {
                val reason = rejection ?: when {
                    !scopeSucceeded -> "SCOPE_FAILED"
                    !resourcesClosed -> "RESOURCES_NOT_CLOSED"
                    guard?.isCurrent() != true -> "NOT_CURRENT"
                    association == null -> "NO_ASSOCIATION"
                    else -> null
                }
                if (reason != null) Outcome(false, reason, null) else Outcome(true, null, association)
            } finally { clear() }
        }
        fun discard() { consumed = true; clear() }
        private fun clear() { association = null; guard?.finish(); guard = null }
    }
    fun stage(sourceBatch: String, pageId: String, meta: RgbaFrameMetadata,
        model: FullPageOcrContract.Model, detectorSha: String, dictionarySha: String,
        input: Input, guard: Guard): Pending {
        fun reject(reason: String): Pending { guard.finish(); return Pending(null, null, reason) }
        if (guard.metadata != meta) return reject("GUARD_METADATA_MISMATCH")
        if (!input.complete || input.reason != null) return reject(input.reason ?: "UPSTREAM_INCOMPLETE")
        if (input.transportRejection != null) return reject(input.transportRejection)
        if (input.frameCloseAttempts != 1 || input.frameClosed != 1) return reject("FRAME_NOT_CLOSED")
        if (!guard.isCurrent()) return reject("NOT_CURRENT")
        val plan = FullPageStripPlanner.plan(meta.width, meta.height) // Guard validated the same metadata.
        val strips = input.strips.toList()
        val candidates = input.candidates.toList()
        val histogram = candidates.groupingBy { it.provenance.stripIndex }.eachCount()
        if (strips.size != plan.strips.size || strips.map { it.index }.toSet().size != strips.size ||
            input.detectorInvocations != strips.size || input.recognitionInvocations != candidates.size ||
            strips.any { s ->
                val expected = plan.strips.getOrNull(s.index)
                expected == null || s.read != expected.read || s.core != expected.core ||
                    s.detectorWidth != expected.detector.width || s.detectorHeight != expected.detector.height ||
                    s.status != "COMPLETE" || s.boxes != (histogram[s.index] ?: 0)
            }) return reject("INVALID_RECEIPTS")
        // Receipts come only from actual diagnostics; the plan is used solely for validation.
        val receipts = strips.map { FullPageOcrAssociation.StripReceipt(it.index, it.read, it.core,
            it.status == "COMPLETE", it.boxes) }
        val identity = FullPageOcrAssociation.PageIdentity(sourceBatch, meta.version, pageId, model, detectorSha, dictionarySha)
        val result = FullPageOcrAssociation.associate(identity, plan, FullPageOcrAssociation.UpstreamState.COMPLETE,
            receipts, candidates, guard::isCurrent)
        if (!result.published) return reject(result.rejection?.name ?: "ASSOCIATION_REJECTED")
        if (!guard.isCurrent()) return reject("NOT_CURRENT")
        return Pending(result, guard)
    }
}
