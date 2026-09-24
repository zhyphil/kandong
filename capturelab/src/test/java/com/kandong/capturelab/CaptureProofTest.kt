package com.kandong.capturelab

import org.junit.Assert.*
import org.junit.Test

class CaptureProofTest {
    private fun frame(time: Long, nonce: Boolean = true, patch: Patch = Patch.BASE,
        witness: Boolean = false, black: Boolean = false) = FrameEvidence(time, nonce, witness, black, patch)

    @Test fun sourceTimestampIsNeverComparedWithHostClockForFreshness() {
        for (sourceTime in listOf(-900_000L, 1L, Long.MAX_VALUE - 10)) {
            val proof = CaptureProof(5_000_000)
            val ticket = proof.begin(Phase.BASELINE, 5_000_000)
            assertNull(proof.observe(ticket, frame(sourceTime, nonce = false), 5_000_001))
            assertNull(proof.observe(ticket, frame(sourceTime + 1), 5_000_002))
            assertNull(proof.observe(ticket, frame(sourceTime + 2), 5_000_003))
            val result = proof.observe(ticket, frame(sourceTime + 3), 5_000_004)!!
            assertEquals(Verdict.CURRENT_CONFIRMED, result.verdict)
            assertEquals(1, result.staleFrames)
            assertEquals(3, result.streak)
        }
    }

    @Test fun matchingNonceAndPatchMustBeInTheSameImage() {
        val proof = CaptureProof(0); val ticket = proof.begin(Phase.BASELINE, 0)
        for (i in 1..6) assertNull(proof.observe(ticket,
            frame(i.toLong(), nonce = i % 2 == 0, patch = if (i % 2 == 0) Patch.COVER else Patch.BASE), i.toLong()))
        assertEquals(Verdict.TIMEOUT, proof.timeout(ticket, 4_500)!!.verdict)
    }

    @Test fun duplicateOrBackwardsSourceTimestampCannotAccumulateMatches() {
        val proof = CaptureProof(0); val ticket = proof.begin(Phase.BASELINE, 0)
        assertNull(proof.observe(ticket, frame(50), 1))
        assertNull(proof.observe(ticket, frame(50), 2))
        assertNull(proof.observe(ticket, frame(40), 3))
        assertNull(proof.observe(ticket, frame(51), 4))
        assertNull(proof.observe(ticket, frame(52), 5))
        val r = proof.observe(ticket, frame(53), 6)!!
        assertEquals(2, r.nonMonotonicFrames); assertEquals(3, r.streak)
    }

    @Test fun cancellationAndLatePhaseTicketsNeverPublishEvidence() {
        val proof = CaptureProof(0); val first = proof.begin(Phase.BASELINE, 0)
        repeat(3) { proof.observe(first, frame(it.toLong()), it.toLong()) }
        val second = proof.begin(Phase.COVER, 10)
        repeat(10) { assertNull(proof.observe(first, frame(it + 100L, patch = Patch.COVER), it + 11L)) }
        proof.cancel(30)
        repeat(10) { assertNull(proof.observe(second, frame(it + 200L, patch = Patch.COVER), it + 31L)) }
        assertEquals(2, proof.results.size)
        assertEquals(Verdict.CANCELLED, proof.results.last().verdict)
        assertEquals(0, proof.results.last().frames)
        assertNull(proof.timeout(second, 9_000))
    }

    @Test fun frameArrivingOnOrAfterDeadlineCannotTurnTimeoutIntoSuccess() {
        val proof = CaptureProof(0); val ticket = proof.begin(Phase.BASELINE, 0)
        proof.observe(ticket, frame(1), 1); proof.observe(ticket, frame(2), 2)
        assertEquals(Verdict.TIMEOUT, proof.observe(ticket, frame(3), 4_500)!!.verdict)
        assertNull(proof.observe(ticket, frame(4), 4_501))
    }

    @Test fun overallDeadlineAlsoBoundsLaterPhases() {
        val proof = CaptureProof(0); val first = proof.begin(Phase.BASELINE, 0)
        repeat(3) { proof.observe(first, frame(it.toLong()), it.toLong()) }
        val next = proof.begin(Phase.COVER, 39_900)
        assertEquals(Verdict.TIMEOUT, proof.timeout(next, 40_000)!!.verdict)
    }

    private fun atPhase(target: Phase): Pair<CaptureProof, Int> {
        val proof = CaptureProof(0)
        for (p in Phase.entries.take(target.ordinal)) {
            val ticket = proof.begin(p, p.ordinal * 10L)
            val patch = when (p) {
                Phase.COVER, Phase.RESTORE -> Patch.COVER
                Phase.SECURE_COVER, Phase.SECURE_ACTIVITY -> Patch.BLACK
                else -> Patch.BASE
            }
            repeat(3) { i -> proof.observe(ticket,
                frame(-100L + p.ordinal * 10L + i, nonce = p != Phase.SECURE_ACTIVITY,
                    patch = patch, witness = true, black = true), p.ordinal * 10L + i) }
        }
        return proof to proof.begin(target, 5_000)
    }

    @Test fun arbitraryMismatchIsNotSecureEvidenceEvenWhenNonceIsCurrent() {
        val (proof, ticket) = atPhase(Phase.SECURE_COVER)
        repeat(10) { assertNull(proof.observe(ticket, frame(it.toLong(), patch = Patch.OTHER), 5_001L + it)) }
        val r = proof.timeout(ticket, 9_500)!!
        assertEquals(Verdict.TIMEOUT, r.verdict)
        assertEquals(10, r.mismatchFrames)
    }

    @Test fun blackUnderlyingAndVisibleSecureOverlayHaveDifferentOutcomes() {
        for ((patch, outcome) in listOf(Patch.BLACK to Verdict.BLACK_OBSERVED,
            Patch.BASE to Verdict.UNDERLYING_OBSERVED, Patch.COVER to Verdict.CONTENT_VISIBLE)) {
            val (proof, ticket) = atPhase(Phase.SECURE_COVER)
            repeat(3) { proof.observe(ticket, frame(it.toLong(), patch = patch), 5_001L + it) }
            assertEquals(outcome, proof.results.last().verdict)
            assertNotEquals(Verdict.CURRENT_CONFIRMED, proof.results.last().verdict)
        }
    }

    @Test fun secureActivityNeedsCurrentIndependentWitnessInSameBlackFrame() {
        val (proof, ticket) = atPhase(Phase.SECURE_ACTIVITY)
        repeat(3) { assertNull(proof.observe(ticket,
            frame(it.toLong(), nonce = false, patch = Patch.BLACK, black = true), 5_001L + it)) }
        repeat(3) { proof.observe(ticket,
            frame(it + 3L, nonce = false, patch = Patch.BLACK, witness = true, black = true), 5_010L + it) }
        assertEquals(Verdict.BLACK_OBSERVED, proof.results.last().verdict)
        assertEquals(3, proof.results.last().staleFrames)
    }

    @Test fun witnessAloneAndGenericMissingMarkersDoNotProveSecureActivityRedaction() {
        val (proof, ticket) = atPhase(Phase.SECURE_ACTIVITY)
        repeat(3) { assertNull(proof.observe(ticket,
            frame(it.toLong(), nonce = false, patch = Patch.OTHER, witness = true), 5_001L + it)) }
        assertEquals(Verdict.TIMEOUT, proof.timeout(ticket, 9_500)!!.verdict)
    }

    @Test fun transitionsRequireUniquePhaseMarkers() {
        val markers = Phase.entries.map { Marker.forPhase(987, it) }
        assertEquals(7, markers.map { it.first }.distinct().size)
        assertEquals(7, markers.map { it.second }.distinct().size)
        assertNotEquals(markers[0], markers[3]); assertNotEquals(markers[3], markers[6])
    }

    @Test fun consentIsExplicitSingleUseAndRequiresResumedFocusedOwner() {
        val gate = ConsentGate()
        assertFalse(gate.result(true)); assertFalse(gate.consume(true, true))
        assertTrue(gate.request()); assertFalse(gate.request())
        assertTrue(gate.result(true)); assertFalse(gate.consume(false, true)); assertFalse(gate.consume(true, false))
        assertTrue(gate.consume(true, true)); assertFalse(gate.consume(true, true))
        gate.cancel(); assertFalse(gate.result(true)); assertFalse(gate.consume(true, true))
        assertTrue(gate.request()); assertFalse(gate.result(false)); assertEquals(ConsentGate.State.IDLE, gate.state)
    }
}
