package com.kandong.modelprobe

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ProbeFailure(val code: String) : RuntimeException(code)
internal fun requireProbe(ok: Boolean, code: String) { if (!ok) throw ProbeFailure(code) }

/** Main-thread ownership; only Token.cancelled is read by the worker. */
class ProbeGate {
    class Token internal constructor(val id: String) {
        val cancelled = AtomicBoolean(false)
        fun checkpoint() { if (cancelled.get()) throw ProbeFailure("CANCELLED") }
    }
    private val ownerThread = Thread.currentThread()
    private var current: Token? = null
    var poisoned = false
        private set
    private fun owned() = check(Thread.currentThread() === ownerThread)
    fun begin(): Token? {
        owned()
        if (current != null || poisoned) return null
        return Token(UUID.randomUUID().toString()).also { current = it }
    }
    fun accepts(token: Token): Boolean { owned(); return current === token && !token.cancelled.get() }
    fun cancel(token: Token): Boolean {
        owned()
        if (current !== token || token.cancelled.get()) return false
        token.cancelled.set(true)
        return true
    }
    fun busy(): Boolean { owned(); return current != null }
    fun finish(token: Token, cleanupUncertain: Boolean) {
        owned()
        if (current !== token) return
        poisoned = poisoned || cleanupUncertain
        current = null
    }
}

/** Worker-only, idempotent ownership. A failed close is never retried. */
class OnceResource<T : AutoCloseable>(val value: T, private val cleanup: Cleanup) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var closed = false
    init { cleanup.opened++ }
    override fun close() {
        check(Thread.currentThread() === owner)
        if (closed) return
        closed = true
        cleanup.closeAttempts++
        try { value.close(); cleanup.closed++ } catch (_: Throwable) { cleanup.uncertain = true }
    }
}
class Cleanup {
    var opened = 0
    var closeAttempts = 0
    var closed = 0
    var uncertain = false
}
