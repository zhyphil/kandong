package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import org.junit.Assert.*
import org.junit.Test

/** The new single-model source must not weaken the existing two-model provenance contract. */
class SingleModelContextEvidenceTest {
    private val identity = ScreenIdentity(1, "a".repeat(64), 2, 3)
    private val rect = ContextRect(10.0, 10.0, 100.0, 40.0)
    private fun block(state: BlockState = BlockState.KNOWN, raw: String = "Book") = ContextBlock(
        "block", raw, "en", "unclassified", rect, rect, 0, state = state,
        source = SourceKind.PACKAGED_FULL_PAGE_SINGLE_MODEL_OCR,
        ocr = OcrEvidence(identity.snapshotId, listOf(ContextPoint(10.0,10.0), ContextPoint(100.0,10.0),
            ContextPoint(100.0,40.0), ContextPoint(10.0,40.0)), listOf(OcrCandidate("latin",raw)),
            if (state == BlockState.KNOWN) listOf("latin") else emptyList()))
    private fun snapshot(b: ContextBlock) = ScreenSnapshot(identity, 200.0, 200.0, 100, 1000, listOf(b), emptyList())

    @Test fun singleActualCandidateCanBecomeARequestWithoutFabricatingAnotherModel() {
        val engine = ContextEngine()
        assertTrue(engine.activateSnapshot(snapshot(block()), 100))
        engine.select(rect, 100)
        val request = engine.requestMissing(100)!!
        assertEquals("Book", request.targets.single().sources.single().text)
        assertEquals(listOf(OcrCandidate("latin","Book")), request.screenContext.blocks.single().ocr!!.candidates)
    }
    @Test fun uncertainAndEmptyRawEvidenceStaysVisibleButCannotBeRequested() {
        for ((state, raw) in listOf(BlockState.AMBIGUOUS to "Book", BlockState.CONFLICT to "Book",
            BlockState.UNKNOWN to "", BlockState.UNKNOWN to "  ")) {
            val engine = ContextEngine()
            assertTrue(engine.activateSnapshot(snapshot(block(state,raw)), 100))
            engine.select(rect,100)
            assertNull(engine.requestMissing(100))
            assertEquals(raw,engine.snapshot!!.blocks.single().text)
        }
    }
    @Test fun legacyDualModelSourceStillRejectsSingleModelEvidence() {
        assertFalse(ContextEngine().activateSnapshot(snapshot(block().copy(source=SourceKind.PACKAGED_SYNTHETIC_OCR)),100))
    }
    @Test fun sourceTextSelectionModelAndGeometryCannotBeRewritten() {
        val original = block()
        val variants = listOf(original.copy(text="Rewritten"), original.copy(ocr=null),
            original.copy(ocr=original.ocr!!.copy(selectedModelIds=listOf("ch"))),
            original.copy(ocr=original.ocr!!.copy(candidates=listOf(OcrCandidate("other","Book")))),
            original.copy(ocr=original.ocr!!.copy(pageId="b".repeat(64))),
            original.copy(original=rect.copy(left=11.0),visible=rect.copy(left=11.0)),
            original.copy(ocr=original.ocr!!.copy(reviewReason=OcrReviewReason.CANDIDATE_CONFLICT)))
        variants.forEach { assertFalse(ContextEngine().activateSnapshot(snapshot(it),100)) }
    }
}
