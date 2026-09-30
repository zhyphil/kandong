package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureVersion

/** Presentation only. Probe remains the sole validator of source identity, bindings and TTL. */
internal class RegionVisualTranslationController(provider: TranslationProviderChoice = TranslationProviderChoice(TranslationMode.LOCAL,
    "BINDING_TEST_ONLY_NO_TRANSLATION_MODEL", "fixed-page-contract-v1", configured=true), grouped: Boolean = false) {
    enum class Phase { IDLE, READING, WAITING, READY, ERROR, EXPIRED, CANCELLED }
    enum class Display { ORIGINAL, DEMO }
    class Run internal constructor(internal val ticket: FullPageTranslationProbe.Ticket)
    data class State(val phase: Phase, val display: Display, val revision: Long)
    private val probe=FullPageTranslationProbe(provider,grouped)
    private var run: Run?=null
    private var request: FullPageTranslationProbe.ProbeRequest?=null
    private var phase=Phase.IDLE
    private var display=Display.DEMO
    private var revision=0L
    private val owner=Thread.currentThread()
    private fun own()=check(Thread.currentThread() === owner)
    private fun transition(next: Phase) { if (phase!=next) { phase=next; revision++ } }
    private fun tick(now: Long) {
        own()
        val p=probe.phase(now)
        if (phase==Phase.READING && p!=OnDemandTranslation.Phase.CAPTURING ||
            (phase==Phase.WAITING || phase==Phase.READY) && !probe.hasOriginal(now)) {
            clear(if(p==OnDemandTranslation.Phase.FAILED) ClearReason.CLOCK_INVALID else ClearReason.EXPIRED)
        }
    }
    fun state(now: Long): State { tick(now); return State(phase,display,revision) }
    fun busy(now: Long): Boolean { tick(now); return phase==Phase.READING || phase==Phase.WAITING }
    fun begin(version: CaptureVersion, now: Long): Run? {
        own(); if(busy(now)) return null
        probe.observe(version)
        val ticket=probe.click(now) ?: run { clear(ClearReason.INVALID_SNAPSHOT); return null }
        request=null; display=Display.DEMO; revision++
        return Run(ticket).also { run=it; transition(Phase.READING) }
    }
    fun owns(token: Run): Boolean { own(); return token===run }
    fun owns(token: Run, pending: FullPageTranslationProbe.ProbeRequest): Boolean = owns(token) && pending===request
    fun captured(token: Run, metadata: RgbaFrameMetadata, association: FullPageOcrAssociation.Result,
        language: String, roi: ContextRect, validatedAtMillis: Long, now: Long): Boolean {
        own(); if(!owns(token) || phase!=Phase.READING) return false
        if(!probe.captured(token.ticket,metadata,association,language,roi,validatedAtMillis,now)) {
            clear(ClearReason.INVALID_SNAPSHOT); return false
        }
        request=probe.pending(now)
        transition(if(request==null) Phase.READY else Phase.WAITING)
        return true
    }
    fun pending(now: Long): FullPageTranslationProbe.ProbeRequest? { tick(now); return request }
    fun reply(token: Run, pending: FullPageTranslationProbe.ProbeRequest, response: FixtureResponse, now: Long): Boolean {
        own(); if(!owns(token,pending)) return false
        if(!probe.accept(pending,response,now)) {
            tick(now)
            if(owns(token,pending)) clear(ClearReason.INVALID_SNAPSHOT)
            return false
        }
        request=null; transition(Phase.READY); return true
    }
    fun failed(token: Run, now: Long): Boolean {
        own(); if(!owns(token) || phase!=Phase.READING) return false
        val accepted=probe.failed(token.ticket,now)
        tick(now); if(accepted) clear(ClearReason.INVALID_SNAPSHOT)
        return accepted
    }
    fun failed(token: Run, pending: FullPageTranslationProbe.ProbeRequest, now: Long): Boolean {
        own(); if(!owns(token,pending)) return false
        val accepted=probe.failed(pending,now)
        tick(now); if(accepted) clear(ClearReason.INVALID_SNAPSHOT)
        return accepted
    }
    fun choose(value: Display, now: Long) {
        tick(now); if(display!=value) { display=value; revision++ }
    }
    fun sourceMap(now: Long): Map<String,FullPageOcrContract.Candidate> { tick(now); return probe.sourceMap(now) }
    fun targetMembers(now: Long): Map<String,List<String>> { tick(now); return probe.targetMembers(now) }
    fun evidence(now: Long): FullPageTranslationAdapter.Evidence? { tick(now); return probe.evidence(now) }
    fun render(roi: ContextRect, transform: MirrorTransform, now: Long): ContextRender {
        tick(now)
        if(phase!=Phase.WAITING && phase!=Phase.READY) return ContextRender(0,0,emptyList(),emptyList())
        probe.select(roi,now); probe.transform(transform,now)
        val result=probe.render(now)
        return if(display==Display.DEMO) result else result.copy(cards=result.cards.map { it.copy(chinese=null) })
    }
    fun clear(reason: ClearReason) {
        own(); probe.clear(reason); run=null; request=null; revision++
        transition(when(reason) {
            ClearReason.EXPIRED -> Phase.EXPIRED
            ClearReason.INVALID_SNAPSHOT, ClearReason.CLOCK_INVALID, ClearReason.PROVIDER_CHANGE -> Phase.ERROR
            else -> Phase.CANCELLED
        })
    }
}
