package com.kandong.app.magnification

/** Geometry is the source projected by the system, never the destination lens bounds. */
internal data class SourceBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal enum class MagnifierMode { WINDOW, FULLSCREEN, UNKNOWN }
internal data class MagnifierObservation(
    val mode: MagnifierMode,
    val scale: Float,
    val activated: Boolean?,
    val source: SourceBounds?,
) {
    val knownInactive get() = mode != MagnifierMode.UNKNOWN && scale.isFinite() && scale >= 1f &&
        (activated == false || (activated == null &&
            ((mode == MagnifierMode.WINDOW && source == null) ||
                (mode == MagnifierMode.FULLSCREEN && scale == 1f))))
    val visibleWindow get() = mode == MagnifierMode.WINDOW && activated != false &&
        scale > 1f && source != null
    val supportedScale get() = scale in setOf(2f, 3f, 4f)
}

/** Public Android APIs expose no owner identity. This is conservative likely-ownership only. */
internal class MagnifierSession {
    enum class Phase { IDLE, REQUESTING, ACTIVE, STOPPING }
    var phase = Phase.IDLE; private set
    var generation = 0L; private set
    var accepted: Boolean? = null; private set
    var source: SourceBounds? = null; private set
    var scale = 2f; private set
    var message = "请同意本次使用，再打开放大镜。"; private set
    private var resetAccepted = false
    private var observedActive = false
    val running get() = phase != Phase.IDLE
    val active get() = phase == Phase.ACTIVE

    fun begin(observation: MagnifierObservation?): Long? {
        if (running) return null
        if (observation?.knownInactive != true) {
            message = "已有系统放大，或无法确认状态。请先自行关闭放大，再重试。"
            return null
        }
        generation++
        phase = Phase.REQUESTING; accepted = null; source = null; resetAccepted = false; observedActive = false
        scale = 2f; message = "正在打开放大镜…"
        return generation
    }

    fun requestResult(token: Long, result: Boolean?) {
        if (token != generation || !running) return
        accepted = result
        if (result == false) finish("系统未接受放大请求，请检查是否支持局部放大。")
        else if (result == null) stop("未能确认请求结果，请点关闭重试；必要时在系统中关闭放大。")
    }

    fun observe(token: Long, observation: MagnifierObservation?) {
        if (token != generation || !running) return
        if (observation == null) {
            if (active) stop("无法确认系统放大状态，请点关闭重试。")
            return
        }
        if (phase == Phase.STOPPING && (resetAccepted || observedActive) && observation.knownInactive) {
            finish("放大镜已关闭。")
            return
        }
        val foreign = (!observation.knownInactive && observation.mode != MagnifierMode.WINDOW) ||
            (observation.mode == MagnifierMode.WINDOW && !observation.knownInactive &&
                (observation.activated == true || observation.source != null) && !observation.supportedScale)
        if (foreign || (phase == Phase.ACTIVE && observation.knownInactive)) {
            finish("系统放大已变化，看懂已停止控制。")
            return
        }
        if (accepted == true && observation.visibleWindow && observation.supportedScale) {
            observedActive = true
            if (phase != Phase.STOPPING) {
                phase = Phase.ACTIVE; source = observation.source; scale = observation.scale
                message = "放大镜已打开。拖动系统镜框手柄，查看其他位置。"
            }
        } else if (phase == Phase.ACTIVE && observation.source == null) {
            finish("系统放大已关闭或源区域不可用，看懂已停止控制。")
        }
    }

    fun stop(reason: String = "正在关闭放大镜…") {
        source = null
        if (!running) { message = "放大镜已关闭。"; return }
        phase = Phase.STOPPING; message = reason
    }

    fun mayReset(observation: MagnifierObservation?): Boolean = phase == Phase.STOPPING &&
        accepted != false && observation?.mode == MagnifierMode.WINDOW &&
        observation.supportedScale && (observation.visibleWindow || observation.activated == true)

    fun resetResult(success: Boolean) {
        if (phase != Phase.STOPPING) return
        resetAccepted = success
        message = if (success) "正在确认放大镜已关闭…" else "放大镜尚未关闭，请再点关闭；也可在系统中手动关闭。"
    }

    fun timeout(token: Long) {
        if (token != generation || !running || active) return
        stop("未能确认放大状态。请点关闭重试；必要时在系统中手动关闭。")
    }

    fun feedback(text: String) { message = text }
    private fun finish(text: String) {
        phase = Phase.IDLE; source = null; accepted = null; resetAccepted = false; observedActive = false
        message = text; generation++
    }
}
