package com.kandong.compat

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LatestRenderWorkerTest {
    private class Jobs : AbstractExecutorService() {
        val jobs = ArrayDeque<Runnable>()
        var stopped = false
        override fun execute(command: Runnable) { check(!stopped); jobs.add(command) }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return jobs.toMutableList().also { jobs.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && jobs.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
    private class Rig {
        val jobs = Jobs()
        val deliveries = ArrayDeque<Runnable>()
        val rendered = mutableListOf<Int>()
        val shown = mutableListOf<Int>()
        val disposed = mutableListOf<Int>()
        var closes = 0
        var opens = 0
        var errors = 0
        var duringRender: (Int) -> Unit = {}
        val worker = LatestRenderWorker<Int, Int>(jobs, Executor { deliveries.add(it) }, {
            opens++
            object : LatestRenderWorker.Backend<Int, Int> {
                override fun render(input: Int): Int { rendered += input; duringRender(input); return input }
                override fun close() { closes++ }
            }
        }, { disposed += it }, { _, output -> shown += output }, { errors++ })
        fun deliver() { while (deliveries.isNotEmpty()) deliveries.removeFirst().run() }
    }
    @Test fun pendingFramesAreReplacedInsteadOfQueued() {
        val r = Rig()
        r.worker.submit(1); r.worker.submit(2); r.worker.submit(3)
        assertEquals(1, r.jobs.jobs.size)
        r.jobs.drain(); r.deliver()
        assertEquals(listOf(3), r.rendered); assertEquals(listOf(3), r.shown)
    }
    @Test fun cropChangeDiscardsBothInFlightAndAlreadyPostedResults() {
        val r = Rig()
        r.duringRender = { if (it == 1) { r.worker.invalidate(); r.worker.submit(2) } }
        r.worker.submit(1); r.jobs.drain()
        assertEquals(listOf(1), r.disposed)
        r.worker.invalidate(); r.deliver()
        assertTrue(r.shown.isEmpty()); assertEquals(listOf(1,2), r.disposed)
    }
    @Test fun pauseClosesOnOwnerQueueAndResumedSessionCanRecreate() {
        val r = Rig()
        r.worker.submit(1); r.jobs.drain()
        r.worker.invalidate(true)
        assertEquals(0, r.closes) // Caller cannot close GL while worker might be rendering.
        r.jobs.drain(); r.deliver()
        assertEquals(1, r.closes); assertTrue(r.shown.isEmpty())
        r.worker.submit(2); r.jobs.drain(); r.deliver()
        assertEquals(2, r.opens); assertEquals(listOf(2), r.shown)
    }
    @Test fun terminalCloseRejectsQueuedAndLateResultsAndShutsDown() {
        val r = Rig()
        r.worker.submit(1); r.jobs.drain()
        r.worker.submit(2); r.worker.close(); r.worker.close(); r.worker.submit(3)
        r.jobs.drain(); r.deliver()
        assertEquals(listOf(1), r.rendered); assertTrue(r.shown.isEmpty())
        assertEquals(listOf(1), r.disposed); assertEquals(1, r.closes); assertTrue(r.jobs.isShutdown)
    }
    @Test fun rendererFailureClosesBackendAndReportsOnceButNotAfterPause() {
        val r = Rig(); r.duringRender = { error("Device render failed") }
        r.worker.submit(1); r.jobs.drain(); r.deliver()
        assertEquals(1, r.errors); assertEquals(1, r.closes)
        r.worker.submit(2); r.jobs.drain(); r.worker.invalidate(true); r.deliver(); r.jobs.drain()
        assertEquals(1, r.errors); assertEquals(2, r.closes)
    }
}
