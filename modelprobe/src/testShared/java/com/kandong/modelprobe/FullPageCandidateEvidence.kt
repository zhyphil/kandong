package com.kandong.modelprobe

/** Full value comparison across the association's independent immutable snapshot. */
internal object FullPageCandidateEvidence {
    fun same(a: FullPageOcrContract.Candidate, b: FullPageOcrContract.Candidate): Boolean {
        val p=a.provenance; val q=b.provenance
        return a.modelId==b.modelId && a.rawText==b.rawText && a.recognitionWidth==b.recognitionWidth &&
            a.recognitionTime==b.recognitionTime && p.version==q.version && p.pageFixtureId==q.pageFixtureId &&
            p.stripIndex==q.stripIndex && p.read==q.read && p.core==q.core && p.contourIndex==q.contourIndex &&
            p.rawBoxIndex==q.rawBoxIndex && p.finalBoxIndex==q.finalBoxIndex && p.stripReadingOrder==q.stripReadingOrder &&
            p.localQuad==q.localQuad && p.pageQuad==q.pageQuad && p.detectorScore.toRawBits()==q.detectorScore.toRawBits() &&
            p.ownsCoreCenter==q.ownsCoreCenter && p.id==q.id
    }
}
