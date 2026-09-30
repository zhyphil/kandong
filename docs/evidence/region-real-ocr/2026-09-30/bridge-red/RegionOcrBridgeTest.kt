package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

class RegionOcrBridgeTest {
    private var time = 100L
    private val work = arrayListOf<() -> Unit>()
    private val deliveries = arrayListOf<() -> Unit>()
    private val slot = RegionOcrSlot()
    private var calls = 0
    private fun bridge(body: (RegionOcrBridge.Ticket) -> RegionOcrBridge.Evidence<String> = {
        calls++; RegionOcrBridge.Evidence("page-$calls",100,60_000,time)
    }) = RegionOcrBridge(slot,{ work.add(it) },{ deliveries.add(it) },{ time },body)
    private fun work() = work.removeAt(0).invoke()
    private fun deliver(index:Int=0) = deliveries.removeAt(index).invoke()

    @Test fun explicitStartIsAsyncAndOneRunningWithZeroQueuedRetries() {
        val b=bridge(); assertEquals(0,calls); assertTrue(b.start())
        repeat(100) { assertFalse(b.start()) }
        assertEquals(0,calls); assertEquals(1,work.size); assertTrue(b.busy())
        work(); assertEquals(1,calls); assertNull(b.value()); assertFalse(b.busy())
        deliver(); assertEquals("page-1",b.value())
    }
    @Test fun cancellationImmediatelyClearsAndDoesNotReleaseNativeSlotUntilReturn() {
        lateinit var b:RegionOcrBridge<String>
        b=bridge { t -> calls++; assertTrue(t.active()); b.cancel(); assertFalse(t.active())
            assertFalse(b.start()); RegionOcrBridge.Evidence("cancelled",100,60000,time) }
        assertTrue(b.start()); work(); deliver(); assertNull(b.value())
        assertTrue(b.start()); assertEquals(1,work.size)
    }
    @Test fun queuedOldSuccessCannotReplaceOrRevokeNewerDisplay() {
        val b=bridge(); assertTrue(b.start()); work()
        assertTrue(b.start()); work(); deliver(1)
        assertEquals("page-2",b.value()); time=99; deliver(0); time=100
        assertEquals("page-2",b.value())
    }
    @Test fun queuedOldFailureCannotChangeNewerResult() {
        val b=bridge { calls++; if(calls==1) throw IllegalStateException("old")
            RegionOcrBridge.Evidence("new",100,60000,time) }
        assertTrue(b.start()); work(); assertTrue(b.start()); work(); deliver(1); deliver(0)
        assertEquals("new",b.value()); assertNull(b.error())
    }
    @Test fun delayedDeliveryUsesAcquisitionDeadlineAndNeverRenews() {
        val b=bridge(); assertTrue(b.start()); work(); time=60099; deliver()
        assertEquals("page-1",b.value()); time=60100; assertNull(b.value())
        time=200; assertNull(b.value())
    }
    @Test fun alreadyExpiredDeliveryIsRejectedAndCannotRevive() {
        val b=bridge(); assertTrue(b.start()); work(); time=60100; deliver(); assertNull(b.value())
        time=101; assertNull(b.value())
    }
    @Test fun cancellationClearsDisplayedValueAndLateCallbacksStayCleared() {
        val b=bridge(); assertTrue(b.start()); work(); deliver(); assertNotNull(b.value())
        b.cancel(); assertNull(b.value()); assertEquals(1,calls)
        assertTrue(b.start()); work(); b.cancel(); deliver(); assertNull(b.value())
    }
    @Test fun sharedSlotPreventsConcurrentWorkAcrossSurfaceReplacement() {
        val a=bridge(); val b=bridge(); assertTrue(a.start()); a.dispose(); assertFalse(b.start())
        work(); deliver(); assertNull(a.value()); assertFalse(a.start()); assertTrue(b.start())
    }
    @Test fun cleanupUncertaintyPoisonsSharedSlotAcrossReplacement() {
        val a=bridge { throw RegionOcrBridge.UncertainCleanup() }; val b=bridge()
        assertTrue(a.start()); work(); deliver(); assertTrue(slot.poisoned)
        assertNull(a.value()); assertNotNull(a.error()); assertFalse(b.start())
    }
    @Test fun workerValidationTimeIsNotAllowedToMoveBackwardsAtDelivery() {
        val b=bridge { RegionOcrBridge.Evidence("future",100,60000,200) }
        assertTrue(b.start()); work(); time=199; deliver(); assertNull(b.value())
    }
}
