package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test

/** Structural binding fixtures only: no assertion about recognition or translation quality. */
class RegionVisualTranslationControllerTest {
    private val version=CaptureVersion(1,2,3,4,5,6)
    private val plan=FullPageStripPlanner.plan(1176,2400)
    private val roi=ContextRect(50.0,50.0,400.0,200.0)
    private fun meta(v: CaptureVersion=version, acquired: Long=110, ttl: Long=1000)=RgbaFrameMetadata(1176,2400,v,acquired,ttl)
    private fun candidate(id: String, text: String="label", strip: Int=0, x: Double=100.0,
        y: Double=100.0, v: CaptureVersion=version, model: String="latin"): FullPageOcrContract.Candidate {
        val s=plan.strips[strip]
        val q=listOf(GeometryProbeContract.Point(x,y),GeometryProbeContract.Point(x+80,y),
            GeometryProbeContract.Point(x+80,y+20),GeometryProbeContract.Point(x,y+20))
        return FullPageOcrContract.Candidate(FullPageOcrContract.Provenance(v,"fixed-page",strip,s.read,s.core,
            17,12,9,0,q.map { it.copy(x=it.x-s.read.left,y=it.y-s.read.top) },q,.87,false,id),model,text,320,40)
    }
    private fun association(cs: List<FullPageOcrContract.Candidate> = listOf(candidate("a"),candidate("b",y=300.0)),
        v: CaptureVersion=version, model: String="latin"): FullPageOcrAssociation.Result {
        val identity=FullPageOcrAssociation.PageIdentity("test-batch",v,"fixed-page",FullPageOcrContract.models.single { it.id==model },
            "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
            if(model=="latin") "d80d308e9759b3ea3aaf6e57fa51b39ef0a2eeb3145a9b23df819f0f0279af44"
            else "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af")
        return FullPageOcrAssociation.associate(identity,plan,FullPageOcrAssociation.UpstreamState.COMPLETE,
            plan.strips.map { FullPageOcrAssociation.StripReceipt(it.index,it.read,it.core,true,cs.count { c -> c.provenance.stripIndex==it.index }) },cs) { true }
    }
    private val transform=MirrorTransform(2.0,600.0,300.0)
    private fun response(r: FullPageTranslationProbe.ProbeRequest): FixtureResponse {
        val c=r.contract
        val numbers=r.evidence.association.rawCandidates.withIndex().associate { it.value.provenance.id to it.index+1 }
        return FixtureResponse(c.id,c.page,c.targetLanguage,c.model,c.version,c.targets.map {
            FixtureAnswer(it,"绑定演示 ${numbers.getValue(r.sourceMap.getValue(it.id).provenance.id)}")
        })
    }
    private fun captured(language: String="en"): Pair<RegionVisualTranslationController,RegionVisualTranslationController.Run> {
        val controller=RegionVisualTranslationController()
        val run=controller.begin(version,100)!!
        val model=if(language.startsWith("zh")) "ch" else "latin"
        val page=association(listOf(candidate("a",model=model),candidate("b",y=300.0,model=model)),model=model)
        assertTrue(controller.captured(run,meta(),page,language,roi,120,120))
        return controller to run
    }
    @Test fun oneRunRetainsWholePageAndProjectsStableMarkers() {
        val (c,run)=captured()
        assertEquals(RegionVisualTranslationController.Phase.WAITING,c.state(120).phase)
        val request=c.pending(120)!!
        assertEquals(setOf("a","b"),c.sourceMap(120).values.map { it.provenance.id }.toSet())
        assertEquals(2,c.evidence(120)!!.association.rawCandidates.size)
        assertTrue(c.reply(run,request,response(request),121))
        assertEquals(RegionVisualTranslationController.Phase.READY,c.state(121).phase)
        assertNull(c.pending(121))
        val card=c.render(roi,transform,121).cards.single()
        assertEquals("绑定演示 1",card.chinese)
        assertEquals("a",c.sourceMap(121).getValue(card.targetId).provenance.id)
    }
    @Test fun bothChineseVariantsRetainSourceWithoutPendingReply() {
        for(language in listOf("zh-Hans","zh-Hant")) {
            val (c,_)=captured(language)
            assertNull(c.pending(120))
            assertEquals(RegionVisualTranslationController.Phase.READY,c.state(120).phase)
            val card=c.render(roi,transform,120).cards.single()
            assertNull(card.chinese); assertEquals("ALREADY_CHINESE",card.reason)
        }
    }
    @Test fun rejectedBeginAndDisplayOnlyChangesRetainCurrentRequest() {
        val (c,run)=captured(); val request=c.pending(120)!!
        c.choose(RegionVisualTranslationController.Display.ORIGINAL,120)
        val before=c.state(120)
        assertNull(c.begin(version.copy(snapshot=8),120))
        assertEquals(before,c.state(120)); assertTrue(c.owns(run,request))
        c.render(ContextRect(50.0,250.0,400.0,400.0),MirrorTransform(5.0,200.0,100.0,10.0,10.0),121)
        assertSame(request,c.pending(121))
        assertTrue(c.reply(run,request,response(request),121))
        assertNull(c.render(roi,transform,122).cards.single().chinese)
        c.choose(RegionVisualTranslationController.Display.DEMO,122)
        assertEquals("绑定演示 1",c.render(roi,transform,122).cards.single().chinese)
        assertEquals(meta(),c.evidence(122)!!.metadata)
    }
    @Test fun foreignLateAndOutOfOrderCallbacksCannotTouchReplacementOrClock() {
        val (c,old)=captured(); val previous=c.pending(120)!!
        val (foreign,foreignRun)=captured(); val other=foreign.pending(120)!!
        assertFalse(c.reply(foreignRun,other,response(other),Long.MAX_VALUE))
        assertFalse(c.failed(old,other,-1))
        assertSame(previous,c.pending(120))
        c.clear(ClearReason.MENU)
        val current=c.begin(version,121)!!
        assertFalse(c.reply(old,previous,response(previous),Long.MAX_VALUE))
        assertFalse(c.failed(old,-1))
        assertFalse(c.captured(old,meta(),association(),"en",roi,Long.MAX_VALUE,Long.MAX_VALUE))
        assertTrue(c.captured(current,meta(acquired=122),association(),"en",roi,123,123))
        assertEquals(RegionVisualTranslationController.Phase.WAITING,c.state(123).phase)
    }
    @Test fun malformedReplyAndWorkerValidationTimeAreRejected() {
        val (c,run)=captured(); val request=c.pending(120)!!
        assertFalse(c.reply(run,request,response(request).copy(answers=emptyList()),120))
        assertEquals(RegionVisualTranslationController.Phase.ERROR,c.state(120).phase)
        assertTrue(c.sourceMap(120).isEmpty()); assertNull(c.pending(120))
        val clock=RegionVisualTranslationController(); val token=clock.begin(version,100)!!
        assertFalse(clock.captured(token,meta(),association(),"en",roi,130,120))
        assertEquals(RegionVisualTranslationController.Phase.ERROR,clock.state(130).phase)
        assertNull(clock.evidence(130))
    }
    @Test fun originalTtlExpiresPendingAndReadyWithSingleClickRetry() {
        for(ready in listOf(false,true)) {
            val (c,old)=captured(); val request=c.pending(120)!!
            if(ready) assertTrue(c.reply(old,request,response(request),1000))
            // No getter before retry: begin itself must expire the original acquisition.
            val next=c.begin(version,1110)
            assertNotNull(next); assertEquals(RegionVisualTranslationController.Phase.READING,c.state(1110).phase)
            assertNull(c.evidence(1110)); assertNull(c.pending(1110))
            assertFalse(c.reply(old,request,response(request),Long.MAX_VALUE))
            assertTrue(c.captured(next!!,meta(acquired=1111),association(),"en",roi,1112,1112))
        }
        val (c,_)=captured()
        assertNotNull(c.evidence(1109)); assertNull(c.evidence(1110))
        assertEquals(RegionVisualTranslationController.Phase.EXPIRED,c.state(1110).phase)
        assertTrue(c.render(roi,transform,1110).cards.isEmpty())
    }
    @Test fun everyClearRevokesRunRequestsAndReadableDataWithoutAutomaticRestart() {
        for(reason in ClearReason.values()) {
            val (c,run)=captured(); val request=c.pending(120)!!
            c.clear(reason)
            assertFalse(c.owns(run)); assertNull(c.pending(120)); assertNull(c.evidence(120))
            assertTrue(c.sourceMap(120).isEmpty()); assertTrue(c.render(roi,transform,120).cards.isEmpty())
            assertFalse(c.reply(run,request,response(request),Long.MAX_VALUE))
            assertFalse(c.busy(120))
        }
    }
    @Test fun failureIsIdentityBoundInBothPhases() {
        val c=RegionVisualTranslationController(); val run=c.begin(version,100)!!
        assertTrue(c.failed(run,100)); assertEquals(RegionVisualTranslationController.Phase.ERROR,c.state(100).phase)
        val (waiting,token)=captured(); val request=waiting.pending(120)!!
        assertTrue(waiting.failed(token,request,120))
        assertEquals(RegionVisualTranslationController.Phase.ERROR,waiting.state(120).phase)
        assertNull(waiting.evidence(120)); assertFalse(waiting.failed(token,request,-1))
    }
    @Test fun clearDuringReadingRejectsDeliveryAndRequiresFreshExplicitRun() {
        val c=RegionVisualTranslationController(); val old=c.begin(version,100)!!
        c.clear(ClearReason.PAUSE)
        assertEquals(RegionVisualTranslationController.Phase.CANCELLED,c.state(101).phase)
        assertFalse(c.captured(old,meta(),association(),"en",roi,120,Long.MAX_VALUE))
        val next=c.begin(version.copy(snapshot=9),102)!!
        assertNotSame(old,next); assertFalse(c.owns(old)); assertTrue(c.owns(next))
        assertEquals(RegionVisualTranslationController.Phase.READING,c.state(102).phase)
    }
}
