package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test

class FullPageCandidateEvidenceTest {
    private fun p(version:CaptureVersion=CaptureVersion(1,2,3,4,5,6), page:String="fixture", strip:Int=0,
        read:FullPageStripPlanner.Rect=FullPageStripPlanner.Rect(0,0,1200,464),
        core:FullPageStripPlanner.Rect=FullPageStripPlanner.Rect(0,0,1200,400), contour:Int=0, raw:Int=0,
        final:Int=0, rank:Int=0, local:List<GeometryProbeContract.Point> = listOf(GeometryProbeContract.Point(1.0,2.0)),
        whole:List<GeometryProbeContract.Point> = listOf(GeometryProbeContract.Point(1.0,2.0)), score:Double=0.5,
        owns:Boolean=true,id:String="candidate") = FullPageOcrContract.Provenance(version,page,strip,read,core,
            contour,raw,final,rank,local,whole,score,owns,id)
    private fun c(p:FullPageOcrContract.Provenance=p())=FullPageOcrContract.Candidate(p,"ch","原始",32,8)
    @Test fun independentSnapshotWithSameValuesMatches() {
        val a=c();val b=c(); assertNotSame(a.provenance,b.provenance)
        assertTrue(FullPageCandidateEvidence.same(a,b))
    }
    @Test fun everyEvidenceFieldChangeAndScoreBitChangeIsRejected() {
        val a=c()
        val variants=listOf(p(version=CaptureVersion(2,2,3,4,5,6)),p(page="other"),p(strip=1),
            p(read=FullPageStripPlanner.Rect(0,1,1200,464)),p(core=FullPageStripPlanner.Rect(0,1,1200,400)),
            p(contour=1),p(raw=1),p(final=1),p(rank=1),p(local=listOf(GeometryProbeContract.Point(3.0,2.0))),
            p(whole=listOf(GeometryProbeContract.Point(3.0,2.0))),p(score=0.6),p(owns=false),p(id="other"))
            .map { c(it) } + listOf(a.copy(modelId="latin"),a.copy(rawText=""),a.copy(recognitionWidth=33),a.copy(recognitionTime=9))
        assertEquals(18,variants.size)
        variants.forEachIndexed { i,b -> assertFalse("field $i",FullPageCandidateEvidence.same(a,b)) }
        assertFalse(FullPageCandidateEvidence.same(c(p(score=0.0)),c(p(score=-0.0))))
    }
}
