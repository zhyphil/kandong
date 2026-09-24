package com.kandong.ocrlab.context

import org.junit.Assert.*
import org.junit.Test

class OcrContextEvidenceTest {
    private val pageId = "a".repeat(64)
    private val roi = ContextRect(20.0, 110.0, 125.0, 145.0)
    private fun evidence(b: ContextBlock, selected: Boolean = false): OcrEvidence = OcrEvidence(
        pageId, listOf(ContextPoint(b.original.left, b.original.top), ContextPoint(b.original.right, b.original.top),
            ContextPoint(b.original.right, b.original.bottom), ContextPoint(b.original.left, b.original.bottom)),
        listOf(OcrCandidate("ch", "。"), OcrCandidate("latin", "o")),
        if (selected) listOf("ch") else emptyList(), if (selected) null else OcrReviewReason.CANDIDATE_CONFLICT,
    )
    private fun page(): ScreenSnapshot {
        val p = ContextFixtures.page(0, 1, 100)
        return p.copy(identity = p.identity.copy(snapshotId = pageId), blocks = p.blocks.map {
            if (it.id == "a.condition") it.copy(text = "", state = BlockState.CONFLICT,
                source = SourceKind.PACKAGED_SYNTHETIC_OCR, ocr = evidence(it)) else it
        })
    }
    private fun changed(transform: (ContextBlock) -> ContextBlock): ScreenSnapshot = page().let { p ->
        p.copy(blocks = p.blocks.map { if (it.id == "a.condition") transform(it) else it })
    }
    @Test fun conflictingConditionOutsideRoiIsRetainedAndBlocksOnlyItsCard() {
        val e = ContextEngine(); assertTrue(e.activateSnapshot(page(), 100)); e.select(roi, 100)
        val target = e.selection!!.targets.single()
        assertEquals("INCOMPLETE_CONFLICT", target.reason)
        val condition = target.context.single { it.id == "a.condition" }
        assertEquals(listOf("。", "o"), condition.ocr!!.candidates.map { it.raw })
        assertEquals(OcrReviewReason.CANDIDATE_CONFLICT, condition.ocr!!.reviewReason)
        assertNull(e.requestMissing(100)); assertNull(e.render(100).cards.single().chinese)
        e.select(ContextRect(20.0, 300.0, 125.0, 335.0), 100)
        assertEquals(listOf("b.price"), e.requestMissing(100)!!.targets.map { it.id })
        assertEquals(9, e.snapshot!!.blocks.size)
    }
    @Test fun evidenceCannotBeRelabelledKnownCorrectOrMovedToAnotherPage() {
        val bad = listOf(
            changed { it.copy(text = "o", state = BlockState.KNOWN) },
            changed { it.copy(ocr = it.ocr!!.copy(pageId = "b".repeat(64))) },
            changed { it.copy(source = SourceKind.SYNTHETIC_FIXTURE) },
            changed { it.copy(ocr = null) },
            changed { it.copy(ocr = it.ocr!!.copy(candidates = listOf(OcrCandidate("ch", "。")))) },
            changed { it.copy(ocr = it.ocr!!.copy(candidates = listOf(OcrCandidate("ch", "。"), OcrCandidate("ch", "o")))) },
            changed { it.copy(ocr = it.ocr!!.copy(quad = it.ocr!!.quad.map { p -> p.copy(x = p.x + 1) })) },
            changed { it.copy(ocr = it.ocr!!.copy(candidates = listOf(OcrCandidate("ch", "x".repeat(4097)), OcrCandidate("latin", "o")))) },
        )
        for (p in bad) {
            val e = ContextEngine(); assertTrue(e.activateSnapshot(ContextFixtures.page(0, 1, 100), 100)); e.select(roi, 100)
            val request = e.requestMissing(100)!!
            assertFalse(e.activateSnapshot(p, 100)); assertNull(e.snapshot)
            assertFalse(e.acceptResponse(ContextFixtures.response(request)!!, 100))
        }
    }
    @Test fun chosenTextMustBeExactCandidateAndAgreementMustBeReal() {
        val good = changed { it.copy(text = "。", state = BlockState.KNOWN, ocr = evidence(it, true)) }
        assertTrue(ContextEngine().activateSnapshot(good, 100))
        for (replace in listOf<(ContextBlock) -> ContextBlock>(
            { it.copy(text = "corrected") },
            { it.copy(ocr = it.ocr!!.copy(selectedModelIds = listOf("missing"))) },
            { it.copy(ocr = it.ocr!!.copy(selectedModelIds = listOf("ch", "latin"))) },
            { it.copy(ocr = it.ocr!!.copy(selectedModelIds = listOf("ch", "ch"))) },
        )) assertFalse(ContextEngine().activateSnapshot(good.copy(blocks = good.blocks.map { if (it.id == "a.condition") replace(it) else it }), 100))
    }
    @Test fun snapshotOwnsNestedCandidateAndGeometryLists() {
        val p = page(); val b = p.blocks.single { it.id == "a.condition" }; val ev = b.ocr!!
        val candidates = ev.candidates.toMutableList(); val quad = ev.quad.toMutableList(); val selected = mutableListOf<String>()
        val mutable = p.copy(blocks = p.blocks.map { if (it.id == b.id) it.copy(ocr = ev.copy(candidates = candidates, quad = quad, selectedModelIds = selected)) else it })
        val e = ContextEngine(); assertTrue(e.activateSnapshot(mutable, 100)); e.select(roi, 100)
        candidates.clear(); quad.clear(); selected.add("latin")
        val retained = e.snapshot!!.blocks.single { it.id == b.id }.ocr!!
        assertEquals(listOf("。", "o"), retained.candidates.map { it.raw }); assertEquals(4, retained.quad.size)
        assertTrue(retained.selectedModelIds.isEmpty()); assertNull(e.requestMissing(100))
        assertThrows(UnsupportedOperationException::class.java) { (retained.candidates as MutableList).clear() }
    }
    @Test fun pageAndLifecycleChangesRevokeEvidenceAndLateAnswers() {
        for (reason in listOf(ClearReason.PAUSE, ClearReason.MENU, ClearReason.STOP, ClearReason.PAGE_CHANGE)) {
            val e = ContextEngine(); assertTrue(e.activateSnapshot(page(), 100))
            e.select(ContextRect(20.0, 300.0, 125.0, 335.0), 100); val r = e.requestMissing(100)!!
            e.clear(reason); assertNull(e.snapshot); assertFalse(e.acceptResponse(ContextFixtures.response(r)!!, 100))
            assertTrue(e.render(100).cards.isEmpty())
        }
        val e = ContextEngine(); assertTrue(e.activateSnapshot(page(), 100)); e.select(roi, 100)
        assertFalse(e.tick(60_100)); assertNull(e.snapshot); assertTrue(e.render(60_100).cards.isEmpty())
    }
}
