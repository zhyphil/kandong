package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

class LanguageRouteTest {
    private fun c(tag: String, score: Double) = LanguageRoute.Candidate(tag, score)
    @Test fun noExpectedAnswerOrGlyphImpliesALanguage() {
        assertEquals("review", LanguageRoute.choose("这是完整的中文原文内容", listOf(c("ja", .99))).action)
        assertEquals("review", LanguageRoute.choose("A visible English phrase", listOf(c("de", .99), c("en", .01))).action)
        assertEquals("review", LanguageRoute.choose("A visible English phrase", listOf(c("und", .99))).action)
    }
    @Test fun strongerUnsupportedCandidateMustNotBeFilteredBeforeChoosing() {
        assertEquals("review", LanguageRoute.choose("Une réservation confirmée", listOf(c("fr", .91), c("es", .99))).action)
        assertEquals("fr", LanguageRoute.choose("Une réservation confirmée", listOf(c("fr", .96), c("es", .03))).language)
    }
    @Test fun originalChineseAccentsAndLiteralValuesAreNeverRewritten() {
        for (text in listOf("付款後不可取消或申請退款。", "付款后不可取消或申请退款。")) {
            val d = LanguageRoute.choose(text, listOf(c("zh", .99)));assertEquals("keep", d.action);assertEquals(text, d.raw)
        }
        val raw = "Le cafe\u0301 est ferme\u0301 aujourd'hui."
        val d = LanguageRoute.choose(raw, listOf(c("fr", .99)));assertEquals(raw, d.raw);assertEquals("translate", d.action)
        for (text in listOf("€27.40", "18:35", "—")) assertEquals(LanguageRoute.Decision("keep-literal", null, text, "NO_LETTERS"), LanguageRoute.choose(text, emptyList()))
    }
    @Test fun shortMixedScriptAndOcrConflictStayUncertainEvenAtHighScore() {
        for (text in listOf("Book", "Annuler", "确认", "请点击 Continue")) assertEquals("review", LanguageRoute.choose(text, listOf(c("en", 1.0))).action)
        assertEquals("OCR_CONFLICT", LanguageRoute.choose("18:35", listOf(c("en", 1.0)), true).reason)
    }
    @Test fun lowScoresTiesAndNarrowGapsRejectAndEvidenceOrderDoesNotMatter() {
        val text = "Refund is not available."
        for (values in listOf(emptyList(), listOf(c("en", .899)), listOf(c("en", .95), c("fr", .80)), listOf(c("en", .95), c("fr", .95))))
            assertEquals("review", LanguageRoute.choose(text, values).action)
        val values = listOf(c("fr", .03), c("en", .96));assertEquals(LanguageRoute.choose(text, values), LanguageRoute.choose(text, values.reversed()))
    }
    @Test fun invalidScoresDuplicatesAndOversizedInputFailClosed() {
        for (values in listOf(listOf(c("en", Double.NaN)), listOf(c("en", 1.01)), listOf(c("en", -.1)), listOf(c("en", .95),c("en", .02))))
            assertEquals("INVALID_EVIDENCE", LanguageRoute.choose("A visible English phrase", values).reason)
        assertEquals("review", LanguageRoute.choose("x".repeat(4097), listOf(c("en", 1.0))).action)
    }
}
