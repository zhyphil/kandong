package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class LiveTranslationStateTest {
    @Test fun snapshotReadingDoesNotDisappearAfterOneMinute() {
        val s=LiveTranslationState<String>(); val token=s.begin(); s.captured(token,100,"page")
        assertTrue(s.complete(token,200))
        assertFalse(s.current(token,120_100))
        assertEquals("page",s.displayed)
        s.invalidate()
        assertFalse(s.displayed != null)
    }
    @Test fun explicitBeginIsRequiredAndLateResultsCannotRevive() {
        val s=LiveTranslationState<String>()
        assertFalse(s.current(0,1))
        val first=s.begin(); assertTrue(s.captured(first,100,"page"))
        assertTrue(s.current(first,59999))
        s.invalidate(); assertFalse(s.current(first,101))
        val next=s.begin(); assertFalse(s.captured(first,102,"page"))
        assertTrue(s.captured(next,102,"page")); assertTrue(s.current(next,103))
    }
    @Test fun pendingRequestTimesOutButReadingLifetimeIsIndependent() {
        val s=LiveTranslationState<String>(); val token=s.begin(); s.captured(token,100,"page")
        assertFalse(s.current(token,99)); assertTrue(s.current(token,60099))
        assertFalse(s.current(token,60100))
    }
    @Test fun failedOrCancelledReplacementKeepsPreviousResultAndDisposesCandidate() {
        val disposed=mutableListOf<String>()
        val s=LiveTranslationState<String> { disposed+=it }
        val first=s.begin(); s.captured(first,100,"old"); s.complete(first,200)
        val next=s.begin(); s.captured(next,500_000,"new")
        assertEquals("old",s.displayed)
        assertFalse(s.complete(next,560_000))
        s.cancel()
        assertEquals("old",s.displayed)
        assertEquals(listOf("new"),disposed)
        assertFalse(s.complete(next,500_001))
    }
    @Test fun successfulReplacementIsAtomicAndLateCallbacksCannotReplaceIt() {
        val disposed=mutableListOf<String>()
        val s=LiveTranslationState<String> { disposed+=it }
        val first=s.begin(); s.captured(first,100,"old"); s.complete(first,200)
        val next=s.begin(); s.captured(next,800_000,"new")
        assertEquals("old",s.displayed)
        assertTrue(s.complete(next,800_100))
        assertEquals("new",s.displayed)
        assertEquals(listOf("old"),disposed)
        assertFalse(s.complete(first,800_101))
        assertFalse(s.captured(next,800_102,"late"))
        assertEquals(listOf("old","late"),disposed)
        assertEquals("new",s.displayed)
    }
    @Test fun pauseKeepsOnlyCompletedDataAndExplicitLiveOrSessionEndClearsEverything() {
        val disposed=mutableListOf<String>()
        val s=LiveTranslationState<String> { disposed+=it }
        val first=s.begin(); s.captured(first,10,"first"); s.complete(first,20)
        repeat(3) { s.cancel(); assertEquals("first",s.displayed) }
        val pending=s.begin(); s.captured(pending,100,"pending")
        s.invalidate(); s.invalidate()
        assertNull(s.displayed); assertNull(s.candidate); assertNull(s.result)
        assertFalse(s.current(pending,101)); assertFalse(s.complete(pending,101))
        assertEquals(listOf("pending","first"),disposed)
    }
    @Test fun cancellingInitialAttemptDoesNotRetainAnUnfinishedSnapshot() {
        val s=LiveTranslationState<String>()
        val token=s.begin(); s.captured(token,100,"draft")
        assertEquals("draft",s.displayed)
        s.cancel(); assertNull(s.displayed)
    }
    @Test fun cleanFrameNeedsPositiveWitnessAndIncreasingProducerTimestamp() {
        val gate=FreshFrameGate()
        assertEquals(FreshFrameGate.Step.WITNESS,gate.observe(90,false,true))
        assertEquals(FreshFrameGate.Step.CLEAN,gate.observe(100,true,false))
        assertEquals(FreshFrameGate.Step.CLEAN,gate.observe(100,false,true))
        assertEquals(FreshFrameGate.Step.CLEAN,gate.observe(99,false,true))
        assertEquals(FreshFrameGate.Step.CLEAN,gate.observe(101,false,false))
        assertEquals(FreshFrameGate.Step.COMPLETE,gate.observe(102,false,true))
    }
    @Test fun removedWitnessOnBlackFrameMustNotPublishSnapshot() {
        val gate=FreshFrameGate()
        assertEquals(FreshFrameGate.Step.CLEAN,gate.observe(100,true,false))
        assertEquals(FreshFrameGate.Step.CLEAN,gate.observe(101,false,true,pageVisible=false))
        assertEquals(FreshFrameGate.Step.CLEAN,gate.observe(102,false,true,pageVisible=false))
        assertEquals(FreshFrameGate.Step.COMPLETE,gate.observe(103,false,true,pageVisible=true))
    }
    @Test fun sensitiveEnglishFrenchChineseAndAccountDataStayLocal() {
        listOf("Your password","Mot de passe","请输入验证码","a@example.com","1234 5678 9012 3456").forEach {
            assertTrue(TranslationTextPolicy.sensitive(it))
        }
        assertFalse(TranslationTextPolicy.sensitive("The museum closes at 18:00."))
    }
    @Test fun oneClickKeepsItsSnapshotDespiteLaterFramesAndRegionChanges() {
        val s=LiveTranslationState<String>(); val token=s.begin()
        assertFalse(s.displayed != null)
        assertTrue(s.captured(token,100,"page"))
        assertFalse(s.captured(token,200,"page"))
        assertEquals(100L,s.capturedAt)
        assertTrue(s.displayed != null)
        assertTrue(s.current(token,200))
        s.invalidate()
        assertFalse(s.displayed != null)
        assertFalse(s.current(token,201))
        val next=s.begin()
        assertFalse(s.displayed != null)
        assertFalse(s.captured(token,202,"page"))
        assertTrue(s.captured(next,202,"page"))
    }
}
