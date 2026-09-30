package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Executes existing components against authored candidates, without reading semantic expectations.
 * Structural tests pass independently of the host's semantic gate. Never actual model inference. */
class SemanticContextEvaluationTest {
    private fun json(v:Any?):String=when(v) {
        null -> "null"
        is String -> "\""+buildString { v.forEach { c -> when(c) {
            '"' -> append("\\\"");'\\' -> append("\\\\");'\n' -> append("\\n");'\r' -> append("\\r");'\t' -> append("\\t")
            else -> if(c.code<32)append("\\u%04x".format(c.code)) else append(c)
        } } }+"\""
        is Boolean,is Int,is Long -> v.toString()
        is Double -> { require(v.isFinite());v.toString() }
        is Map<*,*> -> v.entries.joinToString(",","{","}") { json(it.key as String)+":"+json(it.value) }
        is Iterable<*> -> v.joinToString(",","[","]") { json(it) }
        else -> error("Unsupported diagnostic value")
    }
    private fun emit(v:Map<String,Any?>)=println("KD_SEMANTIC_EVAL "+Base64.getEncoder().encodeToString(json(v).toByteArray(Charsets.UTF_8)))
    private fun source(c:FullPageOcrContract.Candidate,label:String)=mapOf(
        "label" to label,"key" to RecordedFullPageTranslation.key(c),"text" to c.rawText,"strip" to c.provenance.stripIndex,
        "quad" to c.provenance.pageQuad.map { listOf(it.x,it.y) },"score" to c.provenance.detectorScore,
        "scoreBits" to c.provenance.detectorScore.toRawBits(),"id" to c.provenance.id)
    private fun diagnostic(a:FullPageOcrAssociation.Result,language:String,labels:Map<String,String>):Map<String,Any?> {
        val layout=FullPageSemanticLayout.derive(a,language)
        val keyed=a.rawCandidates.associate { RecordedFullPageTranslation.key(it) to labels.getValue(it.provenance.id) }
        return mapOf("published" to a.published,"orderUncertain" to layout.orderUncertain,
            "raw" to a.rawCandidates.map { source(it,labels.getValue(it.provenance.id)) },
            "groups" to layout.groups.map { g -> mapOf("labels" to g.memberKeys.map(keyed::getValue),
                "eligible" to g.eligible,"ambiguous" to g.ambiguous,"reasons" to g.reasons,"qualification" to g.qualification) },
            "associationGroups" to a.groups.map { g -> mapOf("labels" to g.memberIds.map(labels::getValue),
                "text" to g.text.name,"geometry" to g.geometry.name,"reasons" to g.reasons.map { it.name },"agreedRaw" to g.agreedRaw) },
            "associationEdges" to a.edges.map { e -> mapOf("left" to labels.getValue(e.leftId),"right" to labels.getValue(e.rightId),"kind" to e.kind.name) })
    }
    @Test fun authoredInputsPreserveAllFieldsAndObserveActualRequestTargets() {
        val cases=SemanticContextEvalCorpus.pages()
        assertEquals(24,cases.size)
        cases.forEachIndexed { pageIndex,p ->
            val version=CaptureVersion(1,2,pageIndex.toLong(),4,0,0)
            val plan=FullPageStripPlanner.plan(p.width,p.height)
            // Fixtures explicitly describe four strip observations, not captured pixels.
            assertEquals(4,plan.strips.size)
            val model=FullPageOcrContract.models.single { it.id==if(p.language.startsWith("zh"))"ch" else "latin" }
            val identity=FullPageOcrAssociation.PageIdentity("AUTHORED_CANDIDATES_NOT_OCR",version,p.id,model,
                "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
                if(model.id=="ch")"72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af"
                else "d80d308e9759b3ea3aaf6e57fa51b39ef0a2eeb3145a9b23df819f0f0279af44")
            val raw=p.items.mapIndexed { i,n ->
                val strip=plan.strips[n.strip]
                val local=n.quad.map { it.copy(y=it.y-strip.read.top) }
                FullPageOcrContract.Candidate(FullPageOcrContract.Provenance(version,p.id,n.strip,strip.read,strip.core,
                    i,i,i,i,local,n.quad,n.score,strip.ownsSourceCenter(n.quad.sumOf { it.x }/4,n.quad.sumOf { it.y }/4),
                    "authored/${p.id}/s${n.strip}/c$i-r$i-f$i"),model.id,n.text,320,40)
            }
            val labels=raw.mapIndexed { i,c -> c.provenance.id to p.items[i].label }.toMap()
            val receipts=plan.strips.map { s -> FullPageOcrAssociation.StripReceipt(s.index,s.read,s.core,true,raw.count { it.provenance.stripIndex==s.index }) }
            fun associate(input:List<FullPageOcrContract.Candidate>)=FullPageOcrAssociation.associate(identity,plan,
                FullPageOcrAssociation.UpstreamState.COMPLETE,receipts,input) { true }
            val a=associate(raw)
            assertTrue("${p.id}: ${a.rejection}",a.published)
            assertEquals(raw.associate { it.provenance.id to RecordedGroupedFullPageTranslation.candidateFields(it) },
                a.rawCandidates.associate { it.provenance.id to RecordedGroupedFullPageTranslation.candidateFields(it) })
            val reversed=associate(raw.reversed())
            assertEquals(a.groups,reversed.groups)
            assertEquals(FullPageSemanticLayout.derive(a,p.language),FullPageSemanticLayout.derive(reversed,p.language))
            val meta=RgbaFrameMetadata(p.width,p.height,version,101,60000)
            val adapted=FullPageTranslationAdapter.adapt(meta,a,p.language,true) as? FullPageTranslationAdapter.AdaptedPage
            assertNotNull(p.id,adapted)
            val page=checkNotNull(adapted)
            val probe=FullPageTranslationProbe(grouped=true)
            probe.observe(version);val ticket=checkNotNull(probe.click(100))
            assertTrue(p.id,probe.captured(ticket,meta,a,p.language,ContextRect(0.0,0.0,p.width.toDouble(),p.height.toDouble()),102,102))
            val request=probe.pending(103)
            val blockLabel=page.sourceMap.mapValues { labels.getValue(it.value.provenance.id) }
            val targets=request?.contract?.targets.orEmpty().map { t ->
                mapOf("labels" to t.sources.map { blockLabel.getValue(it.id) },"sourceText" to t.sources.joinToString(" ") { it.text })
            }
            val context=request?.contract?.screenContext ?: page.snapshot
            assertEquals(p.items.map { it.label }.toSet(),context.blocks.map { blockLabel.getValue(it.id) }.toSet())
            if(p.language.startsWith("zh"))assertNull(request)
            emit(diagnostic(a,p.language,labels)+mapOf("id" to p.id,"language" to p.language,"corpusSha256" to SemanticContextEvalCorpus.SHA,
                "track" to "AUTHORED_CANDIDATES_NOT_OCR","rawFieldsPreserved" to true,
                "requestTargets" to targets,"contextLabels" to context.blocks.map { blockLabel.getValue(it.id) },
                "rawInContext" to context.blocks.map { mapOf("label" to blockLabel.getValue(it.id),"text" to it.text) }))
            probe.clear(ClearReason.STOP)
            assertNull(probe.evidence(104))
        }
    }
    @Test fun archivedDuplicateTopologyIsReportedWithoutNewInferenceOrWinner() {
        val page=GroupedArchiveFixture.pages().single { it.id=="fr-seam" }
        val before=RecordedFullPageTranslation.fields(FullPageTranslationAdapter.Evidence(page.metadata,page.association),page.language)
        val labels=page.association.rawCandidates.associate { it.provenance.id to RecordedFullPageTranslation.key(it) }
        val record=diagnostic(page.association,page.language,labels)
        assertEquals(before,RecordedFullPageTranslation.fields(FullPageTranslationAdapter.Evidence(page.metadata,page.association),page.language))
        emit(record+mapOf("id" to page.id,"language" to page.language,"track" to "ARCHIVED_OCR_REPLAY_NOT_NEW_INFERENCE",
            "rawFieldsPreserved" to true))
    }
}
