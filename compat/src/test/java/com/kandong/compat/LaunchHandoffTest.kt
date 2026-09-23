package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class LaunchHandoffTest {
    @Test fun requiresSuccessfulStartAndResumedActivity() {
        val handoff = LaunchHandoff()
        assertFalse(handoff.consume(true, true)) // Merely reopening an active session stays here.
        val attempt = handoff.begin()
        assertFalse(handoff.consume(true, false))
        assertFalse(handoff.consume(true, true)) // Consent alone is not service readiness.
        assertTrue(handoff.complete(attempt, true))
        assertFalse(handoff.consume(false, true))
        assertFalse(handoff.consume(true, false))
        assertTrue(handoff.consume(true, true))
        assertFalse(handoff.consume(true, true))
    }
    @Test fun failedStartDoesNotNavigateAndCanBeRetried() {
        val handoff = LaunchHandoff()
        assertTrue(handoff.complete(handoff.begin(), false))
        assertFalse(handoff.consume(true, true))
        assertTrue(handoff.complete(handoff.begin(), true))
        assertTrue(handoff.consume(true, true))
    }
    @Test fun leavingOrEndingSessionDiscardsLateSuccess() {
        val handoff = LaunchHandoff()
        val attempt = handoff.begin()
        handoff.cancel()
        assertFalse(handoff.complete(attempt, true))
        assertFalse(handoff.consume(true, true))
    }
    @Test fun staleAttemptCannotCompleteNewStart() {
        val handoff = LaunchHandoff()
        val old = handoff.begin()
        val fresh = handoff.begin()
        assertFalse(handoff.complete(old, true))
        assertFalse(handoff.complete(old, false))
        assertFalse(handoff.consume(true, true))
        assertTrue(handoff.complete(fresh, true))
        assertTrue(handoff.consume(true, true))
        assertFalse(handoff.complete(fresh, true))
    }
}
