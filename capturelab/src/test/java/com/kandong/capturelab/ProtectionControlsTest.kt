package com.kandong.capturelab

import org.junit.Assert.*
import org.junit.Test

class ProtectionControlsTest {
    private fun complete() = Phase.entries.map { phase -> PhaseResult(phase,
        if (phase in listOf(Phase.SECURE_COVER, Phase.SECURE_ACTIVITY)) Verdict.BLACK_OBSERVED else Verdict.CURRENT_CONFIRMED,
        3, 3, 0, 0, 0, 0, 0, 0, 3, 200) }

    @Test fun completeBeforeAndAfterControlsPermitOnlyALimitedObservation() {
        val result = ProtectionControls.assess(complete())
        assertEquals(ControlStatus.CONTROLLED_OBSERVATION, result.cover)
        assertEquals(ControlStatus.CONTROLLED_OBSERVATION, result.activity)
    }

    @Test fun missingOrFailedPublicControlsCannotSupportProtectedWindowClaim() {
        for (phase in listOf(Phase.BASELINE, Phase.COVER, Phase.HIDE, Phase.RESTORE)) {
            val failed = complete().map { if (it.phase == phase) it.copy(verdict = Verdict.TIMEOUT) else it }
            assertEquals("Missing cover control $phase", ControlStatus.INSUFFICIENT, ProtectionControls.assess(failed).cover)
        }
        for (phase in listOf(Phase.BASELINE, Phase.RESTORE, Phase.PUBLIC_RETURN)) {
            assertEquals("Missing Activity control $phase", ControlStatus.INSUFFICIENT,
                ProtectionControls.assess(complete().filterNot { it.phase == phase }).activity)
        }
    }

    @Test fun cancelledOrIncompleteSequenceKeepsRawObservationsButNotControlledConclusion() {
        val partial = complete().take(3)
        assertEquals(Verdict.BLACK_OBSERVED, partial.last().verdict)
        assertEquals(ControlStatus.INSUFFICIENT, ProtectionControls.assess(partial).cover)
        assertEquals(ControlStatus.INSUFFICIENT, ProtectionControls.assess(partial).activity)
    }

    @Test fun visibleProtectedContentAndUnderlyingObservationAreNotBlackRedaction() {
        val frames = complete().map {
            when(it.phase) {
                Phase.SECURE_COVER -> it.copy(verdict = Verdict.UNDERLYING_OBSERVED)
                Phase.SECURE_ACTIVITY -> it.copy(verdict = Verdict.CONTENT_VISIBLE)
                else -> it
            }
        }
        assertEquals(ControlStatus.CONTROLLED_OBSERVATION, ProtectionControls.assess(frames).cover)
        assertEquals(ControlStatus.CONTENT_VISIBLE, ProtectionControls.assess(frames).activity)
        assertEquals(Verdict.UNDERLYING_OBSERVED, frames[2].verdict)
    }

    @Test fun duplicatePhasesCannotStandInForMissingControls() {
        val duplicated = complete() + complete()[0]
        assertEquals(ControlStatus.INSUFFICIENT, ProtectionControls.assess(duplicated).cover)
        assertEquals(ControlStatus.INSUFFICIENT, ProtectionControls.assess(duplicated).activity)
    }
}
