package com.kandong.modelprobe

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kandong.ocrlab.context.*
import java.util.Collections
import java.util.concurrent.Executors

/** Cached test loader owns this one process-wide native lane; no Activity or surface is stored. */
internal object RegionVisualOcrRuntime {
    val slot = RegionOcrSlot()
    private val executor = Executors.newSingleThreadExecutor { command -> Thread(command, "fixed-page-ocr") }
    private val handler = Handler(Looper.getMainLooper())
    fun worker(command: () -> Unit) { executor.execute(command) }
    fun main(command: () -> Unit) { check(handler.post(command)) }
}

internal class RegionVisualOcrBackend(application: Context, fixtureAssets: AssetManager,
    val recorded:Boolean=false,
    afterInferenceForProbe: () -> Unit = {}) : RegionVisualSession.Backend, RegionVisualDemoReplies.Receiver {
    private val fixtureAssetsForReplies=fixtureAssets
    private val runner = FullPageVisualOcrRunner(application.applicationContext, fixtureAssets,afterInferenceForProbe)
    private val bridge = RegionOcrBridge<RegionOcrBridge.Evidence<FullPageVisualOcrRunner.Result>>(RegionVisualOcrRuntime.slot,
        RegionVisualOcrRuntime::worker, RegionVisualOcrRuntime::main, SystemClock::elapsedRealtime,
        { error("EXPLICIT_PAGE_REQUIRED") })
    var grouped=false
        private set
    var translation=if(recorded) RegionVisualTranslationController(RecordedFullPageTranslation.choice(RecordedRegionVisualReplies.SHA))
        else RegionVisualTranslationController()
        private set
    var startMode=RegionVisualSession.StartMode.OCR_ONLY
        private set
    private var scheduler: RegionVisualDemoReplies=if(recorded) RecordedRegionVisualReplies.scheduler(fixtureAssets) else HandlerRegionVisualDemoReplies()
    private var handle: RegionVisualDemoReplies.Handle?=null
    private var scheduledRun: RegionVisualTranslationController.Run?=null
    private var scheduledRequest: FullPageTranslationProbe.ProbeRequest?=null
    private var run: RegionVisualTranslationController.Run?=null
    private var receipt: RegionOcrBridge.Evidence<FullPageVisualOcrRunner.Result>?=null
    private var awaiting=false
    private var disposed=false
    private var language="en"
    override val pages = Collections.unmodifiableList(FullPageVisualOcrRunner.PAGES.map {
        RegionVisualSession.Page(it.id, it.width, it.height)
    })
    /** Set before a start; preserves the existing trailing afterInferenceForProbe constructor. */
    fun installReplySchedulerForProbe(value: RegionVisualDemoReplies) {
        check(!busy() && !disposed); scheduler=value
    }
    /** Explicit UI selection only. Cancelled JNI still owns the native slot until it returns. */
    fun chooseGrouped(value:Boolean):Boolean {
        if(disposed || !recorded || busy() || RegionVisualOcrRuntime.slot.busy())return false
        if(grouped==value)return true
        cancel();grouped=value
        translation=if(value) RegionVisualTranslationController(RecordedGroupedFullPageTranslation.choice(RecordedGroupedRegionVisualReplies.SHA),true)
            else RegionVisualTranslationController(RecordedFullPageTranslation.choice(RecordedRegionVisualReplies.SHA))
        scheduler=if(value) RecordedGroupedRegionVisualReplies.scheduler(fixtureAssetsForReplies) else RecordedRegionVisualReplies.scheduler(fixtureAssetsForReplies)
        startMode=RegionVisualSession.StartMode.OCR_ONLY
        return true
    }
    override fun start(pageIndex: Int)=start(pageIndex,RegionVisualSession.StartMode.OCR_ONLY)
    override fun start(pageIndex: Int, mode: RegionVisualSession.StartMode): Boolean {
        if(disposed || busy()) return false // No mode/preparation/load changes for rejected clicks.
        val spec=FullPageVisualOcrRunner.PAGES[pageIndex]
        val prepared=runner.prepare(spec)
        val token=if(mode==RegionVisualSession.StartMode.BINDING_DEMO)
            translation.begin(prepared.version,SystemClock.elapsedRealtime()) ?: return false else null
        if(token==null) translation.clear(ClearReason.STOP)
        cancelReply(); receipt=null; run=token; startMode=mode; language=spec.language
        val workerRunner=runner
        val started=bridge.start { ticket ->
            val original=workerRunner.run(prepared,ticket)
            RegionOcrBridge.Evidence(original,original.acquiredAtMillis,original.ttlMillis,original.validatedAtMillis)
        }
        awaiting=started
        if(!started && token!=null) { translation.failed(token,SystemClock.elapsedRealtime()); run=null }
        return started
    }
    private fun cancelReply() {
        handle?.cancel(); handle=null; scheduledRun=null; scheduledRequest=null
    }
    private fun revokeDelivery() {
        cancelReply(); receipt=null; run=null; awaiting=false; bridge.cancel()
    }
    private fun pump(): RegionOcrBridge.Evidence<FullPageVisualOcrRunner.Result>? {
        if(disposed) return null
        val delivered=bridge.value()
        if(delivered!=null) awaiting=false
        if(bridge.error()!=null) {
            awaiting=false
            val token=run
            if(token!=null && translation.owns(token)) translation.clear(when(bridge.error()) {
                "EXPIRED_OR_INVALID" -> ClearReason.EXPIRED
                "CLOCK_INVALID" -> ClearReason.CLOCK_INVALID
                else -> ClearReason.INVALID_SNAPSHOT
            })
            cancelReply(); receipt=null; run=null
            return null // Keep bridge.error native-only and intact for existing OCR tests.
        }
        if(startMode==RegionVisualSession.StartMode.OCR_ONLY) return delivered
        val now=SystemClock.elapsedRealtime()
        val state=translation.state(now)
        if(state.phase in setOf(RegionVisualTranslationController.Phase.EXPIRED,
            RegionVisualTranslationController.Phase.ERROR,RegionVisualTranslationController.Phase.CANCELLED)) {
            revokeDelivery(); return null
        }
        val token=run ?: return null
        if(delivered!=null && receipt==null && translation.owns(token)) {
            // The exact original receipt stays owned by the bridge throughout adaptation.
            if(bridge.value() !== delivered) return null
            val result=delivered.value
            if(!translation.captured(token,result.metadata,result.page,language,
                ContextRect(0.0,0.0,result.metadata.width.toDouble(),result.metadata.height.toDouble()),
                delivered.validatedAtMillis,now)) { revokeDelivery(); return null }
            receipt=delivered
            val pending=translation.pending(now)
            if(pending!=null) {
                scheduledRun=token; scheduledRequest=pending
                try {
                    val newHandle=scheduler.schedule(token,pending,this)
                    if(scheduledRun===token && scheduledRequest===pending) handle=newHandle else newHandle.cancel()
                } catch (_: Exception) { completed(token,pending,null) }
            }
        }
        if(receipt!=null && delivered !== receipt) {
            translation.clear(ClearReason.EXPIRED); revokeDelivery(); return null
        }
        return receipt
    }
    override fun completed(token: RegionVisualTranslationController.Run, request: FullPageTranslationProbe.ProbeRequest,
        response: FixtureResponse?) {
        // Identity first: a stale callback cannot read the clock or touch a replacement handle.
        if(disposed || scheduledRun!==token || scheduledRequest!==request || !translation.owns(token,request)) return
        val now=SystemClock.elapsedRealtime()
        cancelReply()
        if(response==null) translation.failed(token,request,now) else translation.reply(token,request,response,now)
        pump()
    }
    override fun evidence()=pump()?.value?.display
    fun result()=pump()?.value
    fun originalReceiptForProbe()=pump()
    override fun busy(): Boolean {
        pump()
        return bridge.busy() || awaiting ||
            (startMode==RegionVisualSession.StartMode.BINDING_DEMO && translation.busy(SystemClock.elapsedRealtime()))
    }
    override fun error()=bridge.error()
    override fun cancel() { translation.clear(ClearReason.STOP); revokeDelivery() }
    override fun dispose() { cancel(); bridge.dispose(); disposed=true }
}
