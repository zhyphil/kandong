package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test

/** Authored candidate tests, not OCR or provider inference. Semantic expectations stay here. */
class FullPageConditionGuardTest {
    private data class Input(val page:SemanticContextEvalCorpus.Page, val metadata:RgbaFrameMetadata,
        val association:FullPageOcrAssociation.Result, val labels:Map<String,String>)
    private fun input(p:SemanticContextEvalCorpus.Page):Input {
        val version=CaptureVersion(1,2,3,4,0,0)
        val plan=FullPageStripPlanner.plan(p.width,p.height)
        val model=FullPageOcrContract.models.single { it.id==if(p.language.startsWith("zh"))"ch" else "latin" }
        val id=FullPageOcrAssociation.PageIdentity("AUTHORED_CANDIDATES_NOT_OCR",version,p.id,model,
            "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
            if(model.id=="ch")"72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af"
            else "d80d308e9759b3ea3aaf6e57fa51b39ef0a2eeb3145a9b23df819f0f0279af44")
        val raw=p.items.mapIndexed { index,n ->
            val s=plan.strips[n.strip]
            FullPageOcrContract.Candidate(FullPageOcrContract.Provenance(version,p.id,n.strip,s.read,s.core,
                index,index,index,index,n.quad.map { it.copy(y=it.y-s.read.top) },n.quad,n.score,
                s.ownsSourceCenter(n.quad.sumOf { it.x }/4,n.quad.sumOf { it.y }/4),"authored/${p.id}/s${n.strip}/c$index-r$index-f$index"),
                model.id,n.text,320,40)
        }
        val receipts=plan.strips.map { s -> FullPageOcrAssociation.StripReceipt(s.index,s.read,s.core,true,raw.count { it.provenance.stripIndex==s.index }) }
        val a=FullPageOcrAssociation.associate(id,plan,FullPageOcrAssociation.UpstreamState.COMPLETE,receipts,raw) { true }
        assertTrue(a.published)
        assertEquals(raw.map(RecordedGroupedFullPageTranslation::candidateFields).toSet(),a.rawCandidates.map(RecordedGroupedFullPageTranslation::candidateFields).toSet())
        return Input(p,RgbaFrameMetadata(p.width,p.height,version,101,60000),a,raw.mapIndexed { index,c -> c.provenance.id to p.items[index].label }.toMap())
    }
    private fun all()=SemanticContextEvalCorpus.pages()+ConditionGuardCorpus.pages()
    private fun probe(i:Input,guard:Boolean=true):FullPageTranslationProbe {
        val p=FullPageTranslationProbe(grouped=true,conditionGuard=guard)
        p.observe(i.metadata.version);val ticket=checkNotNull(p.click(100))
        assertTrue(p.captured(ticket,i.metadata,i.association,i.page.language,ContextRect(0.0,0.0,1200.0,2400.0),102,102))
        return p
    }
    private fun labels(i:Input,p:FullPageTranslationProbe):List<Set<String>> {
        val ids=p.sourceMap(103).mapValues { i.labels.getValue(it.value.provenance.id) }
        return p.pending(103)?.contract?.targets.orEmpty().map { t -> t.sources.map { ids.getValue(it.id) }.toSet() }
    }
    @Test fun complexPagesNeverSendAnyFragmentsIncludingSplitModifierContinuations() {
        for(p in all().filter { ConditionGuardExpectations.pages.getValue(it.id).hold }) {
            val i=input(p);val guarded=probe(i)
            assertNull("${p.id}: complex page must keep original",guarded.pending(103))
            assertNotNull(guarded.evidence(103))
            assertEquals(p.items.size,guarded.sourceMap(103).size)
            val render=guarded.render(103)
            assertTrue(render.cards.isNotEmpty());assertTrue(render.cards.all { it.chinese==null })
        }
    }
    @Test fun semanticAccountingKeepsCoverageAndWithholdingSeparate() {
        for((name,pages) in listOf("regression" to SemanticContextEvalCorpus.pages(),"fresh" to ConditionGuardCorpus.pages())) {
            var unsafe=0;var correct=0;var withheld=0;var suppressedOrdinary=0
            for(p in pages) {
                val i=input(p);val actual=labels(i,probe(i));val baseline=labels(i,probe(i,false))
                val e=ConditionGuardExpectations.pages.getValue(p.id)
                val related=actual.filter { it.intersect(e.protected).isNotEmpty() }
                val bad=related.count { it !in e.complete };unsafe+=bad
                val ok=related.count { it in e.complete };correct+=ok
                val held=e.complete.count { g -> g !in actual && related.none { t -> t.intersect(g).isNotEmpty() } };withheld+=held
                val lost=baseline.count { it.intersect(e.protected).isEmpty() && it !in actual };suppressedOrdinary+=lost
                println("KD_GUARD_CASE\t$name\t${p.id}\t${p.items.size}\t$bad\t$ok\t$held\t$lost\t${actual.size}")
            }
            println("KD_GUARD_TOTAL\t$name\t${pages.size}\t$unsafe\t$correct\t$withheld\t$suppressedOrdinary")
            assertEquals("$name incomplete targets",0,unsafe)
            assertEquals("$name ordinary complete groups retained",if(name=="regression")10 else 4,correct)
            assertEquals("$name withheld groups",if(name=="regression")9 else 14,withheld)
        }
    }
    @Test fun preservesFullEvidenceContextAndUsesSeparateImmutableLayoutDomain() {
        for(p in all()) {
            val i=input(p);val before=RecordedGroupedFullPageTranslation.fields(FullPageTranslationAdapter.Evidence(i.metadata,i.association),p.language)
            val adapted=FullPageTranslationAdapter.adapt(i.metadata,i.association,p.language,true,true) as FullPageTranslationAdapter.AdaptedPage
            val legacy=FullPageTranslationAdapter.adapt(i.metadata,i.association,p.language,true) as FullPageTranslationAdapter.AdaptedPage
            assertNotEquals("guard has a separate request identity even without phrase targets",legacy.snapshot.identity,adapted.snapshot.identity)
            val layout=checkNotNull(adapted.layout)
            assertEquals(FullPageConditionGuard.VERSION,layout.version)
            assertEquals(FullPageSemanticLayout.derive(i.association,p.language).context,layout.context)
            assertEquals(p.items.map { it.text }.sorted(),adapted.snapshot.blocks.map { it.text }.sorted())
            assertEquals(i.association.rawCandidates.map(RecordedGroupedFullPageTranslation::candidateFields).toSet(),
                adapted.originalEvidence.association.rawCandidates.map(RecordedGroupedFullPageTranslation::candidateFields).toSet())
            assertEquals(before,RecordedGroupedFullPageTranslation.fields(FullPageTranslationAdapter.Evidence(i.metadata,i.association),p.language))
            assertEquals(layout,FullPageConditionGuard.derive(i.association.copy(rawCandidates=i.association.rawCandidates.reversed()),p.language))
            assertTrue(FullPageConditionGuard.VERSION in adapted.snapshot.coverageReasons)
            assertThrows(UnsupportedOperationException::class.java) { (layout.blockedKeys as MutableList<String>).add("bad") }
        }
    }
    @Test fun explicitOptInDoesNotChangeLegacyModeOrChineseRequests() {
        for(p in all()) {
            val i=input(p)
            val legacy=FullPageTranslationAdapter.adapt(i.metadata,i.association,p.language,true) as FullPageTranslationAdapter.AdaptedPage
            assertEquals(FullPageSemanticLayout.derive(i.association,p.language),legacy.layout)
            if(p.language.startsWith("zh"))assertNull(probe(i).pending(103))
        }
        val i=input(ConditionGuardCorpus.pages().first())
        assertTrue(FullPageTranslationAdapter.adapt(i.metadata,i.association,i.page.language,false,true) is FullPageTranslationAdapter.Rejected)
    }
    @Test fun archivedUnrelatedNegationIsReportedAsConservativeLossNotImprovedTranslation() {
        val p=GroupedArchiveFixture.pages().single { it.id=="fr-seam" }
        val original=FullPageSemanticLayout.derive(p.association,p.language)
        assertEquals(2,original.groups.count { it.eligible })
        val guarded=FullPageConditionGuard.derive(p.association,p.language)
        assertTrue(guarded.targets.isEmpty())
        assertEquals(original.context,guarded.context)
        assertEquals(original.orderedKeys,guarded.blockedKeys)
        println("KD_GUARD_ARCHIVE\tfr-seam\t20\t2\t0\tUNRELATED_TRAIN_NEGATION_PAGE_HOLD")
    }
    @Test fun guardedPageCannotConsumeValidV1RecordingEvenWhenNoHoldIsNeeded() {
        val i=input(ConditionGuardCorpus.pages().single { it.id=="fr-plain" });val p=i.page;val sha="a".repeat(64)
        fun request(guard:Boolean):FullPageTranslationProbe.ProbeRequest {
            val probe=FullPageTranslationProbe(RecordedGroupedFullPageTranslation.choice(sha),true,guard)
            probe.observe(i.metadata.version);val t=probe.click(100)!!
            assertTrue(probe.captured(t,i.metadata,i.association,p.language,ContextRect(0.0,0.0,1200.0,2400.0),102,102))
            return probe.pending(103)!!
        }
        val old=request(false)
        val outcomes=old.layout!!.targets.map { t -> RecordedGroupedFullPageTranslation.Outcome(t.key,t.memberKeys,t.sourceText,null,
            AnswerKind.KEEP_ORIGINAL,AnswerOrigin.SOURCE,if(t.memberKeys.size>1)KeepOriginalReason.CHECK_UNVERIFIED else KeepOriginalReason.NO_RECORDED_RESULT,
            if(t.memberKeys.size>1)"uncertain" else "unreviewed",if(t.memberKeys.size>1)"uncertain" else "unreviewed",false,
            if(t.memberKeys.size>1)"b".repeat(64) else null,if(t.memberKeys.size>1)"c".repeat(64) else null) }
        val recording=RecordedGroupedFullPageTranslation.Page(p.id,p.language,p.width,p.height,
            RecordedGroupedFullPageTranslation.fingerprint(old.evidence,p.language),RecordedGroupedFullPageTranslation.fields(old.evidence,p.language),
            outcomes.map { it.key },outcomes)
        assertEquals(old.contract.targets.size,RecordedGroupedFullPageTranslation.reply(old,recording,sha).answers.size)
        assertThrows(IllegalArgumentException::class.java) { RecordedGroupedFullPageTranslation.reply(request(true),recording,sha) }
    }
    @Test fun unchangedOriginalTtlSelectionAndClearRejectLateReplies() {
        val i=input(ConditionGuardCorpus.pages().single { it.id=="en-plain" })
        fun response(r:FullPageTranslationProbe.ProbeRequest)=r.contract.let { c ->
            FixtureResponse(c.id,c.page,c.targetLanguage,c.model,c.version,c.targets.map {
                FixtureAnswer(it,null,AnswerKind.KEEP_ORIGINAL,AnswerOrigin.SOURCE,KeepOriginalReason.NO_RECORDED_RESULT) }) }
        val p=probe(i);val r=p.pending(103)!!;val original=p.evidence(103)
        p.select(ContextRect(90.0,990.0,310.0,1020.0),104)
        assertSame(r,p.pending(104));assertSame(original,p.evidence(104));assertEquals(101L,original!!.metadata.acquiredAtMillis)
        assertTrue(p.accept(r,response(r),105));assertNull(p.pending(106))
        assertTrue(p.render(60101).cards.isEmpty());assertFalse(p.accept(r,response(r),60102));assertNull(p.evidence(60102))
        for(reason in listOf(ClearReason.MENU,ClearReason.PAUSE,ClearReason.STOP,ClearReason.PAGE_CHANGE)) {
            val next=probe(i);val pending=next.pending(103)!!;next.clear(reason)
            assertNull(next.pending(104));assertNull(next.evidence(104));assertTrue(next.render(104).cards.isEmpty())
            assertFalse(next.accept(pending,response(pending),105))
        }
    }
}
