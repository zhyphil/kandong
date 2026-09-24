package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

class ProbeGateTest {
    @Test fun cancelBeforeQueuedWorkRejectsPublicationAndStartUntilDrain() {
        val gate = ProbeGate(); val token = gate.begin()!!
        assertTrue(gate.cancel(token))
        assertThrows(ProbeFailure::class.java) { token.checkpoint() }
        assertFalse(gate.accepts(token)); assertNull(gate.begin())
        gate.finish(token, false)
        assertNotNull(gate.begin())
    }
    @Test fun inFlightCancelFreezesAcceptedProgressAndRejectsLateStage() {
        val gate = ProbeGate(); val token = gate.begin()!!
        val accepted = mutableListOf("first")
        val lateCallback = { if (gate.accepts(token)) accepted.add("late") }
        gate.cancel(token)
        val cancelledSnapshot = accepted.toList()
        lateCallback()
        assertEquals(listOf("first"), cancelledSnapshot)
        assertEquals(cancelledSnapshot, accepted)
        assertFalse(gate.accepts(token)) // Same gate protects final AtomicFile stage publication.
        assertNull(gate.begin())
    }
    @Test fun oldTokensCannotCancelFinishOrPublishNewRun() {
        val gate = ProbeGate(); val old = gate.begin()!!
        gate.finish(old, false)
        val current = gate.begin()!!
        assertNotEquals(old.id, current.id)
        assertFalse(gate.cancel(old)); assertFalse(gate.accepts(old))
        gate.finish(old, true)
        assertTrue(gate.accepts(current)); assertFalse(gate.poisoned); assertNull(gate.begin())
    }
    @Test fun cleanupFailureClosesAllRemainingResourcesAndPoisonsReopen() {
        val gate = ProbeGate(); val token = gate.begin()!!; val cleanup = Cleanup()
        val calls = mutableListOf<String>()
        val outer = OnceResource(AutoCloseable { calls.add("outer") }, cleanup)
        val inner = OnceResource(AutoCloseable { calls.add("inner"); throw IllegalStateException() }, cleanup)
        try { try { token.checkpoint() } finally { inner.close() } } finally { outer.close() }
        inner.close(); outer.close()
        assertEquals(listOf("inner", "outer"), calls)
        assertEquals(2, cleanup.opened); assertEquals(2, cleanup.closeAttempts); assertEquals(1, cleanup.closed)
        gate.finish(token, cleanup.uncertain)
        assertTrue(gate.poisoned); assertNull(gate.begin())
    }
    @Test fun cancellationIsVisibleOnWorkerWithoutInterruptingIt() {
        val gate = ProbeGate(); val token = gate.begin()!!
        gate.cancel(token)
        var cancelled = false
        val worker = Thread { cancelled = token.cancelled.get() && !Thread.currentThread().isInterrupted }
        worker.start(); worker.join()
        assertTrue(cancelled)
        assertFalse(gate.cancel(token))
    }
}
