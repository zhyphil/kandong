package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class LiveTranslationStateTest {
    @Test fun explicitBeginIsRequiredAndLateResultsCannotRevive() {
        val s=LiveTranslationState()
        assertFalse(s.current(0,1))
        val first=s.begin(); assertTrue(s.captured(first,100))
        assertTrue(s.current(first,59999))
        s.invalidate(); assertFalse(s.current(first,101))
        val next=s.begin(); assertFalse(s.captured(first,102))
        assertTrue(s.captured(next,102)); assertTrue(s.current(next,103))
    }
    @Test fun pageExpiresAtBoundAndClockCannotGoBackwards() {
        val s=LiveTranslationState(); val token=s.begin(); s.captured(token,100)
        assertFalse(s.current(token,99)); assertTrue(s.current(token,60099))
        assertFalse(s.current(token,60100))
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
    @Test fun sensitiveEnglishFrenchChineseAndAccountDataStayLocal() {
        listOf("Your password","Mot de passe","请输入验证码","a@example.com","1234 5678 9012 3456").forEach {
            assertTrue(TranslationTextPolicy.sensitive(it))
        }
        assertFalse(TranslationTextPolicy.sensitive("The museum closes at 18:00."))
    }
    @Test fun oneClickKeepsItsSnapshotDespiteLaterFramesAndRegionChanges() {
        val s=LiveTranslationState(); val token=s.begin()
        assertFalse(s.showingSnapshot(100))
        assertTrue(s.captured(token,100))
        assertFalse(s.captured(token,200))
        assertEquals(100L,s.capturedAt)
        assertTrue(s.showingSnapshot(200))
        assertTrue(s.current(token,200))
        s.invalidate()
        assertFalse(s.showingSnapshot(201))
        assertFalse(s.current(token,201))
        val next=s.begin()
        assertFalse(s.showingSnapshot(202))
        assertFalse(s.captured(token,202))
        assertTrue(s.captured(next,202))
    }
}
