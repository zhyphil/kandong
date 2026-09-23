package com.kandong.compat

internal enum class SessionMode { EXPANDED, COLLAPSED, MENU, TERMINAL }
internal class SessionUiState {
    var mode = SessionMode.EXPANDED; private set
    var returnTo = SessionMode.EXPANDED; private set
    val capturing get() = mode == SessionMode.EXPANDED
    val acceptsMenuCallbacks get() = mode == SessionMode.MENU
    fun collapse(): Boolean = change(SessionMode.COLLAPSED)
    fun resume(): Boolean = change(SessionMode.EXPANDED)
    fun menu(): Boolean {
        if (mode == SessionMode.TERMINAL || mode == SessionMode.MENU) return false
        returnTo = mode; mode = SessionMode.MENU; return true
    }
    fun closeMenu(): Boolean {
        if (mode != SessionMode.MENU) return false
        mode = returnTo; return true
    }
    fun end() { mode = SessionMode.TERMINAL }
    private fun change(next: SessionMode): Boolean {
        if (mode == SessionMode.TERMINAL || mode == next) return false
        mode = next; return true
    }
}
internal data class SessionSnapshot(val id: String? = null, val mode: SessionMode = SessionMode.TERMINAL)
/** Main-thread, same-process snapshots only. Never holds a consent Intent or projection token. */
internal object SessionBridge {
    var snapshot = SessionSnapshot(); private set
    private val listeners = mutableSetOf<(SessionSnapshot) -> Unit>()
    fun add(listener: (SessionSnapshot) -> Unit) { listeners.add(listener); listener(snapshot) }
    fun remove(listener: (SessionSnapshot) -> Unit) { listeners.remove(listener) }
    fun publish(value: SessionSnapshot) { snapshot = value; listeners.toList().forEach { it(value) } }
}
