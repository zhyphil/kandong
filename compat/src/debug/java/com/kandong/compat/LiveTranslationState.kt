package com.kandong.compat

/** A bounded in-flight request and one readable result have separate lifetimes.
 * Mutations and payload access are confined to the UI thread; workers only read current().
 */
internal class LiveTranslationState<T : Any>(private val dispose: (T) -> Unit = {}) {
    @Volatile var epoch = 0L; private set
    @Volatile var capturedAt = -1L; private set
    @Volatile var active = false; private set
    var result: T? = null; private set
    var candidate: T? = null; private set
    val displayed get() = result ?: candidate
    fun begin(): Long { cancel(); active=true; return epoch }
    // Takes ownership even when the frame is stale, so rejected frames cannot leak.
    fun captured(token: Long, now: Long, value: T): Boolean {
        if(!active || token != epoch || now < 0 || capturedAt >= 0) { dispose(value); return false }
        capturedAt=now; candidate=value; return true
    }
    fun current(token: Long, now: Long) = active && token == epoch &&
        (capturedAt < 0 || now >= capturedAt && now-capturedAt < PROCESSING_TIMEOUT)
    fun complete(token: Long, now: Long): Boolean {
        if(!current(token,now)) return false
        val next=candidate ?: return false
        val previous=result
        result=next; candidate=null
        active=false; epoch++; capturedAt=-1
        previous?.let(dispose)
        return true
    }
    // Cancel/failure/menu/collapse discard only unfinished work, preserving the last result.
    fun cancel() {
        active=false; epoch++; capturedAt=-1
        candidate?.let(dispose); candidate=null
    }
    // Explicit live mode or session termination also removes the readable result.
    fun invalidate() { cancel(); result?.let(dispose); result=null }
    companion object { const val PROCESSING_TIMEOUT = 60_000L }
}

/** Positive compositor witness, then a later visible frame where the witness is absent.
 * Producer timestamps are compared only with each other, never with a host clock.
 */
internal class FreshFrameGate {
    enum class Step { WITNESS, CLEAN, COMPLETE }
    var step = Step.WITNESS; private set
    private var witnessedAt = Long.MIN_VALUE
    fun observe(timestamp: Long, allMatch: Boolean, noneMatch: Boolean, pageVisible: Boolean = true): Step {
        when(step) {
            Step.WITNESS -> if(allMatch) { witnessedAt=timestamp; step=Step.CLEAN }
            Step.CLEAN -> if(timestamp > witnessedAt && noneMatch && pageVisible) step=Step.COMPLETE
            Step.COMPLETE -> Unit
        }
        return step
    }
}

internal object TranslationTextPolicy {
    private val pattern = Regex("password|passcode|verification code|one.time code|mot de passe|code de v[eé]rification|验证码|密码|银行卡|信用卡|身份证|\\bIBAN\\b|\\bCVV\\b|\\bCVC\\b|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|(?:\\d[ -]?){13,19}",RegexOption.IGNORE_CASE)
    fun sensitive(text: String) = pattern.containsMatchIn(text)
}
