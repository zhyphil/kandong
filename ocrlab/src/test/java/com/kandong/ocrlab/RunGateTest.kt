package com.kandong.ocrlab

import org.junit.Assert.*
import org.junit.Test

class RunGateTest {
    @Test fun cancelBlocksPublicationAndRestartUntilTaskCleanup() {
        val gate = RunGate()
        val first = gate.acquire()!!
        assertTrue(gate.beginTask(first))
        assertTrue(gate.cancel(first))
        assertFalse(gate.canPublish(first))
        assertNull(gate.acquire())
        assertFalse(gate.release(first))
        assertTrue(gate.endTask(first))
        assertTrue(gate.release(first))
        val second = gate.acquire()!!
        assertTrue(gate.beginTask(second))
        assertFalse(gate.canPublish(first))
        assertFalse(gate.cancel(first))
        assertFalse(gate.endTask(first))
        assertFalse(gate.release(first))
        assertTrue(gate.canPublish(second))
        assertFalse(gate.release(second))
        assertTrue(gate.endTask(second))
        assertTrue(gate.release(second))
    }

    @Test fun successfulOwnerRemainsExclusiveUntilExplicitRelease() {
        val gate = RunGate()
        val owner = gate.acquire()!!
        assertTrue(gate.beginTask(owner))
        assertFalse(gate.beginTask(owner))
        assertNull(gate.acquire())
        assertTrue(gate.endTask(owner))
        assertTrue(gate.canPublish(owner))
        assertNull(gate.acquire())
        assertTrue(gate.release(owner))
        assertFalse(gate.canPublish(owner))
        assertNotNull(gate.acquire())
    }

    @Test fun lateCompletionReleasesResourceExactlyOnceEvenAfterCancel() {
        val gate = RunGate()
        val owner = gate.acquire()!!
        var releases = 0
        val resource = OnceResource("bitmap") { releases++ }
        gate.beginTask(owner)
        gate.cancel(owner)
        assertEquals(0, releases)
        resource.close()
        resource.close()
        gate.endTask(owner)
        gate.release(owner)
        assertEquals(1, releases)
    }

    @Test fun throwingCleanupIsNeverRetriedAgainstAnotherOwner() {
        var calls = 0
        val resource = OnceResource(Unit) { calls++; error("synthetic failure") }
        try { resource.close() } catch (_: IllegalStateException) { }
        resource.close()
        assertEquals(1, calls)
    }

    @Test fun cancelBeforeTaskStartsPreventsQueuedWorkFromBeginning() {
        val gate = RunGate()
        val owner = gate.acquire()!!
        assertTrue(gate.cancel(owner))
        assertFalse(gate.beginTask(owner))
        assertTrue(gate.release(owner))
        val newOwner = gate.acquire()!!
        assertFalse(gate.beginTask(owner))
        assertTrue(gate.beginTask(newOwner))
    }
}
