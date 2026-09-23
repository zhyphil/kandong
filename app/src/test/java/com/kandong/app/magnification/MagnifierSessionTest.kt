package com.kandong.app.magnification

import org.junit.Assert.*
import org.junit.Test

class MagnifierSessionTest {
    private val idle = MagnifierObservation(MagnifierMode.FULLSCREEN, 1f, false, null)
    private val window = MagnifierObservation(MagnifierMode.WINDOW, 2f, true, SourceBounds(10, 20, 110, 120))
    private fun pending(): MagnifierSession = MagnifierSession().apply { begin(idle); requestResult(generation, true) }
    private fun active(): MagnifierSession = pending().apply { observe(generation, window) }

    @Test fun existingUserMagnificationAndUnknownAreUntouched() {
        val session = MagnifierSession()
        assertNull(session.begin(window))
        assertNull(session.begin(window.copy(mode = MagnifierMode.FULLSCREEN)))
        assertNull(session.begin(null))
        assertFalse(session.running)
        assertFalse(session.mayReset(window))
    }
    @Test fun acceptedRequestNeedsObservedWindowAndSource() {
        val session = pending()
        assertFalse(session.active)
        session.observe(session.generation, window.copy(source = null))
        assertFalse(session.active)
        session.observe(session.generation, window)
        assertTrue(session.active)
        assertEquals(window.source, session.source)
    }
    @Test fun observationBeforeAcceptanceCannotClaimOwnership() {
        val session = MagnifierSession(); val token = session.begin(idle)!!
        session.observe(token, window)
        assertFalse(session.active)
        session.requestResult(token, true); session.observe(token, window)
        assertTrue(session.active)
    }
    @Test fun rejectedRequestDoesNotReset() {
        val session = MagnifierSession(); val token = session.begin(idle)!!
        session.requestResult(token, false)
        assertFalse(session.running); assertFalse(session.mayReset(window))
        session.observe(token, window); assertFalse(session.active)
    }
    @Test fun timeoutRetainsTrackingForLateActivation() {
        val session = pending(); val token = session.generation
        session.timeout(token); session.observe(token, idle)
        assertTrue(session.running); assertFalse(session.active)
        session.observe(token, window)
        assertTrue(session.mayReset(window)); assertNull(session.source)
    }
    @Test fun stopBeforeRequestResultDoesNotLoseCleanup() {
        val session = MagnifierSession(); val token = session.begin(idle)!!
        session.stop(); session.requestResult(token, true); session.observe(token, window)
        assertFalse(session.active); assertTrue(session.mayReset(window))
    }
    @Test fun resetFailureKeepsRetryPathAndClearsGeometry() {
        val session = active(); session.stop(); session.resetResult(false)
        assertTrue(session.running); assertNull(session.source)
        assertTrue(session.mayReset(window))
        session.resetResult(true); session.observe(session.generation, idle)
        assertFalse(session.running)
    }
    @Test fun foreignModeOrScaleRelinquishesWithoutResetting() {
        listOf(window.copy(mode = MagnifierMode.FULLSCREEN), window.copy(scale = 5f), window.copy(scale = 1f)).forEach {
            val session = active(); session.observe(session.generation, it)
            assertFalse(session.running); assertFalse(session.mayReset(it))
        }
    }
    @Test fun userMovementIsExpectedButDeactivationRelinquishes() {
        val session = active(); val moved = window.copy(source = SourceBounds(20, 30, 120, 130))
        session.observe(session.generation, moved)
        assertTrue(session.active); assertEquals(moved.source, session.source)
        session.observe(session.generation, idle)
        assertFalse(session.running)
    }
    @Test fun staleCallbacksAndTimeoutsDoNotRestartSession() {
        val session = active(); val token = session.generation
        session.stop(); session.resetResult(true); session.observe(token, idle)
        session.begin(idle)
        session.observe(token, window); session.requestResult(token, true); session.timeout(token)
        assertFalse(session.active); assertEquals(MagnifierSession.Phase.REQUESTING, session.phase)
    }
    @Test fun api33FullscreenAvailableRegionAtOneXIsNotActiveZoom() {
        assertNotNull(MagnifierSession().begin(idle.copy(activated = null, source = window.source)))
        assertNotNull(MagnifierSession().begin(idle.copy(activated = null)))
    }
    @Test fun api33RememberedWindowScaleDoesNotPreventConfirmedReset() {
        val session = active(); session.stop(); session.resetResult(true)
        session.observe(session.generation, window.copy(activated = null, source = null))
        assertFalse(session.running)
    }
    @Test fun unreadableActiveStateStopsFurtherControlAndDropsGeometry() {
        val session = active(); session.observe(session.generation, null)
        assertFalse(session.active); assertTrue(session.running); assertNull(session.source)
    }
}
