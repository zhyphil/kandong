package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import org.junit.Assert.*
import org.junit.Test

class ProtectedDisplayTest {
    private val page = TranslationPage(1, 1, 1)
    private fun start(flow: OnDemandTranslation, time: Long = 100): FixtureRequest {
        flow.observePage(page)
        val ticket = flow.clickTranslate(time)!!
        assertTrue(flow.captured(ticket, OnDemandFixtures.page(page, time), OnDemandFixtures.roi(0), time))
        return flow.pending!!
    }
    private fun response(r: FixtureRequest): FixtureResponse = FixtureResponse(r.id, r.page, r.targetLanguage, r.model, r.version,
        listOf(FixtureAnswer(r.targets[0], "列车", origin = AnswerOrigin.RECORDED_DEEPL),
            FixtureAnswer(r.targets[1], "在2028年2月29日有效", AnswerKind.LOCAL_DATE, AnswerOrigin.LOCAL_DATE_RULE),
            FixtureAnswer(r.targets[2], null, AnswerKind.KEEP_ORIGINAL, AnswerOrigin.SOURCE, KeepOriginalReason.CHECK_UNVERIFIED)))

    @Test fun threeOutcomesKeepSourceOwnershipAndDistinctOrigins() {
        val flow = OnDemandTranslation(); val request = start(flow)
        assertEquals(3, request.screenContext.blocks.size)
        assertEquals(1, flow.selection!!.targets.size)
        assertTrue(flow.accept(response(request), 100))
        val kinds = listOf(AnswerKind.CANDIDATE, AnswerKind.LOCAL_DATE, AnswerKind.KEEP_ORIGINAL)
        val origins = listOf(AnswerOrigin.RECORDED_DEEPL, AnswerOrigin.LOCAL_DATE_RULE, AnswerOrigin.SOURCE)
        for (i in 0..2) {
            flow.select(OnDemandFixtures.roi(i), 100)
            val frame = flow.render(100); val card = frame.cards.single()
            assertEquals("train.$i", card.targetId); assertEquals("train.$i", frame.anchors.single().sourceId)
            assertEquals(OnDemandFixtures.source[i], card.sourceText)
            assertEquals(kinds[i], card.kind); assertEquals(origins[i], card.origin)
        }
        assertNull(flow.render(100).cards.single().chinese)
        assertEquals("CHECK_UNVERIFIED", flow.render(100).cards.single().reason)
        assertNull(flow.pending)
    }

    @Test fun contradictoryOutcomesAreRejectedAtomically() {
        val flow = OnDemandTranslation(); val r = start(flow); val valid = response(r)
        val mutations = listOf(
            valid.answers[0].copy(chinese = null),
            valid.answers[0].copy(origin = AnswerOrigin.LOCAL_DATE_RULE),
            valid.answers[0].copy(keepReason = KeepOriginalReason.CHECK_UNVERIFIED),
            valid.answers[1].copy(origin = AnswerOrigin.RECORDED_DEEPL),
            valid.answers[2].copy(chinese = "不得展示的旧误译"),
            valid.answers[2].copy(keepReason = null),
            valid.answers[2].copy(origin = AnswerOrigin.RECORDED_DEEPL),
        )
        for (bad in mutations) {
            val answers = valid.answers.map { if (it.binding.id == bad.binding.id) bad else it }
            assertFalse(flow.accept(valid.copy(answers = answers), 100))
            assertTrue(flow.render(100).cards.all { it.chinese == null && it.origin == null })
            assertSame(r, flow.pending)
        }
        assertTrue(flow.accept(valid, 100))
    }

    @Test fun everyClearReasonRevokesEveryKindAndOldCallback() {
        for (reason in listOf(ClearReason.MENU, ClearReason.PAUSE, ClearReason.STOP, ClearReason.PAGE_CHANGE, ClearReason.PROVIDER_CHANGE)) {
            val flow = OnDemandTranslation(); val old = response(start(flow))
            assertTrue(flow.accept(old, 100)); flow.invalidate(reason)
            assertNull(flow.snapshot); assertNull(flow.pending); assertTrue(flow.render(100).cards.isEmpty())
            assertFalse(flow.accept(old, 100))
            flow.observePage(page); assertNull(flow.pending)
            val next = response(start(flow, 101))
            assertFalse(flow.accept(old, 101)); assertTrue(flow.accept(next, 101))
        }
    }

    @Test fun pendingAndDisplayedResultsCannotSurviveWindowRevisionOrExpiry() {
        for (acceptFirst in listOf(false, true)) {
            val flow = OnDemandTranslation(); val old = response(start(flow))
            if (acceptFirst) assertTrue(flow.accept(old, 100))
            flow.observePage(page.copy(window = "changed"))
            assertFalse(flow.accept(old, 101)); assertTrue(flow.render(101).cards.isEmpty()); assertNull(flow.pending)
        }
        val flow = OnDemandTranslation(); val old = response(start(flow)); assertTrue(flow.accept(old, 100))
        assertTrue(flow.render(60_100).cards.isEmpty()); assertFalse(flow.accept(old, 60_100)); assertNull(flow.pending)
    }

    @Test fun sourceMismatchDoesNotPublishPartialResults() {
        val flow = OnDemandTranslation(); val r = start(flow); val valid = response(r)
        val changed = valid.answers[0].copy(binding = r.targets[0].copy(sources = listOf(r.targets[0].sources[0].copy(text = "different"))))
        assertFalse(flow.accept(valid.copy(answers = listOf(changed) + valid.answers.drop(1)), 100))
        assertTrue(flow.render(100).cards.all { it.chinese == null })
        assertTrue(flow.accept(valid, 100))
    }

    @Test fun movingAndZoomingDoNotChangeAnswersOrIssueAnotherRequest() {
        val flow = OnDemandTranslation(); val r = start(flow); assertTrue(flow.accept(response(r), 100))
        flow.select(OnDemandFixtures.roi(2), 100); val old = flow.render(100).cards.single()
        assertTrue(flow.setTransform(MirrorTransform(5.0, 240.0, 120.0), 100))
        assertEquals(old, flow.render(100).cards.single()); assertNull(flow.pending); assertEquals(r.screenContext, flow.snapshot)
    }

    @Test fun originalPresentationNeverDisplaysRejectedTextAndChineseNeedsNoWarning() {
        val rejected = TranslationCard("x", "原始条件", "不得展示的旧误译", "CHECK_UNVERIFIED", AnswerKind.KEEP_ORIGINAL, AnswerOrigin.SOURCE)
        assertEquals("原始条件", rejected.presentation().text)
        assertEquals("这段翻译暂时无法核对，请先看原文。", rejected.presentation().notice)
        val chinese = rejected.copy(chinese = null, reason = "ALREADY_CHINESE")
        assertEquals("原始条件", chinese.presentation().text); assertNull(chinese.presentation().notice)
    }
}
