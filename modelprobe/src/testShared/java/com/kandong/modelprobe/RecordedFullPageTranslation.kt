package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Recorded synthetic evidence only. Matching does not acquire pixels, refresh TTL, or certify
 * meaning. The current Probe/Bridge remains the owner of the live request and original receipt. */
internal object RecordedFullPageTranslation {
    const val MODEL="RECORDED_DEEPL_FULL_PAGE_V1"
    data class Outcome(val key:String,val sourceText:String,val chinese:String?,val kind:AnswerKind,
        val origin:AnswerOrigin,val reason:KeepOriginalReason?)
    data class Page(val id:String,val language:String,val width:Int,val height:Int,val fingerprint:String,
        val targetKeys:List<String>,val outcomes:List<Outcome>)
    fun choice(sha:String):TranslationProviderChoice {
        require(sha.matches(Regex("[0-9a-f]{64}")))
        return TranslationProviderChoice(TranslationMode.LOCAL,MODEL,sha,configured=true)
    }
    fun key(c:FullPageOcrContract.Candidate):String = c.provenance.let {
        "s${it.stripIndex}/c${it.contourIndex}-r${it.rawBoxIndex}-f${it.finalBoxIndex}"
    }
    private fun digest(fields:List<String>):String {
        val hash=MessageDigest.getInstance("SHA-256")
        fields.forEach { field -> val bytes=field.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes) }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    /** All content, source geometry, scores, models and associations survive; only the explicitly
     * variable historical run/batch/version/time is excluded. No text-based deduplication. */
    fun fields(e:FullPageTranslationAdapter.Evidence,language:String):List<String> {
        val a=e.association; val id=checkNotNull(a.identity)
        val raw=a.rawCandidates
        require(raw.map(::key).distinct().size==raw.size)
        val keys=raw.associate { it.provenance.id to key(it) }
        val out=mutableListOf("recorded-full-page-v1",id.pageFixtureId,language,
            e.metadata.width.toString(),e.metadata.height.toString(),id.model.id,id.model.sha,
            id.model.vocabulary.toString(),id.detectorSha,id.dictionarySha)
        fun ints(vararg values:Int) { values.forEach { out.add(it.toString()) } }
        fun rect(r:FullPageStripPlanner.Rect) { ints(r.left,r.top,r.right,r.bottom) }
        fun bits(v:Double) { require(v.isFinite()); out.add(v.toRawBits().toString()) }
        raw.forEach { c -> val p=c.provenance
            out.addAll(listOf("candidate",key(c),c.modelId,c.rawText)); ints(p.stripIndex)
            rect(p.read);rect(p.core);ints(p.contourIndex,p.rawBoxIndex,p.finalBoxIndex,p.stripReadingOrder)
            (p.localQuad+p.pageQuad).forEach { bits(it.x);bits(it.y) }
            bits(p.detectorScore);out.add(if(p.ownsCoreCenter) "1" else "0")
            ints(c.recognitionWidth,c.recognitionTime)
        }
        a.groups.sortedBy { g -> g.memberIds.map { keys.getValue(it) }.sorted().joinToString("\n") }.forEach { g ->
            val members=g.memberIds.map { keys.getValue(it) }.sorted()
            out.add("group");ints(members.size);out.addAll(members)
            out.add(g.text.name);out.add(g.geometry.name);ints(g.reasons.size)
            out.addAll(g.reasons.map { it.name }.sorted())
        }
        a.edges.map { listOf(keys.getValue(it.leftId),keys.getValue(it.rightId),it.kind.name) }
            .sortedWith(compareBy<List<String>>({it[0]},{it[1]},{it[2]})).forEach { out.add("edge");out.addAll(it) }
        out.add("context");out.add(raw.joinToString("\n") { it.rawText })
        return out
    }
    fun fingerprint(e:FullPageTranslationAdapter.Evidence,language:String)=digest(fields(e,language))
    fun reply(request:FullPageTranslationProbe.ProbeRequest,page:Page,recordingSha:String):FixtureResponse {
        val c=request.contract;val e=request.evidence
        require(c.model==MODEL && c.version==recordingSha && choice(recordingSha).configured)
        require(page.id==e.association.identity?.pageFixtureId && page.language in setOf("en","fr"))
        require(page.width==e.metadata.width && page.height==e.metadata.height)
        require(c.screenContext.blocks.all { it.language==page.language })
        require(page.fingerprint==fingerprint(e,page.language)) { "RECORDED_PAGE_MISMATCH" }
        require(c.screenContext.blocks.map { it.text }==e.association.rawCandidates.map { it.rawText })
        val targets=c.targets.map { key(request.sourceMap.getValue(it.id)) }
        require(targets.distinct().size==targets.size && page.targetKeys==targets)
        require(page.outcomes.map { it.key }==targets) { "RECORDED_TARGET_MISMATCH" }
        val answers=c.targets.zip(page.outcomes).map { (binding,result) ->
            require(binding.sources.size==1 && binding.sources.single().text==result.sourceText)
            val answer=FixtureAnswer(binding,result.chinese,result.kind,result.origin,result.reason)
            require(answer.validOutcome() && result.origin!=AnswerOrigin.HANDCRAFTED_FIXTURE)
            answer
        }
        return FixtureResponse(c.id,c.page,c.targetLanguage,c.model,c.version,answers)
    }
}
