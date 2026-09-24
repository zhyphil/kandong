package com.kandong.compat

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** A slow UI must not retain every completed frame and its source bytes. */
class LatestRenderDeliveryTest {
    private class Jobs : AbstractExecutorService() {
        val jobs = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); jobs.add(command) }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow() = jobs.toMutableList().also { jobs.clear(); stopped = true }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && jobs.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
    private class Rig {
        val jobs = Jobs()
        val deliveries = ArrayDeque<Runnable>()
        val disposed = mutableListOf<Int>()
        val shown = mutableListOf<Int>()
        var errors = 0
        var fail = false
        var closes = 0
        var throwClose = false
        var duringRender: () -> Unit = {}
        var rejectDelivery = false
        var throwSuccess = false
        var throwFailure = false
        val worker = LatestRenderWorker<Int, Int>(jobs, Executor { if (rejectDelivery) throw java.util.concurrent.RejectedExecutionException(); deliveries.add(it) }, {
            object : LatestRenderWorker.Backend<Int, Int> {
                override fun render(input: Int): Int { check(!fail); duringRender(); return input }
                override fun close() { closes++; if (throwClose) error("cleanup failure") }
            }
        }, { disposed += it }, { _, output -> shown += output; if (throwSuccess) error("consumer failure") }, { errors++; if (throwFailure) error("consumer failure") })
        fun render(value: Int) { worker.submit(value); jobs.drain() }
        fun deliver() { while (deliveries.isNotEmpty()) deliveries.removeFirst().run() }
    }
    @Test fun slowUiKeepsOnlyNewestUndeliveredFrameAndOneCallback() {
        val r = Rig()
        repeat(100) { r.render(it) }
        assertEquals(1, r.deliveries.size)
        assertEquals((0 until 99).toList(), r.disposed)
        r.deliver()
        assertEquals(listOf(99), r.shown)
    }
    @Test fun pauseDropsUndeliveredPixelsWithoutWaitingForUiCallback() {
        val r = Rig(); r.render(1)
        r.worker.invalidate(true)
        assertEquals(listOf(1), r.disposed)
        r.jobs.drain(); r.deliver()
        assertTrue(r.shown.isEmpty())
        assertEquals(listOf(1), r.disposed)
    }
    @Test fun closeDropsUndeliveredPixelsExactlyOnce() {
        val r = Rig(); r.render(1)
        r.worker.close(); r.worker.close()
        assertEquals(listOf(1), r.disposed)
        r.jobs.drain(); r.deliver()
        assertTrue(r.shown.isEmpty()); assertEquals(listOf(1), r.disposed)
        assertTrue(r.jobs.isShutdown)
    }
    @Test fun renderFailureReplacesOlderFrameButIsNotHiddenByLaterSuccess() {
        val r = Rig(); r.render(1)
        r.fail = true; r.render(2)
        r.fail = false; r.render(3)
        assertEquals(1, r.deliveries.size)
        r.deliver()
        assertTrue(r.shown.isEmpty()); assertEquals(1, r.errors)
        assertEquals(listOf(1), r.disposed) // Rendering pauses while the failure awaits delivery.
    }
    @Test fun epochChangePreventsQueuedErrorAndShowsNewFrame() {
        val r = Rig(); r.fail = true; r.render(1)
        r.worker.invalidate(true); r.fail = false; r.render(2)
        r.deliver()
        assertEquals(0, r.errors); assertEquals(listOf(2), r.shown)
    }

    @Test fun rejectedDeliveryDisposesAndClosesWithoutRetryingDeadConsumer() {
        val r = Rig(); r.rejectDelivery = true; r.render(1)
        assertEquals(listOf(1), r.disposed); assertEquals(1, r.closes)
        assertTrue(r.jobs.isShutdown); assertTrue(r.deliveries.isEmpty())
        r.worker.invalidate(true); r.worker.close(); r.worker.submit(2)
        assertEquals(listOf(1), r.disposed); assertEquals(0, r.errors)
    }
    @Test fun repeatedEpochChangesReuseAlreadyQueuedCallback() {
        val r = Rig()
        repeat(30) { r.render(it); r.worker.invalidate(true); r.jobs.drain() }
        r.render(30)
        assertEquals(1, r.deliveries.size)
        r.deliver()
        assertEquals((0 until 30).toList(), r.disposed); assertEquals(listOf(30), r.shown)
    }
    @Test fun callbackExceptionDoesNotRecycleTransferredFrameOrStrandNextDelivery() {
        val r = Rig(); r.throwSuccess = true; r.render(1)
        assertThrows(IllegalStateException::class.java) { r.deliver() }
        assertTrue(r.disposed.isEmpty()); assertEquals(listOf(1), r.shown)
        r.throwSuccess = false; r.render(2); r.deliver()
        assertEquals(listOf(1,2), r.shown)
        r.fail = true; r.throwFailure = true; r.render(3)
        assertThrows(IllegalStateException::class.java) { r.deliver() }
        r.fail = false; r.throwFailure = false; r.render(4); r.deliver()
        assertEquals(listOf(1,2,4), r.shown)
        assertEquals(1, r.errors)
    }

    @Test fun closeDuringRenderDisposesLateOutputAndCleansUpOnOwner() {
        val r = Rig(); r.duringRender = { r.worker.close(); assertEquals(0, r.closes) }
        r.render(1); r.deliver()
        assertEquals(listOf(1), r.disposed); assertTrue(r.shown.isEmpty())
        assertEquals(1, r.closes); assertTrue(r.jobs.isShutdown)
        r.worker.invalidate(true) // Terminal state must not schedule on the stopped executor.
    }
    @Test fun cleanupErrorIsCountedWithoutKeepingAnyUndeliveredPixels() {
        val r = Rig(); r.render(1); r.throwClose = true
        r.worker.close(); r.jobs.drain(); r.deliver()
        assertEquals(1L, r.worker.cleanupFailures)
        assertEquals(listOf(1), r.disposed); assertTrue(r.shown.isEmpty())
        assertTrue(r.jobs.isShutdown)
    }
}
