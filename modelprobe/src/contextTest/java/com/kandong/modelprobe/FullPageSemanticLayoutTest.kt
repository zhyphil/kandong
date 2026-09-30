package com.kandong.modelprobe

import com.kandong.modelprobe.FullPageOcrContract.Candidate
import com.kandong.ocrlab.context.*
import org.junit.Assert.*
import org.junit.Test

class FullPageSemanticLayoutTest {
    private fun fr()=GroupedArchiveFixture.pages()[1]
    private fun pair():FullPageOcrAssociation.Result {
        val a=fr().association
        val raw=a.rawCandidates.filter { RecordedFullPageTranslation.key(it) in listOf("s0/c3-r3-f3","s0/c2-r2-f2") }
        return a.copy(rawCandidates=raw,groups=a.groups.filter { g -> g.memberIds.all { id -> raw.any { it.provenance.id==id } } })
    }
    private fun change(c:Candidate,q:List<GeometryProbeContract.Point> = c.provenance.pageQuad,id:String=c.provenance.id):Candidate {
        val p=c.provenance
        return c.copy(provenance=FullPageOcrContract.Provenance(p.version,p.pageFixtureId,p.stripIndex,p.read,p.core,
            p.contourIndex,p.rawBoxIndex,p.finalBoxIndex,p.stripReadingOrder,p.localQuad,q,p.detectorScore,p.ownsCoreCenter,id))
    }
    @Test fun actualArchiveFieldsMatchIndependentPythonAndAllRawEvidenceSurvives() {
        GroupedArchiveFixture.pages().forEach { p ->
            assertTrue(p.association.published)
            val old=FullPageTranslationAdapter.adapt(p.metadata,p.association,p.language) as FullPageTranslationAdapter.AdaptedPage
            val new=FullPageTranslationAdapter.adapt(p.metadata,p.association,p.language,true) as FullPageTranslationAdapter.AdaptedPage
            assertEquals(RecordedFullPageTranslation.fields(old.originalEvidence,p.language),RecordedFullPageTranslation.fields(new.originalEvidence,p.language))
            assertEquals(old.originalEvidence.metadata,new.originalEvidence.metadata)
            assertEquals(old.sourceMap.mapValues { RecordedGroupedFullPageTranslation.candidateFields(it.value) },
                new.sourceMap.mapValues { RecordedGroupedFullPageTranslation.candidateFields(it.value) })
            assertEquals(p.expectedFields,RecordedGroupedFullPageTranslation.fields(new.originalEvidence,p.language))
            assertEquals(p.expectedFingerprint,RecordedGroupedFullPageTranslation.fingerprint(new.originalEvidence,p.language))
            assertEquals(p.association.rawCandidates.map { it.rawText }.sorted(),new.snapshot.blocks.map { it.text }.sorted())
            val engine=ContextEngine();assertTrue(engine.activateSnapshot(new.snapshot,102))
        }
        val layout=FullPageSemanticLayout.derive(fr().association,"fr")
        assertEquals(listOf(true,false,true),layout.groups.map { it.eligible })
        assertEquals(listOf(2,4,2),layout.groups.map { it.memberKeys.size })
        assertTrue(layout.orderUncertain)
        assertTrue(layout.groups.filter { it.eligible }.all { it.qualification==listOf(FullPageSemanticLayout.QUALIFICATION) })
        assertEquals(20,layout.context.lines().size);assertTrue(layout.context.contains("Pnx totar"))
        assertThrows(UnsupportedOperationException::class.java) { (layout.orderedKeys as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (layout.groups[0].memberKeys as MutableList).clear() }
    }
    @Test fun columnsGapInterveningOverlapDuplicateClipAndTiltDoNotBecomeEligible() {
        for(mode in listOf("column","gap","intervening","overlap","duplicate","clip","tilt","unsupported")) {
            var a=pair();val head=a.rawCandidates.single { FullPageSemanticLayout.head(it.rawText,"fr") }
            val tail=a.rawCandidates.single { FullPageSemanticLayout.tail(it.rawText) }
            if(mode in listOf("column","gap","tilt"))a=a.copy(rawCandidates=a.rawCandidates.map { c ->
                when {
                    c==tail && mode=="column" -> change(c,c.provenance.pageQuad.map { it.copy(x=it.x+100) })
                    c==tail && mode=="gap" -> change(c,c.provenance.pageQuad.map { it.copy(y=it.y+60) })
                    c==head && mode=="tilt" -> change(c,c.provenance.pageQuad.mapIndexed { i,q -> if(i==1)q.copy(y=q.y+20) else q })
                    else -> c
                }
            })
            if(mode in listOf("intervening","overlap","duplicate")) {
                val q=if(mode=="intervening")listOf(GeometryProbeContract.Point(70.0,313.0),GeometryProbeContract.Point(100.0,313.0),
                    GeometryProbeContract.Point(100.0,320.0),GeometryProbeContract.Point(70.0,320.0)) else tail.provenance.pageQuad
                // Unique provenance key, even for identical pixel/text evidence.
                val p=tail.provenance
                val competitor=Candidate(FullPageOcrContract.Provenance(p.version,p.pageFixtureId,p.stripIndex,p.read,p.core,
                    99,99,99,99,p.localQuad,q,p.detectorScore,p.ownsCoreCenter,"competitor"),tail.modelId,
                    if(mode=="duplicate")tail.rawText else "other",tail.recognitionWidth,tail.recognitionTime)
                a=a.copy(rawCandidates=a.rawCandidates+competitor,groups=a.groups+FullPageOcrAssociation.Group("extra",listOf("competitor"),
                    FullPageOcrAssociation.Text.SINGLE,FullPageOcrAssociation.Geometry.ISOLATED,emptyList(),null))
            }
            if(mode in listOf("clip","unsupported"))a=a.copy(groups=a.groups.map { it.copy(reasons=listOf(
                if(mode=="clip")FullPageOcrAssociation.Reason.POSSIBLE_CLIP else FullPageOcrAssociation.Reason.UNSUPPORTED_GEOMETRY)) })
            assertFalse(mode,FullPageSemanticLayout.derive(a,"fr").groups.any { it.eligible })
        }
    }
    @Test fun exactQuadOrientationConvexityAndSlopeBoundary() {
        val c=pair().rawCandidates.first()
        fun quad(vararg xy:Double)=xy.toList().chunked(2).map { GeometryProbeContract.Point(it[0],it[1]) }
        assertTrue(FullPageSemanticLayout.nearHorizontal(change(c,quad(0.0,0.0,100.0,2.0,100.0,32.0,0.0,30.0))))
        assertFalse(FullPageSemanticLayout.nearHorizontal(change(c,quad(0.0,0.0,100.0,2.001,100.0,32.0,0.0,30.0))))
        assertTrue(FullPageSemanticLayout.nearHorizontal(change(c,quad(0.0,0.0,100.0,0.0,101.5,30.0,1.5,30.0))))
        assertFalse(FullPageSemanticLayout.nearHorizontal(change(c,quad(0.0,0.0,100.0,0.0,101.501,30.0,1.5,30.0))))
        assertFalse(FullPageSemanticLayout.nearHorizontal(change(c,c.provenance.pageQuad.reversed())))
        assertFalse(FullPageSemanticLayout.nearHorizontal(change(c,c.provenance.pageQuad.let { listOf(it[0],it[2],it[1],it[3]) })))
    }
    @Test fun renamingAndPermutationNeverResolveAmbiguityOrChangeSemanticOrder() {
        val a=fr().association;val expected=FullPageSemanticLayout.derive(a,"fr")
        val mapping=a.rawCandidates.mapIndexed { i,c -> c.provenance.id to "renamed-${100-i}" }.toMap()
        val renamed=a.copy(rawCandidates=a.rawCandidates.reversed().map { change(it,id=mapping.getValue(it.provenance.id)) },
            groups=a.groups.map { g -> g.copy(memberIds=g.memberIds.map { mapping.getValue(it) }) })
        assertEquals(expected,FullPageSemanticLayout.derive(renamed,"fr"))
    }
    @Test fun incompleteAndUnsupportedHeadsNeverRequestStandaloneConfirmation() {
        for(text in listOf("Aucun remboursement après seulement","Aucun remboursement sauf après","Aucun remboursement après.")) {
            val a=pair();val changed=a.copy(rawCandidates=a.rawCandidates.map { if(FullPageSemanticLayout.head(it.rawText,"fr"))it.copy(rawText=text) else it })
            assertTrue(FullPageSemanticLayout.derive(changed,"fr").targets.isEmpty())
        }
    }
}
