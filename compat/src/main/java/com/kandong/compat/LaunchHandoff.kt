package com.kandong.compat

/** One explicit, successful start may leave the introduction once. Main-thread only. */
internal class LaunchHandoff {
    private var sequence = 0
    private var pending: Int? = null
    private var ready = false

    fun begin(): Int {
        ready = false
        return (++sequence).also { pending = it }
    }
    fun complete(attempt: Int, success: Boolean): Boolean {
        if (pending != attempt) return false
        if (success) ready = true else cancel()
        return true
    }
    fun consume(resumed: Boolean, sessionActive: Boolean): Boolean {
        if (!ready || !resumed || !sessionActive) return false
        cancel()
        return true
    }
    fun cancel() { pending = null; ready = false }
}
