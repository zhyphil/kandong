package com.kandong.modelprobe

import android.os.Handler
import android.os.Looper
import android.content.res.AssetManager
import com.kandong.ocrlab.context.*
import org.json.JSONObject
import java.security.MessageDigest
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

internal class HandlerRegionVisualDemoReplies(
    private val reply:(FullPageTranslationProbe.ProbeRequest)->FixtureResponse=RegionVisualDemoReplies::markers
) : RegionVisualDemoReplies {
    private val handler=Handler(Looper.getMainLooper())
    override fun schedule(run: RegionVisualTranslationController.Run, request: FullPageTranslationProbe.ProbeRequest,
        receiver: RegionVisualDemoReplies.Receiver): RegionVisualDemoReplies.Handle {
        val task=Pending(handler,run,request,receiver,reply)
        check(handler.postDelayed(task,350))
        return task
    }
    private class Pending(private val handler: Handler, run: RegionVisualTranslationController.Run,
        request: FullPageTranslationProbe.ProbeRequest, receiver: RegionVisualDemoReplies.Receiver,
        private val reply:(FullPageTranslationProbe.ProbeRequest)->FixtureResponse) : Runnable, RegionVisualDemoReplies.Handle {
        private var run: RegionVisualTranslationController.Run?=run
        private var request: FullPageTranslationProbe.ProbeRequest?=request
        private val receiver=WeakReference(receiver)
        override fun cancel() { handler.removeCallbacks(this); run=null; request=null; receiver.clear() }
        override fun run() {
            val token=run ?: return
            val req=request ?: return
            val target=receiver.get()
            cancel() // Release all queued references before invoking the weak subscriber.
            val response=try { reply(req) } catch (_: Exception) { null }
            target?.completed(token,req,response)
        }
    }
}

/** Bounded, pinned test APK asset. The host made the calls; Android performs no networking. */
internal object RecordedRegionVisualReplies {
    const val ASSET="full-page-translation-recorded-v1.json"
    const val SHA="3ca858be7e1b99e78b4f6903b6998ad50a9ee6366e357947ff8c8c49c81e369f"
    fun scheduler(assets:AssetManager)=HandlerRegionVisualDemoReplies { request ->
        val bytes=assets.open(ASSET).use { ProbeInputs.bounded(it,262144) }
        val actual=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual==SHA) { "RECORDING_HASH" }
        val root=JSONObject(String(bytes,Charsets.UTF_8))
        require(root.getInt("schema")==1 && root.getBoolean("syntheticOnly") && !root.getBoolean("qualityAccepted"))
        require(root.getString("provider")=="DeepL" && root.getString("mode")=="RECORDED")
        val list=root.getJSONArray("pages")
        require(list.length()==4)
        val pages=(0 until list.length()).map { list.getJSONObject(it) }
        require(pages.map { it.getString("id") }.toSet()==setOf("en-normal","fr-seam","hans-normal","hant-seam"))
        val page=pages.single { it.getString("id")==request.evidence.association.identity?.pageFixtureId }
        val targetKeys=page.getJSONArray("targetKeys").let { a -> (0 until a.length()).map { a.getString(it) } }
        val outcomes=page.getJSONArray("outcomes").let { a -> (0 until a.length()).map { i ->
            val v=a.getJSONObject(i)
            RecordedFullPageTranslation.Outcome(v.getString("key"),v.getString("sourceText"),
                if(v.isNull("chinese")) null else v.getString("chinese"),AnswerKind.valueOf(v.getString("kind")),
                AnswerOrigin.valueOf(v.getString("origin")),if(v.isNull("reason")) null else KeepOriginalReason.valueOf(v.getString("reason")))
        } }
        RecordedFullPageTranslation.reply(request,RecordedFullPageTranslation.Page(page.getString("id"),
            page.getString("language"),page.getInt("width"),page.getInt("height"),page.getString("fingerprint"),
            targetKeys,outcomes),SHA)
    }
}
