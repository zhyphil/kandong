package com.kandong.modelprobe

/** Full value comparison across the association's independent immutable snapshot. */
internal object FullPageCandidateEvidence {
    fun same(a: FullPageOcrContract.Candidate, b: FullPageOcrContract.Candidate): Boolean = a == b
}
