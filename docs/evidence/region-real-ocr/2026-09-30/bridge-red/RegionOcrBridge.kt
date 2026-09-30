package com.kandong.modelprobe

/** Contract stub for red behavioral checks; implementation follows in the bounded worker phase. */
internal class RegionOcrSlot { var poisoned = false }
internal class RegionOcrBridge<T>(
    private val slot: RegionOcrSlot,
    private val worker: ((() -> Unit) -> Unit),
    private val ui: ((() -> Unit) -> Unit),
    private val now: () -> Long,
    private val run: (Ticket) -> Evidence<T>
) {
    class Ticket { fun active(): Boolean = true }
    data class Evidence<T>(val value: T, val acquiredAtMillis: Long, val ttlMillis: Long, val validatedAtMillis: Long)
    class UncertainCleanup : RuntimeException()
    fun start(): Boolean = false
    fun cancel() {}
    fun dispose() {}
    fun value(): T? = null
    fun busy(): Boolean = false
    fun error(): String? = null
}
