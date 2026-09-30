package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test

/** Structural binding fixtures only: no assertion about recognition or translation quality. */
class FullPageTranslationProbeTest {
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
    private fun ready(language: String="en"): FullPageTranslationProbe {
        val p=FullPageTranslationProbe(); p.observe(version)
        val ticket=p.click(100)!!
        val model=if(language.startsWith("zh")) "ch" else "latin"
        val a=association(listOf(candidate("a",model=model),candidate("b",y=300.0,model=model)),model=model)
        assertTrue(p.captured(ticket,meta(),a,language,roi,120,120))
        return p
    }
    private fun response(r: FullPageTranslationProbe.ProbeRequest): FixtureResponse {
        val c=r.contract
        return FixtureResponse(c.id,c.page,c.targetLanguage,c.model,c.version,
            c.targets.mapIndexed { i,t -> FixtureAnswer(t,"任意有效译文$i") })
    }
    private fun adapted(a: FullPageOcrAssociation.Result=association(), language: String="en") =
        FullPageTranslationAdapter.adapt(meta(),a,language) as FullPageTranslationAdapter.AdaptedPage

    @Test fun retainsEveryCandidateAndOriginalIdentityOutsideRoiWithoutSemanticGroups() {
        val original=association(); val a=adapted(original)
        assertEquals(original.identity,a.originalEvidence.association.identity)
        assertEquals(meta(),a.originalEvidence.metadata)
        original.rawCandidates.zip(a.originalEvidence.association.rawCandidates).forEach { (x,y) ->
            assertTrue(FullPageCandidateEvidence.same(x,y)); assertNotSame(x.provenance,y.provenance)
        }
        assertEquals(original.edges,a.originalEvidence.association.edges)
        assertEquals(original.groups,a.originalEvidence.association.groups)
        assertEquals(2,a.sourceMap.size)
        assertTrue(a.snapshot.groups.isEmpty())
        assertTrue(a.snapshot.blocks.all { it.contextIds.isEmpty() && it.groupId==null && it.confidence==null })
        assertEquals(110,a.snapshot.capturedAt); assertEquals(1000,a.snapshot.ttlMillis)
        val p=ready(); val request=p.pending(120)!!
        assertEquals(2,request.contract.targets.size)
        assertEquals(2,request.contract.screenContext.blocks.size)
        assertEquals(1,p.render(120).cards.size)
        assertEquals("BINDING_TEST_ONLY_NO_TRANSLATION_MODEL",request.contract.model)
    }
    @Test fun uncertainEmptyWhitespaceAndConflictRemainExactAndIneligible() {
        val cs=listOf(candidate("empty",""),candidate("space","  ",y=200.0),
            candidate("same1","one",y=300.0),candidate("same2","one",x=101.0,y=300.0),
            candidate("cross1","left",y=580.0),candidate("cross2","right",strip=1,y=580.0))
        val a=adapted(association(cs))
        assertEquals(cs.associate { it.provenance.id to it.rawText },a.sourceMap.values.associate { it.provenance.id to it.rawText })
        assertTrue(a.snapshot.blocks.all { it.state!=BlockState.KNOWN && it.ocr!!.selectedModelIds.isEmpty() && it.ocr!!.reviewReason==null })
        assertTrue(a.snapshot.blocks.any { it.state==BlockState.CONFLICT })
        val p=FullPageTranslationProbe(); p.observe(version); val t=p.click(100)!!
        assertTrue(p.captured(t,meta(),association(cs),"en",roi,120,120)); assertNull(p.pending(120))
    }
    @Test fun consistentDuplicateCandidatesAreNeverDeduplicated() {
        val a=adapted(association(listOf(candidate("first",y=580.0),candidate("second",strip=1,y=580.0))))
        assertEquals(2,a.snapshot.blocks.size)
        assertTrue(a.snapshot.blocks.all { it.state==BlockState.KNOWN })
        assertTrue(a.snapshot.groups.isEmpty())
    }
    @Test fun longDelimiterContainingIdentitiesHaveBoundedDistinctTokens() {
        val a=adapted(association(listOf(candidate("x:|".repeat(1000)),candidate("x|:".repeat(1000),y=300.0))))
        assertEquals(2,a.snapshot.blocks.map { it.id }.toSet().size)
        assertTrue(a.snapshot.blocks.all { it.id.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(64,a.snapshot.identity.snapshotId.length)
        assertEquals(setOf("x:|".repeat(1000),"x|:".repeat(1000)),a.sourceMap.values.map { it.provenance.id }.toSet())
    }
    @Test fun explicitEmptyInvalidAndBudgetRejectionsAreAtomic() {
        fun reason(a: FullPageOcrAssociation.Result)= (FullPageTranslationAdapter.adapt(meta(),a,"en") as FullPageTranslationAdapter.Rejected).reason
        assertEquals(FullPageTranslationAdapter.Rejection.EMPTY,reason(association(emptyList())))
        val a=association()
        assertEquals(FullPageTranslationAdapter.Rejection.INVALID,reason(a.copy(published=false)))
        assertEquals(FullPageTranslationAdapter.Rejection.INVALID,reason(a.copy(groups=emptyList())))
        assertEquals(FullPageTranslationAdapter.Rejection.INVALID,reason(a.copy(identity=a.identity!!.copy(dictionarySha="wrong"))))
        assertEquals(FullPageTranslationAdapter.Rejection.BUDGET,reason(association(listOf(candidate("a","x".repeat(2001))))))
        assertTrue(adapted(association(listOf(candidate("a","x".repeat(2000))))).snapshot.blocks.single().text.length==2000)
        assertEquals(FullPageTranslationAdapter.Rejection.BUDGET,reason(a.copy(rawCandidates=List(129) { candidate("$it") })))
        assertEquals(FullPageTranslationAdapter.Rejection.BUDGET,reason(a.copy(rawCandidates=List(5) { candidate("$it","x".repeat(1700)) })))
        assertTrue(FullPageTranslationAdapter.adapt(meta(),a,"zh-Hans") is FullPageTranslationAdapter.Rejected)
        assertTrue(FullPageTranslationAdapter.adapt(meta(),a,"auto") is FullPageTranslationAdapter.Rejected)
        val degenerate=candidate("zero").let { c -> val p=c.provenance
            c.copy(provenance=FullPageOcrContract.Provenance(p.version,p.pageFixtureId,p.stripIndex,p.read,p.core,
                p.contourIndex,p.rawBoxIndex,p.finalBoxIndex,p.stripReadingOrder,List(4) { p.localQuad[0] },List(4) { p.pageQuad[0] },p.detectorScore,p.ownsCoreCenter,p.id)) }
        assertEquals(FullPageTranslationAdapter.Rejection.INVALID,reason(association(listOf(degenerate))))
    }
    @Test fun allSixVersionFieldsInvalidatePendingAndEvidence() {
        val alternatives=listOf(version.copy(session=9),version.copy(snapshot=9),version.copy(page=9),
            version.copy(revision=9),version.copy(window=9),version.copy(display=9))
        for(v in alternatives) {
            val p=ready(); val old=p.pending(120)!!; p.observe(v)
            assertNull(p.evidence(121)); assertNull(p.pending(121)); assertFalse(p.hasOriginal(121))
            assertFalse(p.accept(old,response(old),Long.MAX_VALUE)); assertNotNull(p.click(121))
        }
    }
    @Test fun acquisitionWorkerLowerBoundExpiryAndRollbackCannotRefreshSource() {
        fun rejected(m: RgbaFrameMetadata, validated: Long, now: Long) {
            val p=FullPageTranslationProbe(); p.observe(version); val t=p.click(100)!!
            assertFalse(p.captured(t,m,association(),"en",roi,validated,now)); assertFalse(p.hasOriginal(now))
        }
        rejected(meta(acquired=99),120,120)
        rejected(meta(),109,120)
        rejected(meta(),130,120)
        rejected(meta(ttl=10),120,120)
        val p=ready(); assertNotNull(p.evidence(1109)); assertNull(p.evidence(1110)); assertTrue(p.render(1110).cards.isEmpty())
        p.clear(ClearReason.STOP); assertNull(p.click(1109)); assertNotNull(p.click(1111))
        val q=ready(); assertNull(q.pending(119)); assertNull(q.evidence(120))
    }
    @Test fun firstClickAfterExpiryStartsFreshCaptureWithoutRequiringAReadOrSecondClick() {
        for (displayed in listOf(false,true)) {
            val p=ready(); val old=p.pending(120)!!
            if (displayed) assertTrue(p.accept(old,response(old),120))
            // No tick/render/getter occurs between 120 and this explicit click at original expiry.
            assertNotNull(p.click(1110))
            assertNull(p.evidence(1110)); assertNull(p.pending(1110))
            assertFalse(p.accept(old,response(old),Long.MAX_VALUE))
            assertNull(p.click(1110)) // Busy: the single click already started acquisition.
        }
    }
    @Test fun foreignAndLateCallbacksAreRejectedBeforeTheirClockAndCannotRevokeReplacement() {
        val p=ready(); val live=p.pending(120)!!
        val foreign=ready().pending(120)!!
        assertFalse(p.accept(foreign,response(foreign),Long.MAX_VALUE)); assertFalse(p.failed(foreign,-1))
        assertSame(live,p.pending(120))
        val capture=FullPageTranslationProbe(); capture.observe(version); val old=capture.click(100)!!
        capture.clear(ClearReason.MENU); val current=capture.click(101)!!
        assertFalse(capture.captured(old,meta(),association(),"en",roi,Long.MAX_VALUE,Long.MAX_VALUE))
        assertFalse(capture.failed(old,-1))
        assertTrue(capture.captured(current,meta(),association(),"en",roi,120,120))
    }
    @Test fun malformedMissingExtraAndSwappedBindingsRejectAtomicallyButTextSemanticsAreUnknowable() {
        val p=ready(); val r=p.pending(120)!!; val good=response(r)
        val bad=listOf(good.copy(answers=good.answers.drop(1)),good.copy(answers=good.answers+good.answers.first()),
            good.copy(requestId=good.requestId+1),good.copy(model="other"),
            good.copy(answers=listOf(good.answers[0].copy(binding=good.answers[1].binding),good.answers[1])),
            good.copy(answers=listOf(good.answers[0].copy(chinese=""),good.answers[1])),
            good.copy(answers=listOf(good.answers[0].copy(keepReason=KeepOriginalReason.ALREADY_CHINESE),good.answers[1])))
        for(b in bad) { assertFalse(p.accept(r,b,120)); assertSame(r,p.pending(120)); assertNull(p.render(120).cards.single().chinese) }
        // Swapping only answer text with unchanged bindings is not structurally detectable.
        assertTrue(p.accept(r,good.copy(answers=listOf(good.answers[0].copy(chinese=good.answers[1].chinese),
            good.answers[1].copy(chinese=good.answers[0].chinese))),120))
        assertNull(p.pending(120)); assertFalse(p.accept(r,good,-1)); assertNotNull(p.evidence(120))
    }
    @Test fun roiAndZoomReuseOneWholePageRequest() {
        val p=ready(); val r=p.pending(120)!!
        p.select(ContextRect(50.0,250.0,400.0,400.0),121)
        assertTrue(p.transform(MirrorTransform(5.0,300.0,200.0),121)); assertSame(r,p.pending(121))
        assertTrue(p.accept(r,response(r),121)); assertNull(p.pending(121))
        p.select(roi,122); assertEquals("任意有效译文0",p.render(122).cards.single().chinese)
    }
    @Test fun chineseHasNoProviderRequestAndClearReasonsDiscardEveryReadableSidecar() {
        for(language in listOf("zh-Hans","zh-Hant")) {
            val p=ready(language); assertNull(p.pending(120))
            val card=p.render(120).cards.single()
            assertEquals(AnswerKind.KEEP_ORIGINAL,card.kind); assertEquals(AnswerOrigin.SOURCE,card.origin)
            assertEquals("ALREADY_CHINESE",card.reason); assertNull(card.chinese)
        }
        for(reason in ClearReason.values()) {
            val p=ready(); p.clear(reason)
            assertNull(p.evidence(120)); assertNull(p.pending(120)); assertFalse(p.hasOriginal(120)); assertTrue(p.render(120).cards.isEmpty())
        }
    }
    @Test fun exactBlockAndCharacterBudgetsAreAcceptedWithoutDroppingCandidates() {
        val cs=(0..1).flatMap { strip -> (0 until 64).map { i ->
            candidate("$strip/$i",strip=strip,x=30.0+(i%8)*120,y=plan.strips[strip].core.top+30.0+(i/8)*35)
        } }
        assertEquals(128,adapted(association(cs)).snapshot.blocks.size)
        val chars=(0..4).map { i -> candidate("$i",if(i==4) "z".repeat(192) else "x".repeat(2000),y=50.0+i*40) }
        assertEquals(8192,adapted(association(chars)).snapshot.blocks.sumOf { it.text.length })
    }
    @Test fun structurallyChangedBindingAndMalformedGeometryCannotMasqueradeAsOriginal() {
        val p=ready("fr"); val r=p.pending(120)!!; val good=response(r)
        val first=good.answers.first(); val changed=first.binding.copy(sources=listOf(first.binding.sources.single().copy(text="changed")))
        assertFalse(p.accept(r,good.copy(answers=listOf(first.copy(binding=changed),good.answers[1])),120))
        assertSame(r,p.pending(120))
        val a=association()
        assertTrue(FullPageTranslationAdapter.adapt(meta(v=version.copy(snapshot=9)),a,"en") is FullPageTranslationAdapter.Rejected)
        assertTrue(FullPageTranslationAdapter.adapt(meta(),a.copy(edges=listOf(FullPageOcrAssociation.Edge("a","b",FullPageOcrAssociation.EdgeKind.STRONG))),"en") is FullPageTranslationAdapter.Rejected)
    }
    @Test fun allPublishedCollectionsAreImmutableAndIndependentOfCallerLists() {
        val original=association(); val raw=original.rawCandidates.toMutableList(); val groups=original.groups.toMutableList()
        val a=adapted(original.copy(rawCandidates=raw,groups=groups)); raw.clear(); groups.clear()
        assertEquals(2,a.sourceMap.size)
        fun immutable(block: () -> Unit) { assertThrows(UnsupportedOperationException::class.java,block) }
        immutable { (a.sourceMap as MutableMap).clear() }
        immutable { (a.snapshot.blocks as MutableList).clear() }
        immutable { (a.originalEvidence.association.rawCandidates as MutableList).clear() }
        immutable { (a.originalEvidence.association.groups as MutableList).clear() }
        immutable { (a.originalEvidence.association.edges as MutableList).clear() }
        immutable { (a.originalEvidence.association.groups.first().memberIds as MutableList).clear() }
        immutable { (a.originalEvidence.association.groups.first().reasons as MutableList).clear() }
        immutable { (a.sourceMap.values.first().provenance.pageQuad as MutableList).clear() }
        immutable { (a.sourceMap.values.first().provenance.localQuad as MutableList).clear() }
        immutable { (a.snapshot.blocks.first().ocr!!.candidates as MutableList).clear() }
        immutable { (a.snapshot.blocks.first().ocr!!.selectedModelIds as MutableList).clear() }
    }
    @Test fun slantedEvidenceIsPreservedAsUncertainWithoutDuplicatingSpatialRules() {
        val original=candidate("slanted")
        val p=original.provenance
        val local=p.localQuad.mapIndexed { i,q -> if(i==0) q.copy(x=q.x+3) else q }
        val whole=p.pageQuad.mapIndexed { i,q -> if(i==0) q.copy(x=q.x+3) else q }
        val changed=original.copy(provenance=FullPageOcrContract.Provenance(p.version,p.pageFixtureId,p.stripIndex,
            p.read,p.core,p.contourIndex,p.rawBoxIndex,p.finalBoxIndex,p.stripReadingOrder,local,whole,
            p.detectorScore,p.ownsCoreCenter,p.id))
        val a=adapted(association(listOf(changed)))
        assertTrue(FullPageCandidateEvidence.same(changed,a.sourceMap.values.single()))
        assertEquals(BlockState.AMBIGUOUS,a.snapshot.blocks.single().state)
        assertTrue(a.originalEvidence.association.groups.single().reasons.contains(FullPageOcrAssociation.Reason.NON_AXIS_ALIGNED))
        assertTrue(a.snapshot.coverageReasons.contains("OCR_COVERAGE_UNVERIFIED"))
        val engine=ContextEngine(); assertTrue(engine.activateSnapshot(a.snapshot,120)); engine.select(roi,120)
        assertNull(engine.requestMissing(120))
    }
}
