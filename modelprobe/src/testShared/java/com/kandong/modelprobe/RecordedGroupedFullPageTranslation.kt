package com.kandong.modelprobe

import com.kandong.ocrlab.context.*

/** A separate recording domain. No v1-context reuse, marker fallback, acquisition or TTL reset. */
internal object RecordedGroupedFullPageTranslation {
    const val MODEL="RECORDED_DEEPL_GROUPED_FULL_PAGE_V1"
    data class Outcome(val key:String,val memberKeys:List<String>,val sourceText:String,val chinese:String?,
        val kind:AnswerKind,val origin:AnswerOrigin,val reason:KeepOriginalReason?,val sourceQuality:String,
        val verdict:String,val rulePassed:Boolean,val rawResponseSha256:String?,val requestSha256:String?)
    data class Page(val id:String,val language:String,val width:Int,val height:Int,val fingerprint:String,
        val canonicalFields:List<String>,val targetKeys:List<String>,val outcomes:List<Outcome>)
    fun choice(sha:String):TranslationProviderChoice {
        require(sha.matches(Regex("[0-9a-f]{64}")))
        return TranslationProviderChoice(TranslationMode.LOCAL,MODEL,sha,configured=true)
    }
    fun fields(e:FullPageTranslationAdapter.Evidence,language:String):List<String> =
        RecordedFullPageTranslation.fields(e,language)+FullPageSemanticLayout.fields(FullPageSemanticLayout.derive(e.association,language))
    fun fingerprint(e:FullPageTranslationAdapter.Evidence,language:String)=FullPageSemanticLayout.digest(fields(e,language))
    fun candidateFields(c:FullPageOcrContract.Candidate):List<Any> = c.provenance.let { p ->
        listOf(c.modelId,c.rawText,c.recognitionWidth,c.recognitionTime,p.version,p.pageFixtureId,p.stripIndex,
            p.read,p.core,p.contourIndex,p.rawBoxIndex,p.finalBoxIndex,p.stripReadingOrder,p.localQuad,p.pageQuad,
            p.detectorScore.toRawBits(),p.ownsCoreCenter,p.id)
    }
    fun reply(request:FullPageTranslationProbe.ProbeRequest,page:Page,recordingSha:String):FixtureResponse {
        val c=request.contract;val e=request.evidence
        require(c.model==MODEL && c.version==recordingSha && choice(recordingSha).configured)
        require(page.id==e.association.identity?.pageFixtureId && page.language in setOf("en","fr"))
        require(page.width==e.metadata.width && page.height==e.metadata.height)
        val adapted=FullPageTranslationAdapter.adapt(e.metadata,e.association,page.language,true) as? FullPageTranslationAdapter.AdaptedPage
            ?: throw IllegalArgumentException("INVALID_GROUPED_EVIDENCE")
        val layout=checkNotNull(adapted.layout)
        require(request.layout==layout && request.sourceMap.mapValues { candidateFields(it.value) }==adapted.sourceMap.mapValues { candidateFields(it.value) } && c.screenContext==adapted.snapshot) { "CURRENT_GROUPED_CONTEXT_MISMATCH" }
        val fields=fields(e,page.language)
        require(page.canonicalFields==fields && page.fingerprint==FullPageSemanticLayout.digest(fields)) { "RECORDED_GROUPED_PAGE_MISMATCH" }
        require(page.targetKeys==layout.targets.map { it.key } && page.outcomes.map { it.key }==page.targetKeys &&
            c.targets.size==layout.targets.size) { "RECORDED_GROUPED_TARGET_MISMATCH" }
        val byKey=adapted.sourceMap.entries.associate { RecordedFullPageTranslation.key(it.value) to it.key }
        val blocks=adapted.snapshot.blocks.associateBy { it.id }
        val answers=c.targets.mapIndexed { i,binding ->
            val expected=layout.targets[i];val result=page.outcomes[i]
            val ids=expected.memberKeys.map { byKey.getValue(it) }
            require(binding.sources==ids.map { blocks.getValue(it) } && binding.context.isEmpty() && binding.reason==null)
            val grouped=ids.size>1
            require(binding.id==(if(grouped)binding.sources.first().groupId else ids.single()) &&
                binding.groupId==(if(grouped)binding.id else null) &&
                binding.groupKind==(if(grouped)GroupKind.PHRASE else null) && binding.role==(if(grouped)"phrase" else "unclassified"))
            require(result.memberKeys==expected.memberKeys && result.sourceText==expected.sourceText)
            val answer=FixtureAnswer(binding,result.chinese,result.kind,result.origin,result.reason)
            require(answer.validOutcome())
            if(grouped) {
                require(result.sourceQuality in setOf("correct","incorrect","uncertain") && result.verdict in setOf("pass","fail","uncertain"))
                require(listOf(result.rawResponseSha256,result.requestSha256).all { it?.matches(Regex("[0-9a-f]{64}"))==true })
                val allowed=result.sourceQuality=="correct" && result.verdict=="pass" && result.rulePassed
                require(if(allowed) result.kind==AnswerKind.CANDIDATE && result.origin==AnswerOrigin.RECORDED_DEEPL
                    else result.kind==AnswerKind.KEEP_ORIGINAL && result.reason==KeepOriginalReason.CHECK_UNVERIFIED)
            } else {
                require(result.kind==AnswerKind.KEEP_ORIGINAL && result.reason==KeepOriginalReason.NO_RECORDED_RESULT &&
                    result.sourceQuality=="unreviewed" && result.verdict=="unreviewed" && !result.rulePassed &&
                    result.rawResponseSha256==null && result.requestSha256==null)
            }
            answer
        }
        return FixtureResponse(c.id,c.page,c.targetLanguage,c.model,c.version,answers)
    }
}
