package com.kandong.ocrlab

/** Main-executor confined. Cancellation revokes publication, not resource ownership. */
internal class RunGate {
    class Token internal constructor()
    private var owner: Token? = null
    private var publish = false
    private var inFlight = false

    fun acquire(): Token? {
        if (owner != null) return null
        return Token().also { owner = it; publish = true }
    }
    fun owns(token: Token) = owner === token
    fun canPublish(token: Token) = owns(token) && publish
    fun beginTask(token: Token): Boolean {
        if (!canPublish(token) || inFlight) return false
        inFlight = true
        return true
    }
    fun endTask(token: Token): Boolean {
        if (!owns(token) || !inFlight) return false
        inFlight = false
        return true
    }
    fun cancel(token: Token): Boolean {
        if (!canPublish(token)) return false
        publish = false
        return true
    }
    fun release(token: Token): Boolean {
        if (!owns(token) || inFlight) return false
        owner = null
        publish = false
        return true
    }
}

/** Mark before disposing, so even a throwing disposer cannot double-release a resource. */
internal class OnceResource<T>(val value: T, private val dispose: (T) -> Unit) {
    private var closed = false
    fun close() {
        if (closed) return
        closed = true
        dispose(value)
    }
}
