package com.kandong.modelprobe

import android.os.Build
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
internal class CandidateSelectionProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private fun quad(a: JSONArray) = (0 until a.length()).map { i ->
        val p = a.getJSONArray(i); check(p.length() == 2)
        SharedCandidates.Point(p.getDouble(0), p.getDouble(1))
    }
    private fun nullable(o: JSONObject, key: String) = if (o.isNull(key)) null else o.getString(key)
    private fun encoded(b: SharedCandidates.Block) = JSONObject().put("pageId", b.pageId).put("boxId", b.boxId)
        .put("readingOrder", b.readingOrder).put("quad", JSONArray(b.quad.map { listOf(it.x, it.y) }))
        .put("ch", b.ch).put("latin", b.latin).put("origin", b.decision.origin.name)
        .put("raw", b.decision.raw ?: JSONObject.NULL)

    @Test fun frozenSharedCandidatesAndFreshEndToEndBindings() {
        val file = AtomicFile(File(instrumentation.targetContext.filesDir, "candidate-selection-probe-report.json"))
        val cases = JSONArray(); val fresh = JSONArray(); val errors = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString())
            .put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL).put("configuration", SharedCandidates.CONFIG)
            .put("passed", false).put("status", "started").put("cases", cases).put("freshCases", fresh).put("errors", errors)
            .put("scope", "Synthetic frozen candidate rows plus a separately executed fresh E2E report; not 98 new Android image inferences or production quality acceptance")
        fun save() {
            val stream = file.startWrite()
            try { stream.write(report.toString(2).toByteArray()); file.finishWrite(stream) }
            catch (e: Throwable) { file.failWrite(stream); throw e }
        }
        save()
        try {
            val bytes = instrumentation.context.assets.open("shared-candidates-v2.json").use { ProbeInputs.bounded(it, 524288) }
            ProbeInputs.verifyHash(bytes, "1dab0781e9522504c6feab6513d7f9db3d82a313df00d79aa3551fd95983f1d3")
            val fixture = JSONObject(String(bytes, Charsets.UTF_8)); val fixtures = objects(fixture.getJSONArray("cases"))
            check(fixtures.size == 98 && fixtures.map { it.getString("id") }.toSet().size == 98)
            report.put("fixtureSha256", ProbeInputs.sha(bytes))
            var blocks = 0; var emptyLatin = 0; var blanks = 0
            fixtures.forEach { page ->
                val pageId = page.getString("pageId"); val rows = objects(page.getJSONArray("rows"))
                fun candidates(model: String) = rows.map { r -> SharedCandidates.Candidate(pageId, r.getString("boxId"),
                    r.getInt("readingOrder"), quad(r.getJSONArray("quad")), r.getString(model)) }
                // Deliberately permute model lists: positional pairing must not be used.
                val actual = SharedCandidates.join(pageId, candidates("ch").reversed(), candidates("latin"))
                check(actual.size == rows.size)
                actual.forEachIndexed { i, b ->
                    val r = rows[i]; val expected = r.getJSONObject("expected")
                    check(b.boxId == r.getString("boxId") && b.quad == quad(r.getJSONArray("quad")) && b.readingOrder == i)
                    check(b.ch == r.getString("ch") && b.latin == r.getString("latin"))
                    check(b.decision.origin.name == expected.getString("origin") && b.decision.raw == nullable(expected, "raw"))
                    if (b.latin.isEmpty()) emptyLatin++
                }
                check(SharedCandidates.pageText(actual) == nullable(page, "expectedRaw"))
                blocks += actual.size; if (actual.isEmpty()) blanks++
                cases.put(JSONObject().put("id", page.getString("id")).put("blocks", JSONArray(actual.map(::encoded))).put("passed", true))
            }
            val controls = objects(fixture.getJSONArray("controls")); check(controls.size == 8)
            controls.forEach { c ->
                val actual = SharedCandidates.choose(c.getString("ch"), c.getString("latin")); val expected = c.getJSONObject("expected")
                check(actual.origin.name == expected.getString("origin") && actual.raw == nullable(expected, "raw"))
            }
            check(blocks == 180 && emptyLatin == 27 && blanks == 4)
            report.put("frozenCases", 98).put("frozenBlocks", blocks).put("emptyLatinCandidatesRetained", emptyLatin)
                .put("blankCases", blanks).put("controls", controls.size)

            val args = InstrumentationRegistry.getArguments()
            val expectedRun = checkNotNull(args.getString("e2eRunId")); val expectedSha = checkNotNull(args.getString("e2eReportSha256"))
            val raw = File(instrumentation.targetContext.filesDir, "end-to-end-ocr-probe-report.json").inputStream().use { ProbeInputs.bounded(it, 524288) }
            ProbeInputs.verifyHash(raw, expectedSha)
            val e2e = JSONObject(String(raw, Charsets.UTF_8))
            check(e2e.getString("runId") == expectedRun && e2e.getBoolean("passed") && e2e.getString("status") == "passed")
            check(e2e.getJSONObject("counts").getInt("rawRows") == 18 && e2e.getJSONObject("counts").getInt("bindings") == 18)
            val modelRows = objects(e2e.getJSONArray("models")); check(modelRows.size == 10)
            val sources = objects(e2e.getJSONArray("cases")); check(sources.size == 10)
            var freshBlocks = 0
            for (source in sources.take(5)) {
                val id = source.getString("id"); val pageId = source.getString("sourceBgrSha256")
                fun model(name: String) = objects(modelRows.single { it.getString("id") == "$name/$id" }.getJSONArray("bindings")).map { r ->
                    SharedCandidates.Candidate(pageId, r.getString("boxId"), r.getInt("readingOrder"), quad(r.getJSONArray("quad")), r.getString("raw")) }
                val actual = SharedCandidates.join(pageId, model("ch"), model("latin").reversed())
                val expected = fixtures.single { it.getString("id") == "baseline72/$id/1" }
                val expectedRows = objects(expected.getJSONArray("rows")); check(actual.size == expectedRows.size)
                actual.forEachIndexed { i, b ->
                    val r = expectedRows[i]; val d = r.getJSONObject("expected")
                    check(b.ch == r.getString("ch") && b.latin == r.getString("latin"))
                    check(b.decision.origin.name == d.getString("origin") && b.decision.raw == nullable(d, "raw"))
                }
                check(SharedCandidates.pageText(actual) == nullable(expected, "expectedRaw"))
                freshBlocks += actual.size
                fresh.put(JSONObject().put("id", id).put("blocks", JSONArray(actual.map(::encoded))).put("passed", true))
            }
            check(fresh.length() == 5 && freshBlocks == 9)
            report.put("freshE2eRunId", expectedRun).put("freshE2eReportSha256", expectedSha).put("freshBlocks", freshBlocks)
                .put("status", "passed").put("passed", true)
        } catch (e: Exception) { errors.put(e.stackTraceToString()); report.put("status", "failed") }
        finally { save() }
        assertTrue("Inspect fresh candidate-selection-probe-report.json", report.getBoolean("passed"))
    }
}
