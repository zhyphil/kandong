package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureVersion

/** Owner-thread fixed-page binding experiment, with no provider execution, IO or semantic checker.
 * captured MUST be called only after successful RegionOcrBridge owner-thread delivery, while that
 * original owner's evidence remains current. This class checks metadata, never claims resource
 * cleanup authority. The owner must observe every full version change and clear on lifecycle loss.
 */
internal class FullPageTranslationProbe(provider: TranslationProviderChoice = TranslationProviderChoice(TranslationMode.LOCAL,
    "BINDING_TEST_ONLY_NO_TRANSLATION_MODEL", "fixed-page-contract-v1", configured=true)) {
    class Ticket internal constructor(internal val version: CaptureVersion, internal val capture: OnDemandTranslation.Capture)
    class ProbeRequest internal constructor(val contract: FixtureRequest,
        val evidence: FullPageTranslationAdapter.Evidence,
        val sourceMap: Map<String, FullPageOcrContract.Candidate>)
    private val owner = Thread.currentThread()
    private val translation = OnDemandTranslation(provider)
    private var version: CaptureVersion? = null
    private var ticket: Ticket? = null
    private var page: FullPageTranslationAdapter.AdaptedPage? = null
    private var request: ProbeRequest? = null
    private var lastNow = -1L
    private fun own() = check(Thread.currentThread() === owner) { "OWNER_THREAD" }
    fun observe(value: CaptureVersion) {
        own()
        if (version == value) return
        clear(ClearReason.PAGE_CHANGE)
        version = value.copy()
        translation.observePage(TranslationPage(value.session,value.page,value.revision,value.window.toString(),value.display.toString()))
    }
    fun clear(reason: ClearReason) {
        own(); ticket=null; request=null; page=null
        translation.invalidate(reason) // Neither time history nor observed identity is reset.
    }
    private fun advance(now: Long): Boolean {
        if (now < lastNow || now < 0 || now > Long.MAX_VALUE - ContextEngine.MAX_TTL) {
            clear(ClearReason.CLOCK_INVALID); return false
        }
        lastNow=now
        translation.tick(now)
        if (translation.phase != OnDemandTranslation.Phase.CAPTURING) ticket=null
        if (page != null && (page!!.originalEvidence.metadata.version != version || translation.snapshot == null)) {
            // Expiry revokes old evidence but does not invalidate this valid clock reading.
            // A fresh explicit click may continue immediately; getters still see no old page.
            clear(ClearReason.EXPIRED)
        }
        return true
    }
    fun click(now: Long): Ticket? {
        own()
        if (!advance(now)) return null
        val current=version ?: return null
        if (listOf(current.session,current.snapshot,current.page,current.revision,current.window.toLong(),current.display.toLong()).any { it < 0 }) return null
        val capture=translation.clickTranslate(now) ?: return null
        page=null; request=null
        return Ticket(current,capture).also { ticket=it }
    }
    fun captured(token: Ticket, metadata: RgbaFrameMetadata, association: FullPageOcrAssociation.Result,
        declaredLanguage: String, roi: ContextRect, validatedAtMillis: Long, now: Long): Boolean {
        own()
        if (token !== ticket) return false // Foreign/late callbacks cannot move the active clock.
        if (validatedAtMillis >= 0 && validatedAtMillis <= Long.MAX_VALUE - ContextEngine.MAX_TTL)
            lastNow=maxOf(lastNow,validatedAtMillis)
        if (!advance(now) || token !== ticket) return false
        if (token.version != version || metadata.version != token.version ||
            metadata.acquiredAtMillis < token.capture.clickedAt || validatedAtMillis < metadata.acquiredAtMillis ||
            validatedAtMillis > now || validatedAtMillis < 0 || now >= metadata.acquiredAtMillis + metadata.ttlMillis) {
            clear(ClearReason.INVALID_SNAPSHOT); return false
        }
        val adapted=FullPageTranslationAdapter.adapt(metadata,association,declaredLanguage)
        if (adapted !is FullPageTranslationAdapter.AdaptedPage ||
            !translation.captured(token.capture,adapted.snapshot,roi,now)) {
            clear(ClearReason.INVALID_SNAPSHOT); return false
        }
        ticket=null; page=adapted
        val contract=translation.pending
        if (contract != null && declaredLanguage in setOf("zh-Hans","zh-Hant")) {
            val response=FixtureResponse(contract.id,contract.page,contract.targetLanguage,contract.model,contract.version,
                contract.targets.map { FixtureAnswer(it,null,AnswerKind.KEEP_ORIGINAL,AnswerOrigin.SOURCE,KeepOriginalReason.ALREADY_CHINESE) })
            if (!translation.accept(response,now)) { clear(ClearReason.INVALID_SNAPSHOT); return false }
        } else if (contract != null) request=ProbeRequest(contract,adapted.originalEvidence,adapted.sourceMap)
        return true
    }
    fun pending(now: Long): ProbeRequest? { own(); return if(advance(now)) request else null }
    fun evidence(now: Long): FullPageTranslationAdapter.Evidence? { own(); return if(advance(now)) page?.originalEvidence else null }
    fun hasOriginal(now: Long): Boolean = evidence(now) != null
    fun phase(now: Long): OnDemandTranslation.Phase {
        own(); return if(advance(now)) translation.phase else OnDemandTranslation.Phase.FAILED
    }
    fun sourceMap(now: Long): Map<String, FullPageOcrContract.Candidate> {
        own(); return if (advance(now)) page?.sourceMap ?: emptyMap() else emptyMap()
    }
    fun select(roi: ContextRect, now: Long): ContextSelection? {
        own(); return if(advance(now) && page != null) translation.select(roi,now) else null
    }
    fun transform(value: MirrorTransform, now: Long): Boolean {
        own(); return advance(now) && page != null && translation.setTransform(value,now)
    }
    fun render(now: Long): ContextRender {
        own()
        return if(advance(now) && page != null) translation.render(now) else ContextRender(0,0,emptyList(),emptyList())
    }
    /** Identity-bound failures, just like successes, are checked before observing callback time. */
    fun failed(token: Ticket, now: Long): Boolean {
        own(); if(token !== ticket) return false
        if(!advance(now) || token !== ticket) return false
        clear(ClearReason.INVALID_SNAPSHOT); return true
    }
    fun failed(pending: ProbeRequest, now: Long): Boolean {
        own(); if(pending !== request) return false
        if(!advance(now) || pending !== request) return false
        clear(ClearReason.PROVIDER_CHANGE); return true
    }
    /** Exact request/binding/outcome validation is delegated unchanged. Text meaning is not checked. */
    fun accept(pending: ProbeRequest, response: FixtureResponse, now: Long): Boolean {
        own(); if(pending !== request) return false
        if(!advance(now) || pending !== request || !translation.accept(response,now)) return false
        request=null; return true
    }
}
