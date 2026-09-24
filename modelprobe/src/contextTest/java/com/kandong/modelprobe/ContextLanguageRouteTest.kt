package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

class ContextLanguageRouteTest {
    private val pageId = "a".repeat(64)
    private fun c(language: String, score: Double = .99) = LanguageRoute.Candidate(language, score)
    private fun b(id: String, text: String, group: String = "card", conflict: Boolean = false) = ContextLanguageRoute.Block(id, group, text, conflict)
    private fun page(vararg blocks: ContextLanguageRoute.Block) = ContextLanguageRoute.Page(pageId, blocks.toList())
    private fun evidence(page: ContextLanguageRoute.Page, select: (ContextLanguageRoute.Query) -> List<LanguageRoute.Candidate> = { listOf(c("en")) }) =
        ContextLanguageRoute.queries(page).map { ContextLanguageRoute.Observation(page.id, it, select(it)) }
    private fun decide(p: ContextLanguageRoute.Page, e: List<ContextLanguageRoute.Observation>, veto: Boolean = true) = ContextLanguageRoute.choose(p, "target", e, veto)

    @Test fun sameCardCanSupportAnAmbiguousLabelWithoutChangingItsRawText() {
        val p = page(b("target", "Total"), b("context", "The total includes all applicable taxes."))
        val e = evidence(p) { if (it.key == "block:target") listOf(c("en", .55), c("fr", .44)) else listOf(c("en")) }
        assertEquals(LanguageRoute.Decision("translate", "en", "Total", "GROUP_SUPPORTED_CANDIDATE"), decide(p, e))
    }
    @Test fun strongForeignSpanVetoesAConfidentWholeString() {
        val p = page(b("target", "Confirmer booking"))
        val e = evidence(p) { if (it.raw == "Confirmer") listOf(c("fr")) else listOf(c("en")) }
        assertEquals("translate", decide(p, e, false).action)
        assertEquals("SPAN_LANGUAGE_CONFLICT", decide(p, e).reason)
        assertEquals("Confirmer booking", decide(p, e).raw)
    }
    @Test fun anotherCardAndTheWholePageCannotSupplyAnAnchor() {
        val p = page(b("target", "Retour"), b("other", "Read the description carefully before continuing.", "other"))
        val e = evidence(p) { if (it.blockId == "target" || it.key == "group:card") listOf(c("fr", .65), c("en", .30)) else listOf(c("en")) }
        assertEquals("NO_GROUP_ANCHOR", decide(p, e).reason)
    }
    @Test fun conflictingAnchorsOrStrongTargetEvidenceCannotBeOverridden() {
        val p = page(b("target", "Total"), b("en", "The total includes all applicable taxes."), b("fr", "Le montant comprend toutes les taxes applicables."))
        val e = evidence(p) { when { it.blockId == "fr" -> listOf(c("fr")); it.blockId == "target" -> listOf(c("en", .55), c("fr", .44)); else -> listOf(c("en")) } }
        assertEquals("GROUP_LANGUAGE_CONFLICT", decide(p, e).reason)
        val q = page(b("target", "Annuler"), b("en", "Please verify all the information shown below."))
        val f = evidence(q) { if (it.blockId == "target") listOf(c("fr", .90), c("en", .10)) else listOf(c("en")) }
        assertEquals("SPAN_LANGUAGE_CONFLICT", decide(q, f).reason)
    }
    @Test fun missingDuplicateCrossPageAndAlteredRawEvidenceAreRejected() {
        val p = page(b("target", "Your ticket is valid until midnight.")); val e = evidence(p)
        for (changed in listOf(e.drop(1), e + e.first(), e.map { it.copy(pageId = "b".repeat(64)) },
            e.map { it.copy(query = it.query.copy(raw = it.query.raw + "x")) }, e.map { it.copy(query = it.query.copy(start = 999)) }))
            assertEquals("INVALID_BINDING", decide(p, changed).reason)
    }
    @Test fun LiteralAndChineseRawRemainIntactAndOcrConflictDoesNotLeakAcrossCards() {
        for ((raw, tag, action) in listOf(Triple("€71.80", "und", "keep-literal"), Triple("付款完成之後無法申請退款。", "zh", "keep"))) {
            val p = page(b("target", raw), b("other", "Unreadable", "other", true))
            val d = decide(p, evidence(p) { listOf(c(tag)) });assertEquals(raw, d.raw);assertEquals(action, d.action)
        }
        val p = page(b("target", "€71.80"), b("own", "Unreadable", conflict = true))
        assertEquals("OCR_CONFLICT", decide(p, evidence(p)).reason)
        val mixed = page(b("target", "请点击 Continue 按钮完成付款。"))
        assertEquals("MIXED_SCRIPTS", decide(mixed, evidence(mixed)).reason)
    }
    @Test fun windowsKeepExactUtf16OffsetsAndDoNotNormalizeAccents() {
        val p = page(b("target", "🎫 Le cafe\u0301 n’est pas ouvert."))
        val spans = ContextLanguageRoute.queries(p).filter { it.start != null }
        assertTrue(spans.any { it.raw == "cafe\u0301" })
        assertTrue(spans.any { it.raw == "n’est" })
        for (q in spans) assertEquals(q.raw, p.blocks.single().raw.substring(q.start!!, q.end!!))
    }
    @Test fun budgetsAndMalformedScoresCannotBeAccepted() {
        for (p in listOf(page(b("target", "a".repeat(513))), page(b("target", "words ".repeat(40))), page(b("target", "x"), b("target", "y"))))
            assertEquals("INVALID_PAGE", decide(p, emptyList()).reason)
        val p = page(b("target", "A normal sentence with enough words."))
        for (scores in listOf(emptyList(), listOf(c("en", Double.NaN)), listOf(c("en", .99), c("en", .01))))
            assertEquals("INVALID_BINDING", decide(p, evidence(p) { scores }).reason)
    }
    @Test fun AnAnchorWithConflictingSpansCannotPromoteAShortLabel() {
        val p = page(b("target", "Total"), b("context", "Please confirmer booking details."))
        val e = evidence(p) { when { it.raw == "confirmer" -> listOf(c("fr")); it.blockId == "target" -> listOf(c("en", .55), c("fr", .44)); else -> listOf(c("en")) } }
        assertEquals("translate", decide(p, e, false).action)
        assertEquals("SPAN_LANGUAGE_CONFLICT", decide(p, e).reason)
    }
}
