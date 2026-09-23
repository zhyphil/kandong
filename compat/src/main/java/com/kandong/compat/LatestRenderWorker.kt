package com.kandong.compat

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/** One running request plus one replaceable pending request. No unbounded frame queue. */
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
    private val lock = Any()
    private var epoch = 0L
    private var pending: Request<I>? = null
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
    /** Invalidates both in-flight and already posted results. Pause also releases the GL context. */
    fun invalidate(releaseResources: Boolean = false) = synchronized(lock) {
        epoch++; pending = null
        if (releaseResources) { release = true; schedule() }
    }
    fun close() = synchronized(lock) {
        if (!closed) { closed = true; epoch++; pending = null; release = true; schedule() }
    }
    private fun schedule() {
        if (!scheduled) { scheduled = true; executor.execute(::drain) }
    }
    private fun current(value: Long) = synchronized(lock) { !closed && value == epoch }
    private fun closeBackend() {
        val old = backend; backend = null
        if (old != null) {
            try { old.close(); resourcesOpen = false }
            catch (error: Exception) { cleanupFailures++; throw error }
        }
    }
    private fun drain() {
        while (true) {
            var cleanup = false
            val request = synchronized(lock) {
                cleanup = release; release = false
                pending.also { pending = null }
            }
            if (cleanup) try { closeBackend() } catch (_: Exception) { /* Already invalidated. */ }
            if (request != null && current(request.epoch)) {
                try {
                    val worker = backend ?: factory().also { backend = it; resourcesOpen = true }
                    val output = worker.render(request.input)
                    if (!current(request.epoch)) dispose(output)
                    else delivery.execute {
                        if (current(request.epoch)) success(request.input, output) else dispose(output)
                    }
                } catch (_: Exception) {
                    try { closeBackend() } catch (_: Exception) { }
                    delivery.execute { if (current(request.epoch)) failure() }
                }
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
