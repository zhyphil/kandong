package com.kandong.capturelab

/** Only enums/counts leave the sampler. No captured pixels or page text are retained. */
internal enum class Phase(val code: Int, val label: String) {
    BASELINE(1, "公开基线"), COVER(2, "普通悬浮遮挡"), SECURE_COVER(3, "安全悬浮遮挡"),
    HIDE(4, "隐藏后新画面"), RESTORE(5, "恢复遮挡"), SECURE_ACTIVITY(6, "本页安全窗口"),
    PUBLIC_RETURN(7, "解除安全后新画面")
}

internal enum class Patch { BASE, COVER, BLACK, OTHER }
internal enum class Verdict(val code: Int, val label: String) {
    CURRENT_CONFIRMED(1, "当前帧已确认"), BLACK_OBSERVED(2, "观察到黑色遮蔽"),
    UNDERLYING_OBSERVED(3, "观察到悬浮层排除／底色可见"), CONTENT_VISIBLE(4, "保护内容仍可见"),
    TIMEOUT(5, "超时／证据不足"), CANCELLED(6, "已取消")
}

internal enum class EndReason(val code: Int, val label: String) {
    COMPLETED(1, "实验序列结束"), USER_STOP(2, "手动停止"), BACKGROUND(3, "页面进入后台"),
    FOCUS_LOST(4, "页面失去焦点"), OWNER_GONE(5, "页面已离开"),
    OVERLAY_PERMISSION(6, "未授权悬浮窗"), CONSENT_CANCELLED(7, "屏幕共享未授权"),
    CONSENT_ABANDONED(8, "已丢弃离页授权"), CONSENT_TIMEOUT(9, "授权等待超时"),
    DISPLAY_CHANGED(10, "屏幕大小或方向变化"), REVOKED(11, "屏幕共享已撤销"),
    SCREEN_OFF(12, "屏幕已关闭"), SETUP_FAILURE(13, "启动或平台操作失败"),
    BUFFER_INVALID(14, "画面格式或边界不支持"), OVERLAY_GEOMETRY(15, "悬浮窗位置不符合实验条件"),
    OVERALL_TIMEOUT(16, "实验总时限已到")
}

internal data class FrameEvidence(
    val timestamp: Long, // Source-local ordering diagnostic; NEVER compared to a host clock.
    val nonce: Boolean,
    val witness: Boolean,
    val markerBlack: Boolean,
    val patch: Patch
)

internal data class PhaseResult(
    val phase: Phase, val verdict: Verdict, val frames: Int, val nonceFrames: Int,
    val blackFrames: Int, val baseFrames: Int, val coverFrames: Int, val mismatchFrames: Int,
    val staleFrames: Int, val nonMonotonicFrames: Int, val streak: Int, val elapsedMs: Long
)

internal class CaptureProof(private val startedAt: Long) {
    companion object {
        const val REQUIRED_MATCHES = 3
        const val PHASE_TIMEOUT_MS = 4_500L
        const val OVERALL_TIMEOUT_MS = 40_000L
    }
    private var cancelled = false
    private var active: Phase? = null
    private var phaseStarted = startedAt
    private var epoch = 0
    private var lastTimestamp: Long? = null
    private var frames = 0
    private var nonceFrames = 0
    private var blackFrames = 0
    private var baseFrames = 0
    private var coverFrames = 0
    private var mismatchFrames = 0
    private var staleFrames = 0
    private var nonMonotonicFrames = 0
    private var streak = 0
    private var candidate: Verdict? = null
    private val records = mutableListOf<PhaseResult>()
    val results: List<PhaseResult> get() = records.toList()
    val currentPhase: Phase? get() = active

    fun begin(phase: Phase, now: Long): Int {
        check(!cancelled && active == null && records.size == phase.ordinal)
        active = phase; phaseStarted = now; epoch++
        frames = 0; nonceFrames = 0; blackFrames = 0; baseFrames = 0; coverFrames = 0
        mismatchFrames = 0; staleFrames = 0; nonMonotonicFrames = 0; streak = 0; candidate = null
        return epoch
    }

    fun observe(ticket: Int, frame: FrameEvidence, now: Long): PhaseResult? {
        val phase = active ?: return null
        if (cancelled || ticket != epoch) return null
        if (expired(now)) return finish(Verdict.TIMEOUT, now)
        // A fixed bound also limits all metadata counters on unexpectedly fast sources.
        if (frames >= 10_000) return finish(Verdict.TIMEOUT, now)
        frames++
        val previous = lastTimestamp
        if (previous != null && frame.timestamp <= previous) {
            nonMonotonicFrames++; streak = 0; candidate = null
            return null
        }
        lastTimestamp = frame.timestamp
        if (frame.nonce) nonceFrames++
        when (frame.patch) {
            Patch.BLACK -> blackFrames++
            Patch.BASE -> baseFrames++
            Patch.COVER -> coverFrames++
            Patch.OTHER -> mismatchFrames++
        }
        val fresh = if (phase == Phase.SECURE_ACTIVITY) frame.witness else frame.nonce
        if (!fresh) { staleFrames++; streak = 0; candidate = null; return null }
        val verdict = when (phase) {
            Phase.BASELINE, Phase.HIDE, Phase.PUBLIC_RETURN ->
                if (frame.patch == Patch.BASE) Verdict.CURRENT_CONFIRMED else null
            Phase.COVER, Phase.RESTORE ->
                if (frame.patch == Patch.COVER) Verdict.CURRENT_CONFIRMED else null
            Phase.SECURE_COVER -> when (frame.patch) {
                Patch.BLACK -> Verdict.BLACK_OBSERVED
                Patch.BASE -> Verdict.UNDERLYING_OBSERVED
                Patch.COVER -> Verdict.CONTENT_VISIBLE
                Patch.OTHER -> null
            }
            Phase.SECURE_ACTIVITY -> when {
                frame.nonce || frame.patch == Patch.BASE -> Verdict.CONTENT_VISIBLE
                frame.markerBlack && frame.patch == Patch.BLACK -> Verdict.BLACK_OBSERVED
                else -> null // Arbitrary missing/changed pixels are NOT a protection pass.
            }
        }
        if (verdict == null) {
            if (frame.patch != Patch.OTHER) mismatchFrames++
            candidate = null; streak = 0; return null
        }
        streak = if (candidate == verdict) streak + 1 else 1
        candidate = verdict
        return if (streak >= REQUIRED_MATCHES) finish(verdict, now) else null
    }

    fun timeout(ticket: Int, now: Long): PhaseResult? =
        if (!cancelled && active != null && ticket == epoch && expired(now)) finish(Verdict.TIMEOUT, now) else null

    fun cancel(now: Long) {
        if (cancelled) return
        if (active != null) finish(Verdict.CANCELLED, now)
        cancelled = true; epoch++
    }

    private fun expired(now: Long) = now - phaseStarted >= PHASE_TIMEOUT_MS || now - startedAt >= OVERALL_TIMEOUT_MS
    private fun finish(verdict: Verdict, now: Long): PhaseResult {
        val result = PhaseResult(checkNotNull(active), verdict, frames, nonceFrames, blackFrames, baseFrames,
            coverFrames, mismatchFrames, staleFrames, nonMonotonicFrames, streak,
            (now - phaseStarted).coerceIn(0, OVERALL_TIMEOUT_MS))
        records += result; active = null
        return result
    }
}

/** Explicit click only. A cancelled, abandoned or consumed grant can never resume a session. */
internal class ConsentGate {
    enum class State { IDLE, AWAITING, READY, RUNNING }
    var state = State.IDLE; private set
    fun request(): Boolean {
        if (state != State.IDLE) return false
        state = State.AWAITING; return true
    }
    fun result(granted: Boolean): Boolean {
        if (state != State.AWAITING) return false
        state = if (granted) State.READY else State.IDLE
        return granted
    }
    fun consume(resumed: Boolean, focused: Boolean): Boolean {
        if (state != State.READY || !resumed || !focused) return false
        state = State.RUNNING; return true
    }
    fun cancel() { state = State.IDLE }
}
