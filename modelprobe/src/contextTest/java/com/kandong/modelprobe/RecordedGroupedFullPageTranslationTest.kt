package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import org.junit.Assert.*
import org.junit.Test

class RecordedGroupedFullPageTranslationTest {
    private val sha="a".repeat(64)
    private fun request():FullPageTranslationProbe.ProbeRequest {
        val p=GroupedArchiveFixture.pages()[1]
        val probe=FullPageTranslationProbe(RecordedGroupedFullPageTranslation.choice(sha),true)
        probe.observe(GroupedArchiveFixture.version);val t=probe.click(100)!!
        assertTrue(probe.captured(t,p.metadata,p.association,p.language,ContextRect(0.0,0.0,1000.0,500.0),102,102))
        return probe.pending(102)!!
    }
    private fun page(r:FullPageTranslationProbe.ProbeRequest):RecordedGroupedFullPageTranslation.Page {
        val layout=r.layout!!
        val outcomes=layout.targets.map { t -> RecordedGroupedFullPageTranslation.Outcome(t.key,t.memberKeys,t.sourceText,null,
            AnswerKind.KEEP_ORIGINAL,AnswerOrigin.SOURCE,if(t.memberKeys.size==2)KeepOriginalReason.CHECK_UNVERIFIED else KeepOriginalReason.NO_RECORDED_RESULT,
            if(t.memberKeys.size==2)"uncertain" else "unreviewed",if(t.memberKeys.size==2)"uncertain" else "unreviewed",false,
            if(t.memberKeys.size==2)"b".repeat(64) else null,if(t.memberKeys.size==2)"c".repeat(64) else null) }
        return RecordedGroupedFullPageTranslation.Page("fr-seam","fr",1176,2400,
            RecordedGroupedFullPageTranslation.fingerprint(r.evidence,"fr"),RecordedGroupedFullPageTranslation.fields(r.evidence,"fr"),
            outcomes.map { it.key },outcomes)
    }
    private fun changed(r:FullPageTranslationProbe.ProbeRequest,c:FixtureRequest=r.contract,
        e:FullPageTranslationAdapter.Evidence=r.evidence,layout:FullPageSemanticLayout.Layout?=r.layout)=
        FullPageTranslationProbe.ProbeRequest(c,e,r.sourceMap,layout)
    private fun reject(action:()->Unit)=assertThrows(IllegalArgumentException::class.java,action)
    @Test fun wholeCurrentPageMemberTargetsAndOrderedContextBindStrictly() {
        val r=request();val p=page(r)
        val reply=RecordedGroupedFullPageTranslation.reply(r,p,sha)
        assertEquals(8,reply.answers.size);assertEquals(2,reply.answers.count { it.binding.sources.size==2 })
        assertTrue(reply.answers.all { it.chinese==null })
        reject { RecordedGroupedFullPageTranslation.reply(changed(r,layout=r.layout!!.copy(version="other")),p,sha) }
        reject { RecordedGroupedFullPageTranslation.reply(changed(r,layout=r.layout!!.copy(orderedKeys=r.layout.orderedKeys.reversed())),p,sha) }
        reject { RecordedGroupedFullPageTranslation.reply(changed(r,c=r.contract.copy(screenContext=r.contract.screenContext.copy(blocks=r.contract.screenContext.blocks.reversed()))),p,sha) }
        reject { RecordedGroupedFullPageTranslation.reply(changed(r,c=r.contract.copy(targets=r.contract.targets.reversed())),p,sha) }
        val target=r.contract.targets.first { it.sources.size==2 }
        reject { RecordedGroupedFullPageTranslation.reply(changed(r,c=r.contract.copy(targets=r.contract.targets.map {
            if(it===target)it.copy(sources=it.sources.reversed()) else it })),p,sha) }
        val a=r.evidence.association
        reject { RecordedGroupedFullPageTranslation.reply(changed(r,e=r.evidence.copy(association=a.copy(rawCandidates=a.rawCandidates.mapIndexed {
            i,c -> if(i==0)c.copy(rawText="changed outside") else c }))),p,sha) }
    }
    @Test fun wrongPageSourceResultKindAndReviewCannotCreateCandidate() {
        val r=request();val p=page(r)
        listOf(p.copy(targetKeys=p.targetKeys.reversed()),p.copy(outcomes=p.outcomes.reversed()),p.copy(width=1000),
            p.copy(canonicalFields=p.canonicalFields.dropLast(1)),p.copy(fingerprint="0".repeat(64)),
            p.copy(outcomes=p.outcomes.map { it.copy(memberKeys=it.memberKeys.reversed(),sourceText="bad") })).forEach {
            reject { RecordedGroupedFullPageTranslation.reply(r,it,sha) }
        }
        val group=p.outcomes.indexOfFirst { it.memberKeys.size==2 }
        fun replace(o:RecordedGroupedFullPageTranslation.Outcome)=p.copy(outcomes=p.outcomes.mapIndexed { i,v -> if(i==group)o else v })
        val candidate=p.outcomes[group].copy(kind=AnswerKind.CANDIDATE,origin=AnswerOrigin.RECORDED_DEEPL,
            chinese="结构测试占位",reason=null,sourceQuality="correct",verdict="pass",rulePassed=true)
        assertEquals("结构测试占位",RecordedGroupedFullPageTranslation.reply(r,replace(candidate),sha).answers[group].chinese)
        for(bad in listOf(candidate.copy(origin=AnswerOrigin.HANDCRAFTED_FIXTURE),candidate.copy(kind=AnswerKind.LOCAL_DATE),
            candidate.copy(sourceQuality="incorrect"),candidate.copy(verdict="uncertain"),candidate.copy(rulePassed=false),
            candidate.copy(rawResponseSha256=null),candidate.copy(sourceText="confirmation."))) {
            reject { RecordedGroupedFullPageTranslation.reply(r,replace(bad),sha) }
        }
        val singleton=p.outcomes.indexOfFirst { it.memberKeys.size==1 }
        reject { RecordedGroupedFullPageTranslation.reply(r,p.copy(outcomes=p.outcomes.mapIndexed { i,v -> if(i==singleton)
            v.copy(kind=AnswerKind.CANDIDATE,origin=AnswerOrigin.RECORDED_DEEPL,chinese="old context",reason=null) else v }),sha) }
        reject { RecordedGroupedFullPageTranslation.reply(r,p,"d".repeat(64)) }
    }
    @Test fun tailRoiFullCardAnchorsAndLifecycleNeverReacquireOrExtendOriginalTtl() {
        val p=GroupedArchiveFixture.pages()[1];val controller=RegionVisualTranslationController(RecordedGroupedFullPageTranslation.choice(sha),true)
        val run=controller.begin(GroupedArchiveFixture.version,100)!!
        val tail=ContextRect(65.0,322.0,270.0,360.0)
        assertTrue(controller.captured(run,p.metadata,p.association,"fr",tail,102,102))
        val pending=controller.pending(102)!!;val receipt=controller.evidence(102)
        val response=RecordedGroupedFullPageTranslation.reply(pending,page(pending),sha)
        assertTrue(controller.reply(run,pending,response,103))
        val card=controller.render(tail,MirrorTransform(1.0,1000.0,500.0),104)
        assertEquals(1,card.cards.size);assertTrue(card.cards.single().sourceText.startsWith("Aucun remboursement"))
        assertEquals(1,card.anchors.size)
        val whole=controller.render(ContextRect(60.0,270.0,500.0,360.0),MirrorTransform(1.0,1000.0,500.0),105)
        assertEquals(1,whole.cards.size);assertEquals(2,whole.anchors.size)
        assertEquals(2,controller.targetMembers(105).getValue(whole.cards.single().targetId).size)
        val middle=controller.render(ContextRect(60.0,1160.0,500.0,1250.0),MirrorTransform(1.0,1000.0,500.0),106)
        assertEquals(1,middle.cards.size);assertEquals("AMBIGUOUS_GROUP",middle.cards.single().reason)
        assertEquals(4,middle.anchors.size);assertEquals(4,controller.targetMembers(106).getValue(middle.cards.single().targetId).size)
        controller.render(tail,MirrorTransform(3.0,1000.0,500.0,5.0,5.0),107)
        assertSame(receipt,controller.evidence(107));assertNull(controller.pending(107))
        assertEquals(101L,controller.evidence(107)!!.metadata.acquiredAtMillis)
        assertTrue(controller.render(tail,MirrorTransform(1.0,1000.0,500.0),60101).cards.isEmpty())
        assertFalse(controller.reply(run,pending,response,60102))
        for((index,reason) in listOf(ClearReason.STOP,ClearReason.MENU,ClearReason.PAUSE,ClearReason.PAGE_CHANGE).withIndex()) {
            val now=60200L+index*10
            val next=controller.begin(GroupedArchiveFixture.version,now)!!
            controller.clear(reason);assertFalse(controller.owns(next));assertNull(controller.evidence(now+1))
            assertTrue(controller.render(tail,MirrorTransform(1.0,1000.0,500.0),now+1).cards.isEmpty())
        }
    }
}
