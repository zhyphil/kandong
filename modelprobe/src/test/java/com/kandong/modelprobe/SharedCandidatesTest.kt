package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

class SharedCandidatesTest {
    private val page = "a".repeat(64)
    private fun row(id: Int, raw: String) = SharedCandidates.Candidate(page, "sample.box-$id", id,
        listOf(SharedCandidates.Point(1.0, 1.0), SharedCandidates.Point(20.0, 1.0),
            SharedCandidates.Point(20.0, 12.0), SharedCandidates.Point(1.0, 12.0)), raw)
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    @Test fun conflictingPunctuationDigitsAndBlankCannotTurnIntoLetters() {
        for ((ch, latin) in listOf("。" to "o", "123" to "l23", "" to "A")) {
            val joined = SharedCandidates.join(page, listOf(row(0, ch)), listOf(row(0, latin)))
            assertEquals(SharedCandidates.Origin.REVIEW, joined.single().decision.origin)
            assertNull(joined.single().decision.raw)
            assertEquals(ch, joined.single().ch); assertEquals(latin, joined.single().latin)
        }
    }
    @Test fun agreeingTimesAndCurrencyRemainUsable() {
        for (raw in listOf("18:00.", "€27.40", "é", " ")) {
            assertEquals(SharedCandidates.Decision(SharedCandidates.Origin.AGREED, raw), SharedCandidates.choose(raw, raw))
        }
    }
    @Test fun uncertainDigitsAndEmptyOutputAreNotInvented() {
        for ((ch, latin) in listOf("8" to "9", "" to "", "/" to "")) {
            assertEquals(SharedCandidates.Decision(SharedCandidates.Origin.REVIEW, null), SharedCandidates.choose(ch, latin))
        }
    }
    @Test fun keepsEmptyCandidateInItsBoxAndOrdersByIdentity() {
        val result = SharedCandidates.join(page, listOf(row(1, "Ce bilet"), row(0, "行李8公斤")),
            listOf(row(0, ""), row(1, "Ce billet")))
        assertEquals(listOf("sample.box-0", "sample.box-1"), result.map { it.boxId })
        assertEquals("", result[0].latin)
        assertEquals("行李8公斤\nCe billet", SharedCandidates.pageText(result))
    }
    @Test fun rejectsStaleMissingDuplicateOrDifferentBoxEvenAtSameCoordinates() {
        val ch = listOf(row(0, "行李")); val latin = row(0, "8")
        for (bad in listOf(emptyList(), listOf(latin, latin), listOf(latin.copy(pageId = "b".repeat(64))),
            listOf(latin.copy(boxId = "other.box-0")), listOf(latin.copy(readingOrder = 1)),
            listOf(latin.copy(quad = latin.quad.map { it.copy(x = it.x + .001) })))) {
            rejects { SharedCandidates.join(page, ch, bad) }
        }
    }
    @Test fun validatesBoundsAndPreservesRawUnicodeWithoutCorrection() {
        rejects { SharedCandidates.choose("x".repeat(4097), "") }
        rejects { SharedCandidates.join(page, listOf(row(0, "a")), listOf(row(0, "a").copy(quad = listOf(SharedCandidates.Point(Double.NaN, 0.0))))) }
        val many = (0..8).map { row(it, "a") }; rejects { SharedCandidates.join(page, many, many) }
        assertEquals("税", SharedCandidates.choose("税", "稅").raw)
        assertEquals("é", SharedCandidates.choose("e\u0301", "é").raw)
        assertEquals(" Sortie/出口 ", SharedCandidates.choose(" Sortie/出口 ", "Sortie / ").raw)
    }
    @Test fun cjkAlgorithmicNamesAndSupplementaryCharactersKeepTheSameRule() {
        for (cp in listOf(0x884c, 0x3400, 0x20000, 0xf900)) {
            val raw = String(Character.toChars(cp))
            assertEquals(SharedCandidates.Decision(SharedCandidates.Origin.CH, raw), SharedCandidates.choose(raw, ""))
        }
        assertEquals(SharedCandidates.Origin.REVIEW, SharedCandidates.choose("〇", "").origin)
    }
    @Test fun blankPageHasNoCandidatesButUncertainBlockPreventsCompleteText() {
        assertEquals("", SharedCandidates.pageText(SharedCandidates.join(page, emptyList(), emptyList())))
        assertNull(SharedCandidates.pageText(SharedCandidates.join(page, listOf(row(0, "8")), listOf(row(0, "9")))))
    }
}
