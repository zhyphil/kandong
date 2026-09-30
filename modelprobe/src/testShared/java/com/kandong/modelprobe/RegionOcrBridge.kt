package com.kandong.modelprobe

import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Process-scoped reservation. Cancellation never releases a native resource reservation. */
internal class RegionOcrSlot {
    @Volatile var poisoned = false
        private set
    private var running: Any? = null
    @Synchronized internal fun acquire(identity: Any): Boolean {
        if (poisoned || running != null) return false
        running = identity
        return true
    }
    @Synchronized internal fun finish(identity: Any, uncertain: Boolean) {
        check(running === identity)
        poisoned = poisoned || uncertain
        running = null
    }
    @Synchronized fun busy(): Boolean = running != null
}

/**
 * Owner/UI-thread delivery gate. Only Ticket's cancellation bit/mailbox cross threads; the
 * native runner keeps its separate controller, Guard and permits on the worker. Work and posted
 * deliveries hold only a weak subscriber. Cancellation also removes any queued evidence.
 */
internal class RegionOcrBridge<T>(
    private val slot: RegionOcrSlot,
    private val worker: ((() -> Unit) -> Unit),
    private val ui: ((() -> Unit) -> Unit),
    private val now: () -> Long,
    private val run: (Ticket) -> Evidence<T>
) {
    class Ticket internal constructor(internal val startedAtMillis: Long = 0) {
        private val cancelled = AtomicBoolean(false)
        private var message: Any? = null
        fun active(): Boolean = !cancelled.get()
        fun checkpoint() { if (!active()) throw CancellationException("VISUAL_CANCELLED") }
        @Synchronized internal fun cancel() { cancelled.set(true); message = null }
        @Synchronized internal fun offer(value: Any) { if (active()) message = value }
        @Synchronized internal fun take(): Any? = message.also { message = null }
    }
    data class Evidence<T>(val value: T, val acquiredAtMillis: Long, val ttlMillis: Long, val validatedAtMillis: Long)
    class UncertainCleanup : RuntimeException()
    private data class Delivery<T>(val evidence: Evidence<T>?, val error: String?)
    private val owner = Thread.currentThread()
    private var current: Ticket? = null
    private var displayed: Evidence<T>? = null
    private var lastNow: Long? = null // Sticky across cancel, page changes, expiry and retries.
    private var problem: String? = null
    private var disposed = false
    private fun own() = check(Thread.currentThread() === owner) { "BRIDGE_OWNER_THREAD" }
    private fun revoke() { current?.cancel(); current = null; displayed = null }
    private fun tick(): Long? {
        val time = try { now() } catch (_: Exception) { -1L }
        if (time < 0 || lastNow?.let { time < it } == true) {
            revoke(); problem = "CLOCK_INVALID"; return null
        }
        lastNow = time
        return time
    }
    fun start(): Boolean = start(run)
    /** The task must capture immutable page specifications and application assets only. */
    fun start(task: (Ticket) -> Evidence<T>): Boolean {
        own()
        if (disposed) return false
        if (slot.poisoned) { revoke(); problem = "CLEANUP_UNCERTAIN_RESTART"; return false }
        if (slot.busy()) return false
        val startedAt = tick() ?: return false
        val ticket = Ticket(startedAt)
        if (!slot.acquire(ticket)) return false
        revoke(); current = ticket; problem = null
        // Local copies are intentional: neither runnable owns this bridge or its UI subscriber.
        val reservation = slot
        val dispatch = ui
        val subscriber = WeakReference(this)
        try {
            worker {
                var uncertain = false
                val delivery = try {
                    ticket.checkpoint()
                    Delivery(task(ticket), null)
                } catch (_: UncertainCleanup) {
                    uncertain = true
                    Delivery<T>(null, "CLEANUP_UNCERTAIN_RESTART")
                } catch (_: LinkageError) {
                    Delivery<T>(null, "NATIVE_UNAVAILABLE")
                } catch (_: Throwable) {
                    Delivery<T>(null, if (ticket.active()) "OCR_LOCAL_FAILURE" else "CANCELLED")
                } finally {
                    // Runner has exited all cleanup scopes. An uncertain cleanup poisons the
                    // shared slot even if its Activity has already been destroyed.
                    reservation.finish(ticket, uncertain)
                }
                ticket.offer(delivery)
                dispatch { subscriber.get()?.deliver(ticket) ?: ticket.cancel() }
            }
        } catch (_: RuntimeException) {
            reservation.finish(ticket, false)
            revoke(); problem = "WORKER_UNAVAILABLE"; return false
        }
        return true
    }
    private fun eligible(e: Evidence<T>, time: Long): Boolean =
        e.acquiredAtMillis >= 0 && e.ttlMillis in 1L..60_000L &&
            e.acquiredAtMillis <= Long.MAX_VALUE - e.ttlMillis &&
            e.validatedAtMillis >= e.acquiredAtMillis && time >= e.validatedAtMillis &&
            time < e.acquiredAtMillis + e.ttlMillis
    private fun deliver(ticket: Ticket) {
        own()
        // Identity must be checked before consuming a callback's time or failure state.
        if (disposed || current !== ticket || !ticket.active()) { ticket.cancel(); return }
        @Suppress("UNCHECKED_CAST")
        val delivery = ticket.take() as? Delivery<T> ?: return
        val time = tick() ?: return
        if (delivery.error != null) { revoke(); problem = delivery.error; return }
        val e = delivery.evidence
        if (e == null || e.acquiredAtMillis < ticket.startedAtMillis || !eligible(e, time)) { revoke(); problem = "EXPIRED_OR_INVALID"; return }
        displayed = e
    }
    fun cancel() { own(); revoke(); problem = null }
    fun dispose() { own(); cancel(); disposed = true }
    fun value(): T? {
        own()
        if (disposed || current?.active() != true) { revoke(); return null }
        val time = tick() ?: return null
        val evidence = displayed ?: return null
        if (!eligible(evidence, time)) { revoke(); problem = "EXPIRED_OR_INVALID"; return null }
        return evidence.value
    }
    fun busy(): Boolean { own(); return slot.busy() }
    fun error(): String? { own(); return problem }
}
