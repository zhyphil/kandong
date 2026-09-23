package com.kandong.app.domain

/** Main-thread owner; no Android objects or persistent storage. */
class Session(private val clock: () -> Long, val lifetimeMs: Long = 15_000) {
    var running = false
        private set
    var generation = 0L
        private set
    var snapshot: Snapshot? = null
        private set
    var selectedIndex = 0
        private set
    var candidates: List<NodeSnapshot> = emptyList()
        private set

    fun start() { invalidate(); running = true }
    fun stop() { running = false; invalidate() }
    fun invalidate(): Long {
        generation++
        snapshot = null
        candidates = emptyList()
        selectedIndex = 0
        return generation
    }

    fun accept(token: Long, context: ScreenContext, result: ReadResult, started: Long, panel: Box?): Boolean {
        if (!running || token != generation || result.rejected || clock() >= started + lifetimeMs) return false
        val selected = CandidateSelector.candidates(result.nodes, panel)
        if (selected.isEmpty()) return false
        candidates = java.util.Collections.unmodifiableList(ArrayList(selected))
        snapshot = Snapshot(context, result.nodes, started, started + lifetimeMs, result.truncated)
        selectedIndex = 0
        return true
    }

    fun current(context: ScreenContext?, token: Long = generation): NodeSnapshot? {
        val saved = snapshot ?: return null
        if (!running || token != generation || context != saved.context || clock() >= saved.expiresAt) {
            invalidate()
            return null
        }
        return candidates.getOrNull(selectedIndex)
    }

    fun move(delta: Int, context: ScreenContext?): NodeSnapshot? {
        current(context) ?: return null
        selectedIndex = (selectedIndex + delta).coerceIn(0, candidates.lastIndex)
        return current(context)
    }
}
