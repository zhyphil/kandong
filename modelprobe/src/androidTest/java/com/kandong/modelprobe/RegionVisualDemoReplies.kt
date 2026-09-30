package com.kandong.modelprobe

import android.os.Handler
import android.os.Looper
import com.kandong.ocrlab.context.FixtureAnswer
import com.kandong.ocrlab.context.FixtureResponse
import java.lang.ref.WeakReference

/** Injectable asynchronous, local binding markers. This never invokes a translation service. */
internal interface RegionVisualDemoReplies {
    fun interface Handle { fun cancel() }
    interface Receiver {
        fun completed(run: RegionVisualTranslationController.Run, request: FullPageTranslationProbe.ProbeRequest,
            response: FixtureResponse?)
    }
    fun schedule(run: RegionVisualTranslationController.Run, request: FullPageTranslationProbe.ProbeRequest,
        receiver: Receiver): Handle

    companion object {
        fun markers(request: FullPageTranslationProbe.ProbeRequest): FixtureResponse {
            val c=request.contract
            val ordinals=request.evidence.association.rawCandidates.withIndex().associate { it.value.provenance.id to it.index+1 }
            return FixtureResponse(c.id,c.page,c.targetLanguage,c.model,c.version,c.targets.map { target ->
                val source=checkNotNull(request.sourceMap[target.id])
                FixtureAnswer(target,"绑定演示 ${ordinals.getValue(source.provenance.id)}")
            })
        }
    }
}

internal class HandlerRegionVisualDemoReplies : RegionVisualDemoReplies {
    private val handler=Handler(Looper.getMainLooper())
    override fun schedule(run: RegionVisualTranslationController.Run, request: FullPageTranslationProbe.ProbeRequest,
        receiver: RegionVisualDemoReplies.Receiver): RegionVisualDemoReplies.Handle {
        val task=Pending(handler,run,request,receiver)
        check(handler.postDelayed(task,350))
        return task
    }
    private class Pending(private val handler: Handler, run: RegionVisualTranslationController.Run,
        request: FullPageTranslationProbe.ProbeRequest, receiver: RegionVisualDemoReplies.Receiver) : Runnable, RegionVisualDemoReplies.Handle {
        private var run: RegionVisualTranslationController.Run?=run
        private var request: FullPageTranslationProbe.ProbeRequest?=request
        private val receiver=WeakReference(receiver)
        override fun cancel() { handler.removeCallbacks(this); run=null; request=null; receiver.clear() }
        override fun run() {
            val token=run ?: return
            val req=request ?: return
            val target=receiver.get()
            cancel() // Release all queued references before invoking the weak subscriber.
            val response=try { RegionVisualDemoReplies.markers(req) } catch (_: Exception) { null }
            target?.completed(token,req,response)
        }
    }
}
