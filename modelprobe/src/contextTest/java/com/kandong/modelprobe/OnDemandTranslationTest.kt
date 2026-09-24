package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import org.junit.Assert.*
import org.junit.Test

class OnDemandTranslationTest {
    private val page = TranslationPage(1, 1, 1)
    private fun start(t: OnDemandTranslation, now: Long = 100): OnDemandTranslation.Capture {
        t.observePage(page); return t.clickTranslate(now)!!
    }
    private fun captured(t: OnDemandTranslation, ticket: OnDemandTranslation.Capture) {
        assertTrue(t.captured(ticket, OnDemandFixtures.page(ticket.page, ticket.clickedAt), OnDemandFixtures.roi(0), ticket.clickedAt))
    }
    @Test fun observeMoveZoomAndTimeDoNotCreateCaptureOrTranslation() {
        val t = OnDemandTranslation(); t.observePage(page)
        assertNull(t.snapshot); assertNull(t.pending); assertNull(t.select(OnDemandFixtures.roi(0), 100))
        assertFalse(t.setTransform(MirrorTransform(2.0, 240.0, 120.0), 100))
        assertTrue(t.render(100).cards.isEmpty()); t.observePage(page.copy(revision = 2))
        assertNull(t.snapshot); assertNull(t.pending); assertTrue(t.render(200).cards.isEmpty())
    }
    @Test fun oneClickPreparesWholePageAndMovingRegionOnlyChangesDisplay() {
        val t = OnDemandTranslation(); val ticket = start(t); assertNull(t.clickTranslate(100)); captured(t, ticket)
        val snapshot = t.snapshot; val request = t.pending!!
        assertEquals(listOf("train.0", "train.1", "train.2"), request.targets.map { it.id })
        assertNull(t.clickTranslate(100))
        t.select(OnDemandFixtures.roi(2), 100); t.setTransform(MirrorTransform(4.0, 240.0, 120.0), 100)
        assertSame(snapshot, t.snapshot); assertSame(request, t.pending)
        assertTrue(t.accept(OnDemandFixtures.response(request), 100))
        assertEquals("出发后不可退款", t.render(100).cards.single().chinese)
        t.select(OnDemandFixtures.roi(0), 100)
        assertEquals("开往巴黎的列车", t.render(100).cards.single().chinese); assertNull(t.pending)
    }
    @Test fun pageChangesRevokeCaptureAndDoNotAutomaticallyRecapture() {
        for (changed in listOf(page.copy(session = 2), page.copy(generation = 2), page.copy(revision = 2),
            page.copy(window = "other"), page.copy(display = "other"))) {
            val t = OnDemandTranslation(); val ticket = start(t); t.observePage(changed)
            assertFalse(t.captured(ticket, OnDemandFixtures.page(page, 100), OnDemandFixtures.roi(0), 100))
            assertNull(t.snapshot); assertNull(t.pending); assertEquals(OnDemandTranslation.Phase.NEEDS_REFRESH, t.phase)
            val fresh = t.clickTranslate(101)!!
            assertTrue(t.captured(fresh, OnDemandFixtures.page(changed, 101), OnDemandFixtures.roi(0), 101))
        }
    }
    @Test fun staleCaptureCannotCancelNewClickAndCaptureIsConsumedOnce() {
        val t = OnDemandTranslation(); val old = start(t); t.invalidate(ClearReason.PAUSE); val next = t.clickTranslate(101)!!
        assertFalse(t.captured(old, OnDemandFixtures.page(page, 100), OnDemandFixtures.roi(0), 101))
        captured(t, next)
        assertFalse(t.captured(next, OnDemandFixtures.page(page, 101), OnDemandFixtures.roi(0), 101))
        assertNotNull(t.pending)
    }
    @Test fun wrongPageOrPreClickSnapshotCannotBeAccepted() {
        val bad = listOf(OnDemandFixtures.page(page, 99), OnDemandFixtures.page(page.copy(revision = 2), 100),
            OnDemandFixtures.page(page, 100).copy(width = Double.NaN))
        for (input in bad) {
            val t = OnDemandTranslation(); val ticket = start(t)
            assertFalse(t.captured(ticket, input, OnDemandFixtures.roi(0), 100))
            assertNull(t.snapshot); assertNull(t.pending); assertEquals(OnDemandTranslation.Phase.FAILED, t.phase)
        }
    }
    @Test fun pauseMenuStopAndPageChangeRevokeLateResponses() {
        for (reason in listOf(ClearReason.PAUSE, ClearReason.MENU, ClearReason.STOP, ClearReason.PAGE_CHANGE)) {
            val t = OnDemandTranslation(); captured(t, start(t)); val response = OnDemandFixtures.response(t.pending!!)
            t.invalidate(reason); assertFalse(t.accept(response, 100)); assertNull(t.snapshot); assertNull(t.pending)
            assertTrue(t.render(100).cards.isEmpty())
        }
    }
    @Test fun captureAndResultsExpireWithoutStartingAnythingElse() {
        val t = OnDemandTranslation(); val ticket = start(t); t.tick(60_100)
        assertFalse(t.captured(ticket, OnDemandFixtures.page(page, 100), OnDemandFixtures.roi(0), 60_100))
        assertNull(t.snapshot); assertNull(t.pending)
        val next = t.clickTranslate(60_101)!!; captured(t, next); val answer = OnDemandFixtures.response(t.pending!!)
        assertFalse(t.accept(answer, 120_101)); assertTrue(t.render(120_101).cards.isEmpty()); assertNull(t.pending)
    }
    @Test fun originalModeAndClockFailureNeedAnotherClick() {
        val t = OnDemandTranslation(); captured(t, start(t)); val old = OnDemandFixtures.response(t.pending!!)
        assertTrue(t.accept(old, 100)); t.invalidate(ClearReason.PAUSE)
        assertTrue(t.render(100).cards.isEmpty()); t.observePage(page); assertNull(t.pending)
        val ticket = t.clickTranslate(101)!!
        assertFalse(t.captured(ticket, OnDemandFixtures.page(page, 101), OnDemandFixtures.roi(0), 100))
        assertEquals(OnDemandTranslation.Phase.FAILED, t.phase); assertNull(t.snapshot)
    }
}
