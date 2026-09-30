package com.kandong.compat

/** Page identity is independent of region, zoom and pan. No screen text in diagnostics. */
internal class LiveTranslationState {
    @Volatile var epoch = 0L; private set
    @Volatile var capturedAt = -1L; private set
    @Volatile var active = false; private set
    fun begin(): Long { epoch++; capturedAt=-1; active=true; return epoch }
    fun captured(token: Long, now: Long): Boolean {
        // A click owns one immutable snapshot. Later frames cannot replace it,
        // nor extend its lifetime while OCR or a translation request is pending.
        if(!active || token != epoch || now < 0 || capturedAt >= 0) return false
        capturedAt=now; return true
    }
    fun showingSnapshot(now: Long) = capturedAt >= 0 && current(epoch, now)
    fun current(token: Long, now: Long) = active && token == epoch &&
        (capturedAt < 0 || now >= capturedAt && now-capturedAt < TTL)
    fun invalidate() { active=false; epoch++; capturedAt=-1 }
    companion object { const val TTL = 60_000L }
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
