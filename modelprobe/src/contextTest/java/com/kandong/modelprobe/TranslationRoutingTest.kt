package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic responses; no network, provider account, or translation-quality assertions. */
class TranslationRoutingTest {
    private val local = TranslationProviderChoice(TranslationMode.LOCAL, "local-test-double", "v1", true)
    private val online = TranslationProviderChoice(TranslationMode.ONLINE, "online-test-double", "v1", true, "text-only-v1")
    private val page = TranslationPage(1, 1, 1)
    private fun flow(p: TranslationProviderChoice = local) = OnDemandTranslation(p).also { it.observePage(page) }
    private fun capture(t: OnDemandTranslation, now: Long = 100): FixtureRequest {
        val ticket = t.clickTranslate(now)!!
        assertTrue(t.captured(ticket, OnDemandFixtures.page(ticket.page, now), OnDemandFixtures.roi(0), now))
        return t.pending!!
    }
    private fun permit(t: OnDemandTranslation) {
        t.setNetworkAvailable(true)
        assertTrue(t.confirmOnlineUse(t.provider, page.session))
    }
    @Test fun localFailureNeverSelectsOnlineOrStartsNewWork() {
        val t = flow(); val request = capture(t)
        assertEquals("local-test-double", request.model)
        assertTrue(t.failed(request.id, 101)); assertEquals(local, t.provider)
        assertEquals(TranslationIssue.REQUEST_FAILED, t.issue)
        assertNull(t.pending); assertNull(t.snapshot)
        t.tick(102); assertNull(t.pending)
        assertFalse(t.accept(OnDemandFixtures.response(request), 103))
    }
    @Test fun unavailableLocalDoesNotFallBackAndOnlineRequiresChoiceAndConsentBeforeCapture() {
        val t = flow(local.copy(configured = false))
        assertNull(t.clickTranslate(100)); assertEquals(TranslationIssue.PROVIDER_UNAVAILABLE, t.issue)
        t.chooseProvider(online); t.setNetworkAvailable(true)
        assertNull(t.clickTranslate(101)); assertEquals(TranslationIssue.ONLINE_CONSENT_REQUIRED, t.issue)
        assertNull(t.snapshot); assertFalse(t.confirmOnlineUse(online, 2))
        assertTrue(t.confirmOnlineUse(online, 1)); assertNull(t.pending); assertNull(t.snapshot)
        val request = capture(t, 102)
        assertEquals("online-test-double", request.model)
        assertEquals(3, request.screenContext.blocks.size)
        assertEquals(3, request.targets.size)
    }
    @Test fun providerSwitchAndDisclosureChangeInvalidateOldResultsAndConsent() {
        val t = flow(online); permit(t); val old = capture(t)
        val changed = online.copy(disclosureVersion = "text-only-v2")
        t.chooseProvider(changed)
        assertFalse(t.accept(OnDemandFixtures.response(old), 101))
        assertFalse(t.confirmOnlineUse(online, 1)); assertNull(t.clickTranslate(102))
        assertEquals(TranslationIssue.ONLINE_CONSENT_REQUIRED, t.issue)
        assertTrue(t.confirmOnlineUse(changed, 1)); val fresh = capture(t, 103)
        assertFalse(t.failed(old.id, 104)); assertSame(fresh, t.pending)
        t.chooseProvider(local); assertNull(t.pending); assertNull(t.snapshot)
        assertFalse(t.accept(OnDemandFixtures.response(fresh), 105))
        t.chooseProvider(changed); assertNull(t.clickTranslate(106))
    }
    @Test fun switchingDuringCaptureDiscardsLateCapture() {
        val t = flow(); val old = t.clickTranslate(100)!!
        t.chooseProvider(online)
        assertFalse(t.captured(old, OnDemandFixtures.page(page, 100), OnDemandFixtures.roi(0), 101))
        assertNull(t.snapshot); assertNull(t.pending)
    }
    @Test fun networkLossClearsWorkAndReconnectionWaitsForNewClick() {
        val t = flow(online); permit(t); val old = capture(t)
        t.setNetworkAvailable(false)
        assertNull(t.pending); assertNull(t.snapshot)
        assertFalse(t.accept(OnDemandFixtures.response(old), 101))
        assertNull(t.clickTranslate(102)); assertEquals(TranslationIssue.NETWORK_UNAVAILABLE, t.issue)
        t.setNetworkAvailable(true); t.tick(103); assertNull(t.pending)
        val fresh = capture(t, 104)
        assertEquals(online.model, fresh.model); assertEquals(online, t.provider)
    }
    @Test fun pauseMenuAndPageChangeClearTextAndRequireClickButStopAlsoRevokesSessionConsent() {
        for (reason in listOf(ClearReason.PAUSE, ClearReason.MENU, ClearReason.PAGE_CHANGE, ClearReason.STOP)) {
            val t = flow(online); permit(t); val old = capture(t)
            t.invalidate(reason)
            assertFalse(t.accept(OnDemandFixtures.response(old), 101))
            assertNull(t.pending); assertNull(t.snapshot)
            if (reason == ClearReason.STOP) {
                assertNull(t.clickTranslate(102)); assertEquals(TranslationIssue.ONLINE_CONSENT_REQUIRED, t.issue)
            } else assertNotNull(t.clickTranslate(102))
        }
    }
    @Test fun consentCannotCarryIntoAnotherSessionOrUnconfiguredProvider() {
        val t = flow(online); permit(t)
        t.observePage(page.copy(session = 2))
        assertNull(t.clickTranslate(100)); assertFalse(t.confirmOnlineUse(online, 1))
        assertTrue(t.confirmOnlineUse(online, 2))
        t.revokeOnlineUse(); assertNull(t.clickTranslate(101))
        t.chooseProvider(online.copy(configured = false))
        assertFalse(t.confirmOnlineUse(t.provider, 2))
        assertNull(t.clickTranslate(102)); assertEquals(TranslationIssue.PROVIDER_UNAVAILABLE, t.issue)
    }
    @Test fun moveZoomAndSameProviderDoNotRepeatCaptureAndWrongProvenanceIsRejected() {
        val t = flow(online); permit(t); val request = capture(t)
        t.chooseProvider(online); t.select(OnDemandFixtures.roi(2), 101)
        t.setTransform(MirrorTransform(4.0, 240.0, 120.0), 101)
        assertSame(request, t.pending); assertNull(t.clickTranslate(102))
        val response = OnDemandFixtures.response(request)
        assertFalse(t.accept(response.copy(model = local.model), 103))
        assertFalse(t.accept(response.copy(version = "wrong"), 103))
        assertTrue(t.accept(response, 104)); assertNull(t.pending)
        assertEquals("出发后不可退款", t.render(104).cards.single().chinese)
        t.select(OnDemandFixtures.roi(0), 105); assertNull(t.pending)
    }
}
