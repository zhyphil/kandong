package com.kandong.ocrlab.context.capture

import org.junit.Assert.*
import org.junit.Test

/** Same synthetic cases run on JVM and Android. No real screen, permissions or transport. */
class LocalCapturePrivacyTest {
    private val yes = CaptureEvidence.YES
    private val no = CaptureEvidence.NO
    private val unknown = CaptureEvidence.UNKNOWN
    private val version = CaptureVersion(1, 2, 3, 4, 10, 0)
    private val window = CaptureWindow(10, CaptureWindowOwner.TARGET_APP, yes, no, no)
    private fun node(id: Int = 1, parent: Int? = null, windowId: Int = 10) = CaptureNode(
        id, parent, windowId, id, CaptureRect(10, id * 20, 150, id * 20 + 15),
        CaptureRect(10, id * 20, 150, id * 20 + 15), CaptureRole.TEXT,
        yes, no, no, no, no, no, yes, CaptureLabelScope.SELF_ONLY,
    )
    private fun page(nodes: List<CaptureNode> = listOf(node())) = CaptureMetadata(
        version, 300, 600, 100, 10_000, yes, yes, listOf(window), nodes,
    )
    private fun inspect(
        metadata: CaptureMetadata = page(),
        checkpoint: () -> CaptureCheckpoint = { CaptureCheckpoint(version, true, 101) },
        read: (Int) -> CaptureLabel = { CaptureLabel("source-$it", null) },
    ) = LocalCapturePrivacy.inspect(metadata, checkpoint, read)
    private fun excluded(n: CaptureNode, reason: CaptureGapReason) {
        val result = inspect(page(listOf(n))) { error("Excluded node label must not be read") }
        assertNull(result.rejection); assertTrue(result.blocks.isEmpty())
        assertEquals(listOf(CaptureGap(n.id, reason)), result.gaps)
    }

    @Test fun wholePageKeepsHeadingButtonAndOutsideSelectionConditionInSourceOrder() {
        val nodes = listOf(node(3), node(1), node(2).copy(role = CaptureRole.BUTTON))
        val labels = mapOf(1 to "Train tickets", 2 to "Book", 3 to "No refund after departure.")
        val read = mutableListOf<Int>()
        val result = inspect(page(nodes)) { read += it; CaptureLabel(labels[it], null) }
        assertNull(result.rejection)
        assertEquals(listOf(1, 2, 3), read)
        assertEquals(listOf("Train tickets", "Book", "No refund after departure."), result.blocks.map { it.text })
        assertEquals(nodes.sortedBy { it.order }.map { it.original }, result.blocks.map { it.original })
        assertEquals(CaptureRole.BUTTON, result.blocks[1].role)
        assertEquals(CaptureOrigin.SYNTHETIC_METADATA, result.origin)
        assertEquals(CaptureCoverage.UNVERIFIED, result.coverage)
    }

    @Test fun frenchAndChinesePagesRemainSeparateAndUnmodified() {
        for (labels in listOf(listOf("Billets de train", "Réserver", "Non remboursable."),
            listOf("列车票", "预订", "出发后不可退款。"), listOf("列車票", "預訂", "出發後不可退款。"))) {
            val result = inspect(page((1..3).map { node(it) })) { CaptureLabel(labels[it - 1], null) }
            assertEquals(labels, result.blocks.map { it.text })
        }
    }

    @Test fun passwordAndSensitiveAndEditableLabelsAreNeverFetched() {
        excluded(node().copy(password = yes), CaptureGapReason.PASSWORD)
        excluded(node().copy(sensitive = yes), CaptureGapReason.SENSITIVE_NODE)
        excluded(node().copy(editable = yes), CaptureGapReason.EDITABLE)
    }

    @Test fun unknownAndUnavailablePrivacyDoNotBecomeFalse() {
        for (e in listOf(unknown, CaptureEvidence.UNAVAILABLE)) {
            excluded(node().copy(password = e), CaptureGapReason.PRIVACY_UNKNOWN)
            excluded(node().copy(sensitive = e), CaptureGapReason.PRIVACY_UNKNOWN)
            excluded(node().copy(editable = e), CaptureGapReason.PRIVACY_UNKNOWN)
        }
    }

    @Test fun visibilityFlagAloneDoesNotAuthorizeOffscreenPartialOccludedOrTruncatedText() {
        excluded(node().copy(visible = no), CaptureGapReason.NOT_VISIBLE)
        excluded(node().copy(visible = unknown), CaptureGapReason.NOT_VISIBLE)
        excluded(node().copy(visibleBounds = null), CaptureGapReason.CLIPPED_OR_OFFSCREEN)
        excluded(node().copy(visibleBounds = CaptureRect(10, 20, 90, 35)), CaptureGapReason.CLIPPED_OR_OFFSCREEN)
        excluded(node().copy(original = CaptureRect(-10, 20, 150, 35), visibleBounds = CaptureRect(-10, 20, 150, 35)), CaptureGapReason.CLIPPED_OR_OFFSCREEN)
        excluded(node().copy(occluded = yes), CaptureGapReason.OCCLUDED)
        excluded(node().copy(occluded = unknown), CaptureGapReason.OCCLUDED)
        excluded(node().copy(truncated = yes), CaptureGapReason.TRUNCATED)
        excluded(node().copy(truncated = unknown), CaptureGapReason.TRUNCATED)
    }

    @Test fun sensitiveOrUnknownWindowPreventsAnyLabelRead() {
        for (w in listOf(window.copy(sensitive = yes), window.copy(protectedContent = yes),
            window.copy(sensitive = unknown), window.copy(protectedContent = unknown),
            window.copy(protectedContent = CaptureEvidence.UNAVAILABLE))) {
            val result = inspect(page().copy(windows = listOf(w))) { error("Window contents must not be fetched") }
            assertTrue(result.blocks.isEmpty()); assertNull(result.rejection)
            assertEquals(if (w.sensitive == yes || w.protectedContent == yes) CaptureGapReason.SENSITIVE_WINDOW
                else CaptureGapReason.WINDOW_PRIVACY_UNKNOWN, result.gaps.single().reason)
        }
    }

    @Test fun ownOverlayOtherAppSystemAndKeyboardAreExcludedWithoutReadingLabels() {
        for (owner in CaptureWindowOwner.values().filter { it != CaptureWindowOwner.TARGET_APP }) {
            val other = CaptureWindow(11, owner, yes, no, no)
            val result = inspect(page(listOf(node(), node(2, windowId = 11))).copy(windows = listOf(window, other))) {
                assertEquals(1, it); CaptureLabel("public heading", null)
            }
            assertEquals(listOf(1), result.blocks.map { it.node })
            assertEquals(listOf(CaptureGap(2, CaptureGapReason.WINDOW_EXCLUDED)), result.gaps)
        }
    }

    @Test fun removingOwnOverlayNodesDoesNotRevealTextOccludedBehindThem() {
        val overlay = CaptureWindow(11, CaptureWindowOwner.OWN_OVERLAY, yes, no, no)
        val result = inspect(page(listOf(node().copy(occluded = yes), node(2, windowId = 11)))
            .copy(windows = listOf(window, overlay))) { error("Neither window label may be read") }
        assertTrue(result.blocks.isEmpty())
        assertEquals(listOf(CaptureGap(1, CaptureGapReason.OCCLUDED), CaptureGap(2, CaptureGapReason.WINDOW_EXCLUDED)), result.gaps)
        assertEquals(CaptureCoverage.UNVERIFIED, result.coverage)
    }

    @Test fun incompleteWindowListMissingOrAmbiguousTargetRejectsBeforeReading() {
        val pages = listOf(page().copy(windowsComplete = unknown), page().copy(windowsComplete = no),
            page().copy(windows = listOf(window.copy(owner = CaptureWindowOwner.UNKNOWN))),
            page().copy(windows = listOf(window, window.copy(id = 11))))
        for (p in pages) {
            val result = inspect(p) { error("Unconfirmed target must not be read") }
            assertEquals(CaptureRejection.TARGET_UNCONFIRMED, result.rejection)
            assertTrue(result.blocks.isEmpty())
        }
    }

    @Test fun excludedAncestorsTaintDescendantsButNotUnrelatedSiblings() {
        val nodes = listOf(node(1).copy(role = CaptureRole.CONTAINER), node(2, 1).copy(password = yes),
            node(3, 1), node(4, 2))
        val result = inspect(page(nodes)) { assertEquals(3, it); CaptureLabel("help", null) }
        assertEquals(listOf(3), result.blocks.map { it.node })
        assertEquals(1, result.blocks.single().parent)
        assertEquals(listOf(CaptureGapReason.CONTAINER_LABEL, CaptureGapReason.PASSWORD, CaptureGapReason.ANCESTOR_EXCLUDED), result.gaps.map { it.reason })
        for (ancestor in listOf(node().copy(sensitive = unknown), node().copy(visible = no), node().copy(occluded = yes))) {
            val r = inspect(page(listOf(ancestor, node(2, 1)))) { error("Ancestor prevents fetch") }
            assertTrue(r.blocks.isEmpty()); assertEquals(CaptureGapReason.ANCESTOR_EXCLUDED, r.gaps.last().reason)
        }
    }

    @Test fun parentAggregationAndUnknownChildrenCannotLeakSensitiveDescendantText() {
        excluded(node().copy(role = CaptureRole.CONTAINER), CaptureGapReason.CONTAINER_LABEL)
        excluded(node().copy(childrenComplete = unknown), CaptureGapReason.CHILDREN_UNKNOWN)
        excluded(node().copy(labelScope = CaptureLabelScope.DESCENDANTS), CaptureGapReason.NONLOCAL_LABEL)
        excluded(node().copy(labelScope = CaptureLabelScope.UNKNOWN), CaptureGapReason.NONLOCAL_LABEL)
    }

    @Test fun duplicateMissingCyclicAndCrossWindowMetadataRejectsAtomically() {
        val cases = listOf(listOf(node(), node()), listOf(node(parent = 99)), listOf(node(parent = 1)),
            listOf(node(parent = 2), node(2, 1)), listOf(node(), node(2).copy(order = 1)),
            listOf(node(windowId = 99)), listOf(node(), node(2, 1, 11)))
        for (nodes in cases) {
            val result = inspect(page(nodes).copy(windows = listOf(window, window.copy(id = 11, owner = CaptureWindowOwner.OTHER_APP)))) {
                error("Malformed graph must be rejected before any read")
            }
            assertEquals(CaptureRejection.INVALID_METADATA, result.rejection); assertTrue(result.blocks.isEmpty())
        }
    }

    @Test fun invalidGeometrySizeTimeAndBoundsAreRejectedBeforeReading() {
        val cases = listOf(page().copy(width = 0), page().copy(height = 10001), page().copy(ttlMillis = 0),
            page().copy(ttlMillis = 60001), page().copy(capturedAtMillis = -1),
            page().copy(version = version.copy(session = -1)),
            page().copy(windows = listOf(window, window)),
            page(listOf(node().copy(original = CaptureRect(10, 20, 10, 35)))),
            page(listOf(node().copy(visibleBounds = CaptureRect(0, 0, 200, 200)))),
            page(List(513) { node(it + 1) }))
        for (p in cases) {
            val result = inspect(p) { error("Invalid metadata must not be read") }
            assertEquals(CaptureRejection.INVALID_METADATA, result.rejection)
        }
    }

    @Test fun incompleteNodeEnumerationPreservesUsableTextAndMissingEvidence() {
        for (e in listOf(no, unknown, CaptureEvidence.UNAVAILABLE)) {
            val result = inspect(page().copy(nodesComplete = e))
            assertEquals("source-1", result.blocks.single().text)
            assertEquals(e, result.enumeration); assertEquals(CaptureCoverage.UNVERIFIED, result.coverage)
        }
    }

    @Test fun descriptionOnlyAndIdenticalLabelsKeepProvenanceWithoutDuplication() {
        val rows = listOf(CaptureLabel(null, "Help"), CaptureLabel("Help", "Help"), CaptureLabel("Help", ""))
        val result = inspect(page((1..3).map { node(it) })) { rows[it - 1] }
        assertEquals(listOf("Help", "Help", "Help"), result.blocks.map { it.text })
        assertEquals(listOf(CaptureLabelSource.DESCRIPTION, CaptureLabelSource.MATCHING_TEXT_AND_DESCRIPTION,
            CaptureLabelSource.TEXT), result.blocks.map { it.labelSource })
    }

    @Test fun conflictingEmptyInvalidAndOversizedLabelsDoNotGetGuessedOrTruncated() {
        val rows = listOf(CaptureLabel("Pay", "Delete"), CaptureLabel(" ", null),
            CaptureLabel("x".repeat(2001), null), CaptureLabel("a\u0000b", null),
            CaptureLabel("a\u202Eb", null), CaptureLabel("a\uD800", null))
        val result = inspect(page(rows.indices.map { node(it + 1) })) { rows[it - 1] }
        assertTrue(result.blocks.isEmpty())
        assertEquals(listOf(CaptureGapReason.CONFLICTING_LABEL, CaptureGapReason.EMPTY_LABEL,
            CaptureGapReason.INVALID_LABEL, CaptureGapReason.INVALID_LABEL, CaptureGapReason.INVALID_LABEL,
            CaptureGapReason.INVALID_LABEL), result.gaps.map { it.reason })
    }

    @Test fun validAccentsWhitespaceAndSupplementaryCharactersAreNotRewritten() {
        val label = "  Café — 37,80 €\n\t\uD83D\uDE86  "
        val result = inspect { CaptureLabel(label, null) }
        assertEquals(label, result.blocks.single().text)
        assertTrue(result.gaps.isEmpty())
    }

    @Test fun readerExceptionKeepsOnlyGenericGapAndContinuesWithOtherEligibleNode() {
        val result = inspect(page(listOf(node(), node(2)))) {
            if (it == 1) throw IllegalStateException("SECRET-PASSWORD")
            CaptureLabel("public", null)
        }
        assertEquals(listOf("public"), result.blocks.map { it.text })
        assertEquals(listOf(CaptureGap(1, CaptureGapReason.READ_FAILED)), result.gaps)
        assertFalse(result.toString().contains("SECRET"))
    }

    @Test fun totalTextBudgetStopsFurtherFetchesAndReportsGaps() {
        val reads = mutableListOf<Int>()
        val result = inspect(page((1..10).map { node(it) })) { reads += it; CaptureLabel("x".repeat(2000), null) }
        assertEquals((1..8).toList(), reads); assertEquals(16000, result.blocks.sumOf { it.text.length })
        assertEquals(listOf(CaptureGap(9, CaptureGapReason.TEXT_BUDGET), CaptureGap(10, CaptureGapReason.TEXT_BUDGET)), result.gaps)
    }

    @Test fun staleInactiveFutureExpiredOrDifferentVersionNeverReads() {
        val checks = listOf(CaptureCheckpoint(version, false, 101), CaptureCheckpoint(version, true, 99),
            CaptureCheckpoint(version, true, 10100), CaptureCheckpoint(version.copy(revision = 5), true, 101),
            CaptureCheckpoint(version.copy(snapshot = 3), true, 101), CaptureCheckpoint(version.copy(window = 11), true, 101),
            CaptureCheckpoint(version.copy(display = 1), true, 101), CaptureCheckpoint(version.copy(session = 2), true, 101))
        for (c in checks) {
            val result = inspect(checkpoint = { c }) { error("Stale capture must not read") }
            assertEquals(CaptureRejection.CANCELLED_OR_STALE, result.rejection); assertTrue(result.blocks.isEmpty())
        }
    }

    @Test fun pauseOrPageChangeDuringReadDiscardsEvenAlreadyReadText() {
        for (change in listOf(false, true)) {
            var current = CaptureCheckpoint(version, true, 101)
            var reads = 0
            val result = inspect(page(listOf(node(), node(2))), { current }) {
                reads++
                current = if (change) current.copy(version = version.copy(revision = 5)) else current.copy(active = false)
                CaptureLabel("must be discarded", null)
            }
            assertEquals(1, reads); assertEquals(CaptureRejection.CANCELLED_OR_STALE, result.rejection)
            assertTrue(result.blocks.isEmpty()); assertTrue(result.gaps.isEmpty())
        }
    }

    @Test fun clockRollbackExpiryAndCheckpointFailureDiscardTheWholeRead() {
        for (mode in 0..2) {
            var afterRead = false
            val result = inspect(checkpoint = {
                if (afterRead && mode == 2) throw IllegalStateException("private diagnostic")
                CaptureCheckpoint(version, true, if (!afterRead) 102 else if (mode == 0) 101 else 10100)
            }) { afterRead = true; CaptureLabel("discard me", null) }
            assertEquals(CaptureRejection.CANCELLED_OR_STALE, result.rejection); assertTrue(result.blocks.isEmpty())
        }
    }

    @Test fun inputCollectionMutationCannotAlterSnapshotDuringRead() {
        val nodes = mutableListOf(node(), node(2))
        val windows = mutableListOf(window)
        val result = inspect(page(nodes).copy(windows = windows)) {
            nodes.clear(); windows.clear(); CaptureLabel("source-$it", null)
        }
        assertEquals(listOf(1, 2), result.blocks.map { it.node })
    }

    @Test fun resultsAreImmutableAndDefaultDiagnosticsDoNotRevealLabels() {
        val result = inspect { CaptureLabel("PRIVATE-LABEL", null) }
        assertFalse(result.toString().contains("PRIVATE"))
        assertFalse(result.blocks.toString().contains("PRIVATE"))
        assertFalse(CaptureLabel("PRIVATE", "PRIVATE").toString().contains("PRIVATE"))
        try { (result.blocks as MutableList).clear(); fail("Expected immutable output") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun emptyTreeStaysUnverifiedAndDoesNotAcquireLabels() {
        val result = inspect(page(emptyList())) { error("No node to fetch") }
        assertNull(result.rejection); assertTrue(result.blocks.isEmpty())
        assertEquals(CaptureCoverage.UNVERIFIED, result.coverage)
    }
}
