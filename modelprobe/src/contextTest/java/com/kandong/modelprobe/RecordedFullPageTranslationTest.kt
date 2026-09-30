package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test

class RecordedFullPageTranslationTest {
    private val sha="a".repeat(64)
    private val version=CaptureVersion(1,2,3,4,0,0)
    private val plan=FullPageStripPlanner.plan(1176,2400)
    private val model=FullPageOcrContract.models.single { it.id=="latin" }
    private fun candidate(strip:Int,text:String,y:Double):FullPageOcrContract.Candidate {
        val s=plan.strips[strip]; val q=listOf(GeometryProbeContract.Point(80.0,y),GeometryProbeContract.Point(380.0,y),
            GeometryProbeContract.Point(380.0,y+30),GeometryProbeContract.Point(80.0,y+30))
        return FullPageOcrContract.Candidate(FullPageOcrContract.Provenance(version,"en-normal",strip,s.read,s.core,
            0,0,0,0,q.map { it.copy(y=it.y-s.read.top) },q,.875,true,"1-2-3-4-0-0/en-normal/s$strip/c0-r0-f0"),
            "latin",text,320,40)
    }
    private fun request():FullPageTranslationProbe.ProbeRequest {
        val raw=listOf(candidate(0,"Breakfast is not included.",100.0),candidate(3,"Outside context",2200.0))
        val id=FullPageOcrAssociation.PageIdentity("current-batch",version,"en-normal",model,
            "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
            "d80d308e9759b3ea3aaf6e57fa51b39ef0a2eeb3145a9b23df819f0f0279af44")
        val a=FullPageOcrAssociation.associate(id,plan,FullPageOcrAssociation.UpstreamState.COMPLETE,
            plan.strips.map { FullPageOcrAssociation.StripReceipt(it.index,it.read,it.core,true,raw.count { c -> c.provenance.stripIndex==it.index }) },raw) { true }
        assertTrue(a.published)
        val probe=FullPageTranslationProbe(RecordedFullPageTranslation.choice(sha))
        probe.observe(version);val t=probe.click(100)!!
        assertTrue(probe.captured(t,RgbaFrameMetadata(1176,2400,version,101,60000),a,"en",ContextRect(0.0,0.0,1000.0,500.0),102,102))
        return probe.pending(102)!!
    }
    private fun page(r:FullPageTranslationProbe.ProbeRequest):RecordedFullPageTranslation.Page {
        val rows=r.contract.targets.map { t -> RecordedFullPageTranslation.Outcome(
            RecordedFullPageTranslation.key(r.sourceMap.getValue(t.id)),t.sources.single().text,"检查用候选",
            AnswerKind.CANDIDATE,AnswerOrigin.RECORDED_DEEPL,null) }
        return RecordedFullPageTranslation.Page("en-normal","en",1176,2400,
            RecordedFullPageTranslation.fingerprint(r.evidence,"en"),rows.map { it.key },rows)
    }
    private fun rejected(action:()->Unit) { try { action();fail("Changed recording was accepted") } catch(_:IllegalArgumentException) {} }
    @Test fun bindsToCurrentTargetsAndKeepsOutsideContextWithoutGeometryFromProvider() {
        val r=request();val p=page(r);val answer=RecordedFullPageTranslation.reply(r,p,sha)
        assertEquals(2,answer.answers.size);assertEquals(r.contract.page,answer.page)
        assertSame(r.contract.targets[1],answer.answers[1].binding)
        assertEquals(AnswerOrigin.RECORDED_DEEPL,answer.answers[0].origin)
        assertTrue(RecordedFullPageTranslation.fields(r.evidence,"en").last().contains("Outside context"))
    }
    @Test fun changedOutsideTextRejectsEntirePage() {
        val r=request();val p=page(r)
        val a=r.evidence.association
        val altered=a.copy(rawCandidates=a.rawCandidates.mapIndexed { i,c -> if(i==1)c.copy(rawText="Changed outside") else c })
        val changed=FullPageTranslationProbe.ProbeRequest(r.contract,r.evidence.copy(association=altered),r.sourceMap)
        rejected { RecordedFullPageTranslation.reply(changed,p,sha) }
    }
    @Test fun changedGeometryScoreModelsGroupsContextAndDimensionsFailClosed() {
        val r=request();val p=page(r);val a=r.evidence.association;val c=a.rawCandidates[1];val q=c.provenance
        fun altered(quad:List<GeometryProbeContract.Point> = q.pageQuad,score:Double=q.detectorScore)=c.copy(provenance=FullPageOcrContract.Provenance(
            q.version,q.pageFixtureId,q.stripIndex,q.read,q.core,q.contourIndex,q.rawBoxIndex,q.finalBoxIndex,q.stripReadingOrder,
            q.localQuad,quad,score,q.ownsCoreCenter,q.id))
        val changes=listOf(a.copy(rawCandidates=listOf(a.rawCandidates[0],altered(score=.874))),
            a.copy(rawCandidates=listOf(a.rawCandidates[0],altered(quad=q.pageQuad.map { it.copy(x=it.x+1) }))),
            a.copy(identity=a.identity!!.copy(model=model.copy(sha="b".repeat(64)))),
            a.copy(groups=a.groups.map { it.copy(reasons=listOf(FullPageOcrAssociation.Reason.POSSIBLE_CLIP)) }))
        changes.forEach { changed -> rejected { RecordedFullPageTranslation.reply(FullPageTranslationProbe.ProbeRequest(
            r.contract,r.evidence.copy(association=changed),r.sourceMap),p,sha) } }
        rejected { RecordedFullPageTranslation.reply(r,p.copy(width=1080),sha) }
        val bad=r.contract.copy(screenContext=r.contract.screenContext.copy(blocks=r.contract.screenContext.blocks.reversed()))
        rejected { RecordedFullPageTranslation.reply(FullPageTranslationProbe.ProbeRequest(bad,r.evidence,r.sourceMap),p,sha) }
    }
    @Test fun missingExtraDuplicateReorderedAndWrongSourceResultsAreRejected() {
        val r=request();val p=page(r)
        listOf(p.copy(outcomes=p.outcomes.drop(1)),p.copy(outcomes=p.outcomes+p.outcomes[0]),
            p.copy(outcomes=listOf(p.outcomes[0],p.outcomes[0])),p.copy(outcomes=p.outcomes.reversed()),
            p.copy(outcomes=p.outcomes.map { it.copy(sourceText="different") })).forEach { changed ->
            rejected { RecordedFullPageTranslation.reply(r,changed,sha) }
        }
    }
    @Test fun markerOriginAndUnknownProviderCannotMasqueradeAsRecorded() {
        val r=request();val p=page(r)
        rejected { RecordedFullPageTranslation.reply(r,p.copy(outcomes=p.outcomes.map { it.copy(origin=AnswerOrigin.HANDCRAFTED_FIXTURE) }),sha) }
        rejected { RecordedFullPageTranslation.reply(r,p,"b".repeat(64)) }
        val wrong=FullPageTranslationProbe.ProbeRequest(r.contract.copy(model="OTHER"),r.evidence,r.sourceMap)
        rejected { RecordedFullPageTranslation.reply(wrong,p,sha) }
    }
    @Test fun withheldResultsKeepExactSourceAndNeverInventTranslation() {
        val r=request();val p=page(r)
        val withheld=p.copy(outcomes=p.outcomes.map { it.copy(chinese=null,kind=AnswerKind.KEEP_ORIGINAL,origin=AnswerOrigin.SOURCE,
            reason=KeepOriginalReason.CHECK_UNVERIFIED) })
        val response=RecordedFullPageTranslation.reply(r,withheld,sha)
        assertTrue(response.answers.all { it.chinese==null && it.binding.sources.single().text.isNotEmpty() })
        rejected { RecordedFullPageTranslation.reply(r,withheld.copy(outcomes=withheld.outcomes.map { it.copy(chinese="invented") }),sha) }
    }
}
