package com.kandong.modelprobe

import com.kandong.ocrlab.context.*

/** Test-only bridge. Groups are explicitly hand supplied, never inferred from OCR proximity. */
internal object CandidateContext {
    fun activate(
        engine: ContextEngine, identity: ScreenIdentity, width: Double, height: Double,
        capturedAt: Long, ttlMillis: Long, ch: List<SharedCandidates.Candidate>,
        latin: List<SharedCandidates.Candidate>, manualGroups: List<SemanticGroup>, now: Long,
    ): Boolean {
        // An invalid replacement must revoke the prior page and publication rights too.
        engine.clear(ClearReason.INVALID_SNAPSHOT)
        return try {
            val joined = SharedCandidates.join(identity.snapshotId, ch, latin)
            val owner = mutableMapOf<String, String>()
            for (group in manualGroups) for (id in group.memberIds) {
                require(owner.put(id, group.id) == null) { "Ambiguous manual ownership" }
            }
            require(joined.all { it.boxId in owner }) { "Every OCR block needs explicit synthetic ownership" }
            val screen = ContextRect(0.0, 0.0, width, height)
            require(screen.valid())
            val blocks = joined.map { b ->
                val original = ContextRect(b.quad.minOf { it.x }, b.quad.minOf { it.y }, b.quad.maxOf { it.x }, b.quad.maxOf { it.y })
                val visible = requireNotNull(ContextGeometry.intersect(original, screen))
                val noText = b.ch.isBlank() && b.latin.isBlank()
                val review = when {
                    noText -> OcrReviewReason.EMPTY_TEXT
                    b.decision.origin == SharedCandidates.Origin.REVIEW -> OcrReviewReason.CANDIDATE_CONFLICT
                    else -> null
                }
                val selected = if (review != null) emptyList() else when (b.decision.origin) {
                    SharedCandidates.Origin.AGREED -> listOf("ch", "latin")
                    SharedCandidates.Origin.CH -> listOf("ch")
                    SharedCandidates.Origin.LATIN -> listOf("latin")
                    SharedCandidates.Origin.REVIEW -> emptyList()
                }
                ContextBlock(b.boxId, if (review == null) checkNotNull(b.decision.raw) else "", "und", "unclassified",
                    original, visible, b.readingOrder, groupId = owner.getValue(b.boxId),
                    state = when (review) {
                        OcrReviewReason.EMPTY_TEXT -> BlockState.UNKNOWN
                        OcrReviewReason.CANDIDATE_CONFLICT -> BlockState.CONFLICT
                        null -> BlockState.KNOWN
                    }, clipped = original != visible, source = SourceKind.PACKAGED_SYNTHETIC_OCR,
                    ocr = OcrEvidence(b.pageId, b.quad.map { ContextPoint(it.x, it.y) },
                        listOf(OcrCandidate("ch", b.ch), OcrCandidate("latin", b.latin)), selected, review))
            }
            engine.activateSnapshot(ScreenSnapshot(identity, width, height, capturedAt, ttlMillis, blocks,
                manualGroups, listOf("MANUAL_SYNTHETIC_GROUPS_LANGUAGE_UNDETERMINED")), now)
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
