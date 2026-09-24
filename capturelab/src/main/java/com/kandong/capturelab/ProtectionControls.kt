package com.kandong.capturelab

/** Raw frame observations are kept even when their before/after controls did not succeed. */
internal enum class ControlStatus(val label: String) {
    CONTROLLED_OBSERVATION("前后对照完整"), INSUFFICIENT("证据不足"), CONTENT_VISIBLE("保护内容仍可见")
}
internal data class ProtectionAssessment(val cover: ControlStatus, val activity: ControlStatus)
internal object ProtectionControls {
    fun assess(results: List<PhaseResult>): ProtectionAssessment {
        if (results.map { it.phase }.distinct().size != results.size)
            return ProtectionAssessment(ControlStatus.INSUFFICIENT, ControlStatus.INSUFFICIENT)
        val phases = results.associateBy { it.phase }
        fun evaluate(target: Phase, controls: List<Phase>): ControlStatus {
            val observation = phases[target] ?: return ControlStatus.INSUFFICIENT
            if (controls.any { phases[it]?.verdict != Verdict.CURRENT_CONFIRMED }) return ControlStatus.INSUFFICIENT
            return when (observation.verdict) {
                Verdict.BLACK_OBSERVED -> ControlStatus.CONTROLLED_OBSERVATION
                Verdict.UNDERLYING_OBSERVED -> if (target == Phase.SECURE_COVER)
                    ControlStatus.CONTROLLED_OBSERVATION else ControlStatus.INSUFFICIENT
                Verdict.CONTENT_VISIBLE -> ControlStatus.CONTENT_VISIBLE
                else -> ControlStatus.INSUFFICIENT
            }
        }
        return ProtectionAssessment(
            evaluate(Phase.SECURE_COVER, listOf(Phase.BASELINE, Phase.COVER, Phase.HIDE, Phase.RESTORE)),
            evaluate(Phase.SECURE_ACTIVITY, listOf(Phase.BASELINE, Phase.RESTORE, Phase.PUBLIC_RETURN))
        )
    }
}
