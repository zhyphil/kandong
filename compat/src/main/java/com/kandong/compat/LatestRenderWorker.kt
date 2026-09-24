package com.kandong.compat

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/**
 * One running request, one replaceable pending request, and one undelivered completion.
 * Lifecycle calls and delivery callbacks use the same consumer thread. Backend work and cleanup
 * stay on the owner executor. A successful callback takes output ownership; dispose must not throw.
 */
internal class LatestRenderWorker<I : Any, O : Any>(
    private val executor: ExecutorService,
    private val delivery: Executor,
    private val factory: () -> Backend<I, O>,
    private val dispose: (O) -> Unit,
    private val success: (I, O) -> Unit,
    private val failure: () -> Unit
) {
    interface Backend<I, O> : AutoCloseable { fun render(input: I): O }
    private data class Request<I>(val epoch: Long, val input: I)
    // A failure carries no input/output pixels. Only a successful completion owns an output.
    private data class Completion<I, O>(val epoch: Long, val input: I?, val output: O?)
    private val lock = Any()
    private var epoch = 0L
    private var pending: Request<I>? = null
    private var completed: Completion<I, O>? = null
    private var deliveryScheduled = false
    private var scheduled = false
    private var release = false
    private var closed = false
    // Only touched by the single owner executor.
    private var backend: Backend<I, O>? = null
    @Volatile var resourcesOpen = false; private set
    @Volatile var cleanupFailures = 0L; private set

    fun submit(input: I) = synchronized(lock) {
        if (!closed) { pending = Request(epoch, input); schedule() }
    }
    /** Invalidates running/undelivered results immediately. Pause releases GL on its owner. */
    fun invalidate(releaseResources: Boolean = false) {
        val discarded = synchronized(lock) {
            if (closed) return
            epoch++; pending = null
            val old = completed; completed = null
            // Keep deliveryScheduled: its already queued callback owns no frame and may run empty.
            if (releaseResources) { release = true; schedule() }
            old
        }
        discarded?.output?.let(dispose)
    }
    fun close() {
        val discarded = synchronized(lock) {
            if (closed) return
            closed = true; epoch++; pending = null
            val old = completed; completed = null
            release = true; schedule()
            old
        }
        discarded?.output?.let(dispose)
    }
    private fun schedule() {
        if (!scheduled) { scheduled = true; executor.execute(::drain) }
    }
    private fun current(value: Long) = synchronized(lock) { !closed && value == epoch }
    private fun failureWaiting(value: Long) = synchronized(lock) {
        completed?.let { it.epoch == value && it.output == null } == true
    }
    private fun closeBackend() {
        val old = backend; backend = null
        if (old != null) {
            try { old.close(); resourcesOpen = false }
            catch (error: Exception) { cleanupFailures++; throw error }
        }
    }
    private fun offer(result: Completion<I, O>) {
        var dispatch = false
        val discarded = synchronized(lock) {
            if (closed || result.epoch != epoch ||
                completed?.let { it.epoch == epoch && it.output == null } == true) result
            else {
                val old = completed; completed = result
                if (!deliveryScheduled) { deliveryScheduled = true; dispatch = true }
                old
            }
        }
        discarded?.output?.let(dispose)
        if (dispatch) dispatchDelivery()
    }
    private fun dispatchDelivery() {
        try {
            // No per-frame closure: only the single slot retains pixels while the UI is busy.
            delivery.execute(::deliverLatest)
        } catch (_: RuntimeException) {
            // Rejected delivery cannot safely report failure to that same executor. Fail closed;
            // clear any owned completion and let the owner drain release its backend and shut down.
            synchronized(lock) { deliveryScheduled = false }
            close()
        }
    }
    private fun deliverLatest() {
        // Claiming the slot is the ownership transfer point. Lifecycle and callbacks are serialized
        // on the consumer thread, so a callback cannot race with pause after this claim.
        val result = synchronized(lock) { completed.also { completed = null } }
        try {
            if (result != null) {
                if (!current(result.epoch)) result.output?.let(dispose)
                else if (result.output != null) success(checkNotNull(result.input), result.output)
                else failure()
            }
        } finally {
            val again = synchronized(lock) {
                if (completed != null) true else { deliveryScheduled = false; false }
            }
            if (again) dispatchDelivery()
        }
    }
    private fun drain() {
        while (true) {
            var cleanup = false
            val request = synchronized(lock) {
                cleanup = release; release = false
                pending.also { pending = null }
            }
            if (cleanup) try { closeBackend() } catch (_: Exception) { /* Counted above. */ }
            if (request != null && current(request.epoch) && !failureWaiting(request.epoch)) {
                val result = try {
                    val worker = backend ?: factory().also { backend = it; resourcesOpen = true }
                    Completion(request.epoch, request.input, worker.render(request.input))
                } catch (_: Exception) {
                    try { closeBackend() } catch (_: Exception) { }
                    Completion<I, O>(request.epoch, null, null)
                }
                offer(result)
            }
            synchronized(lock) {
                if (pending == null && !release) {
                    scheduled = false
                    if (closed) executor.shutdown()
                    return
                }
            }
        }
    }
}
