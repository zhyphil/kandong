package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import org.junit.Assert.*
import org.junit.Test

class CandidateContextTest {
    private val page = "a".repeat(64)
    private val identity = ScreenIdentity(1, page, 1, 1)
    private val roi = ContextRect(5.0, 5.0, 105.0, 35.0)
    private fun row(i: Int, text: String, pageId: String = page): SharedCandidates.Candidate {
        val y = 10.0 + i * 50
        return SharedCandidates.Candidate(pageId, "b$i", i, listOf(SharedCandidates.Point(10.0, y),
            SharedCandidates.Point(100.0, y), SharedCandidates.Point(100.0, y + 20), SharedCandidates.Point(10.0, y + 20)), text)
    }
    private val ch get() = listOf(row(0, "120 €"), row(1, "。"), row(2, "Continue"))
    private val latin get() = listOf(row(0, "120 €"), row(1, "o"), row(2, "Continue"))
    private val groups get() = listOf(SemanticGroup("card", GroupKind.CARD, listOf("b0", "b1")),
        SemanticGroup("other", GroupKind.CARD, listOf("b2")))
    private fun activate(e: ContextEngine, c: List<SharedCandidates.Candidate> = ch, l: List<SharedCandidates.Candidate> = latin,
        g: List<SemanticGroup> = groups) = CandidateContext.activate(e, identity, 200.0, 200.0, 100, 60_000, c, l, g, 100)
    private fun answer(r: FixtureRequest) = FixtureResponse(r.id, r.page, r.targetLanguage, r.model, r.version,
        r.targets.map { FixtureAnswer(it, "人工占位，不是模型译文") })

    @Test fun selectionCannotDropOutsideConflictOrDiscardRawCandidates() {
        val e = ContextEngine(); assertTrue(activate(e, ch.reversed(), latin))
        assertEquals(listOf("b0", "b1", "b2"), e.snapshot!!.blocks.map { it.id })
        assertEquals(listOf("und", "und", "und"), e.snapshot!!.blocks.map { it.language })
        val conflict = e.snapshot!!.blocks[1]
        assertEquals("", conflict.text); assertEquals(listOf("。", "o"), conflict.ocr!!.candidates.map { it.raw })
        assertEquals(ch[1].quad.map { ContextPoint(it.x, it.y) }, conflict.ocr!!.quad)
        e.select(roi, 100); assertEquals("INCOMPLETE_CONFLICT", e.selection!!.targets.single().reason)
        assertEquals(listOf("b1"), e.selection!!.targets.single().context.map { it.id }); assertNull(e.requestMissing(100))
        val snapshot = e.snapshot; e.setTransform(MirrorTransform(4.0, 240.0, 120.0), 100)
        e.select(ContextRect(5.0, 105.0, 105.0, 135.0), 100); assertSame(snapshot, e.snapshot)
        val request = e.requestMissing(100)!!; assertEquals("b2", request.targets.single().id)
        assertTrue(e.acceptResponse(answer(request), 100))
        e.select(roi, 100); assertNull(e.render(100).cards.single().chinese)
    }
    @Test fun invalidCandidateIdentityPairingAndOwnershipClearPreviousPage() {
        val variants = listOf(
            Triple(ch, latin.map { it.copy(pageId = "b".repeat(64)) }, groups),
            Triple(ch, latin.dropLast(1), groups), Triple(ch, latin, groups.dropLast(1)),
            Triple(ch, latin, groups + SemanticGroup("duplicate", GroupKind.CARD, listOf("b1"))),
            Triple(ch, latin.map { if (it.boxId == "b1") it.copy(quad = it.quad.map { p -> p.copy(x = p.x + 1) }) else it }, groups),
            Triple(ch.map { it.copy(raw = "x".repeat(4097)) }, latin, groups),
        )
        for ((c, l, g) in variants) {
            val e = ContextEngine(); assertTrue(activate(e)); e.select(ContextRect(5.0, 105.0, 105.0, 135.0), 100)
            val old = e.requestMissing(100)!!
            assertFalse(activate(e, c, l, g)); assertNull(e.snapshot); assertEquals(0, e.pendingCount)
            assertFalse(e.acceptResponse(answer(old), 100))
        }
    }
    @Test fun emptyCandidateBlocksRemainUnknownAndClippedBlocksStayDisclosed() {
        val e = ContextEngine(); assertTrue(activate(e, ch.map { if (it.boxId == "b1") it.copy(raw = " ") else it },
            latin.map { if (it.boxId == "b1") it.copy(raw = "") else it }))
        assertEquals(3, e.snapshot!!.blocks.size); assertEquals(BlockState.UNKNOWN, e.snapshot!!.blocks[1].state)
        assertEquals(OcrReviewReason.EMPTY_TEXT, e.snapshot!!.blocks[1].ocr!!.reviewReason)
        e.select(roi, 100); assertEquals("INCOMPLETE_UNKNOWN", e.selection!!.targets.single().reason)
        val edge = row(0, "Read").let { it.copy(quad = it.quad.map { p -> p.copy(x = p.x - 20) }) }
        assertTrue(activate(e, listOf(edge), listOf(edge), listOf(SemanticGroup("edge", GroupKind.CARD, listOf("b0")))))
        assertTrue(e.snapshot!!.blocks.single().clipped); e.select(roi, 100)
        assertEquals("TRUNCATED_GEOMETRY", e.selection!!.targets.single().reason); assertNull(e.requestMissing(100))
    }
    @Test fun replacementAndExpiryNeverReviveOldEvidenceOrResults() {
        val e = ContextEngine(); assertTrue(activate(e)); e.select(ContextRect(5.0, 105.0, 105.0, 135.0), 100)
        val old = e.requestMissing(100)!!
        assertTrue(CandidateContext.activate(e, identity.copy(pageGeneration = 2), 200.0, 200.0, 100, 60_000, ch, latin, groups, 100))
        assertFalse(e.acceptResponse(answer(old), 100)); assertNull(e.selection)
        e.select(roi, 100); assertFalse(e.tick(60_100)); assertNull(e.snapshot)
        assertTrue(e.render(60_100).cards.isEmpty())
    }
}
