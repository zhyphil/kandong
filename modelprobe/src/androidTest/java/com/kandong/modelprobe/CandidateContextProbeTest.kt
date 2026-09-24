package com.kandong.modelprobe

import android.os.Build
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.ocrlab.context.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
internal class CandidateContextProbeTest {
    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    @Test fun freshOcrEvidenceSurvivesSelectionAndExpiresWithItsPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = AtomicFile(File(instrumentation.targetContext.filesDir, "candidate-context-probe-report.json"))
        val pages = JSONArray(); val errors = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString())
            .put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL).put("passed", false)
            .put("status", "started").put("pages", pages).put("errors", errors)
            .put("scope", "Fresh packaged synthetic OCR to the existing context engine; manually declared phrase grouping, undetermined language, no translation model or real screens")
        fun save() {
            val stream = file.startWrite()
            try { stream.write(report.toString(2).toByteArray()); file.finishWrite(stream) }
            catch (e: Throwable) { file.failWrite(stream); throw e }
        }
        save()
        try {
            val args = InstrumentationRegistry.getArguments()
            val expectedId = checkNotNull(args.getString("e2eRunId")); val expectedSha = checkNotNull(args.getString("e2eReportSha256"))
            val raw = File(instrumentation.targetContext.filesDir, "end-to-end-ocr-probe-report.json")
                .inputStream().use { ProbeInputs.bounded(it, 524288) }
            ProbeInputs.verifyHash(raw, expectedSha)
            val e2e = JSONObject(String(raw, Charsets.UTF_8))
            check(e2e.getString("runId") == expectedId && e2e.getBoolean("passed") && e2e.getString("status") == "passed")
            val manifestRaw = instrumentation.context.assets.open("detector-v1/manifest.json").use { ProbeInputs.bounded(it, 131072) }
            ProbeInputs.verifyHash(manifestRaw, "74aa1e39c8d3187ee2388ad07bb228e3c876c0486ccd02c47208942d26f2c80a")
            val fixtures = objects(JSONObject(String(manifestRaw, Charsets.UTF_8)).getJSONArray("cases"))
            val sources = objects(e2e.getJSONArray("cases")); val models = objects(e2e.getJSONArray("models"))
            check(sources.size == 10 && models.size == 10)
            var count = 0
            for (source in sources.take(5)) {
                val id = source.getString("id"); val pageId = source.getString("sourceBgrSha256")
                fun rows(model: String) = objects(models.single { it.getString("id") == "$model/$id" }.getJSONArray("bindings")).map { b ->
                    val q = b.getJSONArray("quad")
                    SharedCandidates.Candidate(pageId, b.getString("boxId"), b.getInt("readingOrder"),
                        (0 until q.length()).map { n -> val p = q.getJSONArray(n); SharedCandidates.Point(p.getDouble(0), p.getDouble(1)) }, b.getString("raw"))
                }
                val ch = rows("ch"); val latin = rows("latin")
                val f = fixtures.single { it.getString("id") == id }; val width = f.getDouble("sourceWidth"); val height = f.getDouble("sourceHeight")
                val engine = ContextEngine(); val identity = ScreenIdentity(1, pageId, 1, 1)
                val groups = listOf(SemanticGroup("manual.phrase", GroupKind.PHRASE, ch.sortedBy { it.readingOrder }.map { it.boxId }))
                check(CandidateContext.activate(engine, identity, width, height, 100, 60_000, ch.reversed(), latin, groups, 100))
                val snapshot = checkNotNull(engine.snapshot); check(snapshot.blocks.size == ch.size)
                val encoded = JSONArray()
                snapshot.blocks.forEach { b ->
                    val c = ch.single { it.boxId == b.id }; val l = latin.single { it.boxId == b.id }; val evidence = checkNotNull(b.ocr)
                    check(evidence.pageId == pageId && b.order == c.readingOrder && b.language == "und" && b.confidence == null)
                    check(evidence.candidates == listOf(OcrCandidate("ch", c.raw), OcrCandidate("latin", l.raw)))
                    check(evidence.quad == c.quad.map { ContextPoint(it.x, it.y) })
                    check(evidence.reviewReason == null)
                    encoded.put(JSONObject().put("id", b.id).put("pageId", pageId).put("readingOrder", b.order)
                        .put("quad", JSONArray(evidence.quad.map { listOf(it.x, it.y) })).put("ch", c.raw).put("latin", l.raw)
                        .put("raw", b.text).put("selectedModelIds", JSONArray(evidence.selectedModelIds)).put("language", b.language))
                }
                val first = snapshot.blocks.first().visible
                val roi = first.copy(right = first.left + first.width / 2)
                engine.select(roi, 100)
                check(engine.selection!!.targets.single().sources.map { it.id } == snapshot.blocks.map { it.id })
                val request = checkNotNull(engine.requestMissing(100))
                val answer = FixtureResponse(request.id, request.page, request.targetLanguage, request.model, request.version,
                    request.targets.map { FixtureAnswer(it, "人工占位，不是模型译文") })
                check(engine.setTransform(MirrorTransform(3.0, 240.0, 120.0), 100))
                engine.select(snapshot.blocks.last().visible, 100); check(engine.snapshot === snapshot)
                check(engine.acceptResponse(answer, 100)); check(engine.render(100).cards.single().chinese == "人工占位，不是模型译文")
                check(CandidateContext.activate(engine, identity.copy(pageGeneration = 2), width, height, 100, 60_000, ch, latin, groups, 100))
                check(!engine.acceptResponse(answer, 100) && engine.cacheCount == 0 && engine.selection == null)
                engine.select(roi, 100); check(!engine.tick(60_100) && engine.snapshot == null && engine.render(60_100).cards.isEmpty())
                count += snapshot.blocks.size
                pages.put(JSONObject().put("id", id).put("width", width).put("height", height).put("blocks", encoded)
                    .put("sourceCount", ch.size).put("manuallyGrouped", true).put("versionAndExpiryPassed", true).put("passed", true))
            }
            check(pages.length() == 5 && count == 9)
            report.put("freshE2eRunId", expectedId).put("freshE2eReportSha256", expectedSha)
                .put("freshPages", pages.length()).put("freshBlocks", count).put("status", "passed").put("passed", true)
        } catch (e: Exception) { errors.put(e.stackTraceToString()); report.put("status", "failed") }
        finally { save() }
        assertTrue("Inspect candidate-context-probe-report.json", report.getBoolean("passed"))
    }
}
