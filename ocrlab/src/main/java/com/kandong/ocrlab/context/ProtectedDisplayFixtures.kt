package com.kandong.ocrlab.context

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.io.ByteArrayOutputStream

/** Recorded synthetic outcomes ONLY. No network, OCR, intent data or runtime translator. */
object ProtectedDisplayFixtures {
    const val SHA = "aa3c331e08272c9b1134a53736c2715c9dfdff2064030b02238ebe726f997c1f"
    val provider = TranslationProviderChoice(TranslationMode.LOCAL, "RECORDED_SYNTHETIC_MIX", SHA.take(16), configured = true)
    data class Outcome(val chinese: String?, val kind: AnswerKind, val origin: AnswerOrigin, val reason: KeepOriginalReason?)
    data class Page(val template: ScreenSnapshot, val targets: List<String>, val outcomes: Map<String, Outcome>) {
        val id get() = template.identity.snapshotId
        fun source(index: Int) = template.blocks.single { it.id == targets[index] }
        fun roi(index: Int) = source(index).visible
        fun snapshot(page: TranslationPage, now: Long) = template.copy(
            identity = ScreenIdentity(page.session, id, page.generation, page.revision, page.window, page.display), capturedAt = now)
        fun response(request: FixtureRequest): FixtureResponse {
            require(request.screenContext.blocks == template.blocks && request.screenContext.groups == template.groups && request.page.snapshotId == id)
            require(request.model == provider.model && request.version == provider.version)
            return FixtureResponse(request.id, request.page, request.targetLanguage, request.model, request.version,
                request.targets.map { target ->
                    require(target.sources.singleOrNull() == template.blocks.single { it.id == target.id })
                    val value = outcomes.getValue(target.id)
                    FixtureAnswer(target, value.chinese, value.kind, value.origin, value.reason)
                })
        }
    }
    private fun JSONArray.strings() = List(length()) { getString(it) }
    private fun JSONObject.rect() = getJSONArray("bounds").let { ContextRect(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3)) }
    fun load(context: Context): List<Page> {
        val raw = context.assets.open("protected-translation-display.json").use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 131072)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(raw.size <= 131072 && MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) } == SHA)
        val obj = JSONObject(String(raw, Charsets.UTF_8))
        require(obj.getBoolean("syntheticOnly") && !obj.getBoolean("qualityAccepted") && obj.getInt("schema") == 1)
        val pages = obj.getJSONArray("pages")
        return frozen(List(pages.length()) { i ->
            val value = pages.getJSONObject(i); val source = value.getJSONObject("source")
            val blocks = source.getJSONArray("blocks"); val groups = source.getJSONArray("groups")
            val viewport = source.getJSONArray("viewport"); val answers = value.getJSONArray("answers")
            val template = ScreenSnapshot(ScreenIdentity(0, source.getString("id"), 0, 0), viewport.getDouble(2), viewport.getDouble(3), 0, 60_000,
                List(blocks.length()) { j -> blocks.getJSONObject(j).let { b ->
                    ContextBlock(b.getString("id"), b.getString("text"), source.getString("sourceLanguage"), b.getString("role"),
                        b.rect(), b.rect(), b.getInt("readingOrder"), b.getString("groupId"))
                } }, List(groups.length()) { j -> groups.getJSONObject(j).let { g ->
                    SemanticGroup(g.getString("id"), GroupKind.CARD, g.getJSONArray("blockIds").strings())
                } }).freeze()
            val outcomes = (0 until answers.length()).associate { j -> answers.getJSONObject(j).let { a ->
                a.getString("id") to Outcome(if (a.isNull("chinese")) null else a.getString("chinese"),
                    AnswerKind.valueOf(a.getString("kind")), AnswerOrigin.valueOf(a.getString("origin")),
                    if (a.isNull("reason")) null else KeepOriginalReason.valueOf(a.getString("reason")))
            } }
            require(outcomes.keys == template.blocks.map { it.id }.toSet())
            Page(template, frozen(source.getJSONArray("targetIds").strings()), java.util.Collections.unmodifiableMap(outcomes))
        })
    }
}
