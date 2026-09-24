package com.kandong.modelprobe

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKit
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ContextLanguageProbeTest {
    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private fun decision(d: LanguageRoute.Decision) = JSONObject().put("action", d.action).put("language", d.language ?: JSONObject.NULL).put("raw", d.raw).put("reason", d.reason)
    private fun candidates(c: List<LanguageRoute.Candidate>) = JSONArray(c.map { JSONObject().put("language", it.language).put("score", it.score) })

    @Test fun frozenPagesWithWholeGroupAndSpanEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val file = AtomicFile(File(instrumentation.targetContext.filesDir, "context-language-probe-report.json"))
        val rows = JSONArray(); val errors = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString()).put("configuration", ContextLanguageRoute.CONFIG)
            .put("scope", "44 new authored manually grouped pages plus 38 known regression strings; no screen/OCR/translation/product acceptance")
            .put("status", "started").put("technicalPassed", false).put("qualityAccepted", false)
            .put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
            .put("sdk", "com.google.mlkit:language-id:17.0.6").put("candidateThreshold", .01).put("cases", rows).put("errors", errors)
        fun save() {
            val stream = file.startWrite()
            try { stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (e: Throwable) { file.failWrite(stream); throw e }
        }
        var client: LanguageIdentifier? = null
        var opened = 0; var closed = 0; var queryCount = 0
        val cache = linkedMapOf<String, List<LanguageRoute.Candidate>>()
        save()
        try {
            val permissionRows = JSONArray()
            for (c in listOf(context, instrumentation.targetContext)) for (p in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
                val granted = c.packageManager.checkPermission(p, c.packageName) == PackageManager.PERMISSION_GRANTED
                permissionRows.put(JSONObject().put("package", c.packageName).put("permission", p).put("granted", granted));check(!granted)
            }
            report.put("permissions", permissionRows)
            val sdkContext = object : ContextWrapper(context) { override fun getApplicationContext(): Context = this }
            MlKit.initialize(sdkContext)
            client = LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(.01f).build()); opened++
            fun infer(raw: String): List<LanguageRoute.Candidate> {
                queryCount++
                return cache.getOrPut(raw) { Tasks.await(client.identifyPossibleLanguages(raw), 30, TimeUnit.SECONDS)
                    .map { LanguageRoute.Candidate(it.languageTag, it.confidence.toDouble()) } }
            }
            val corpora = listOf(Triple("new", "language-context-v2.json", "fbc29c9fd6fa34680ad1ece4f86b74e4f57b6316548046f45b63b7efaac20d76"),
                Triple("legacy", "language-routing-v1.json", "733f883e4e974f110b201ceb9307d648f53bc14b2e6b46719a5a097eb33d6e7c"))
            val identities = JSONObject()
            for ((corpus, asset, sha) in corpora) {
                val bytes = context.assets.open(asset).use { ProbeInputs.bounded(it, 131072) }; ProbeInputs.verifyHash(bytes, sha)
                identities.put(corpus, sha)
                val fixtures = objects(JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("cases"))
                check(fixtures.size == if (corpus == "new") 44 else 38)
                check(fixtures.map { it.getString("id") }.toSet().size == fixtures.size)
                for (f in fixtures) {
                    val blocks = if (corpus == "new") objects(f.getJSONArray("blocks")).map { b ->
                        ContextLanguageRoute.Block(b.getString("id"), b.getString("groupId"), b.getString("text"), b.getBoolean("ocrConflict"))
                    } else listOf(ContextLanguageRoute.Block("target", "card", f.getString("text")))
                    val pageId = if (corpus == "new") f.getString("pageId") else ProbeInputs.sha(f.getString("text").toByteArray(Charsets.UTF_8))
                    val page = ContextLanguageRoute.Page(pageId, blocks)
                    val targetId = if (corpus == "new") f.getString("targetId") else "target"
                    val target = blocks.single { it.id == targetId }
                    val start = SystemClock.elapsedRealtimeNanos()
                    val observations = ContextLanguageRoute.queries(page).map { q -> ContextLanguageRoute.Observation(page.id, q, infer(q.raw)) }
                    val base = LanguageRoute.choose(target.raw, observations.single { it.query.key == "block:$targetId" }.candidates, target.ocrConflict)
                    val a = ContextLanguageRoute.choose(page, targetId, observations, false)
                    val b = ContextLanguageRoute.choose(page, targetId, observations, true)
                    check(listOf(base, a, b).all { it.raw == target.raw })
                    check(listOf(a, b).none { it.reason in setOf("INVALID_PAGE", "INVALID_BINDING", "MISSING_TARGET") })
                    val row = JSONObject().put("id", f.getString("id")).put("corpus", corpus).put("pageId", page.id)
                        .put("targetId", targetId).put("raw", target.raw).put("blocks", JSONArray(blocks.map { v -> JSONObject()
                            .put("id", v.id).put("groupId", v.groupId).put("raw", v.raw).put("ocrConflict", v.ocrConflict) }))
                        .put("observations", JSONArray(observations.map { o -> JSONObject().put("pageId", o.pageId).put("key", o.query.key)
                            .put("raw", o.query.raw).put("blockId", o.query.blockId ?: JSONObject.NULL)
                            .put("start", o.query.start ?: JSONObject.NULL).put("end", o.query.end ?: JSONObject.NULL).put("candidates", candidates(o.candidates)) }))
                        .put("decisions", JSONObject().put("v1", decision(base)).put("A", decision(a)).put("B", decision(b)))
                        .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                    // Consult scoring labels only after ALL three candidates have finished.
                    row.put("category", f.getString(if (corpus == "new") "category" else "group")).put("expected", f.getJSONObject("expected"))
                    rows.put(row); save()
                }
            }
            report.put("fixtureSha256", identities)
            val all = objects(rows)
            fun matches(row: JSONObject, variant: String): Boolean {
                val d = row.getJSONObject("decisions").getJSONObject(variant); val e = row.getJSONObject("expected")
                fun language(o: JSONObject) = if (o.isNull("language")) null else o.getString("language")
                return d.getString("action") == e.getString("action") && language(d) == language(e)
            }
            fun ready(row: JSONObject, variant: String) = row.getJSONObject("decisions").getJSONObject(variant).getString("action") != "review"
            fun stats(selected: List<JSONObject>, variant: String) = JSONObject().put("cases", selected.size)
                .put("matching", selected.count { matches(it, variant) }).put("correctReady", selected.count { matches(it, variant) && ready(it, variant) })
                .put("correctReview", selected.count { matches(it, variant) && !ready(it, variant) })
                .put("wrongAutomatic", selected.count { !matches(it, variant) && ready(it, variant) })
                .put("reviewInsteadOfReady", selected.count { !matches(it, variant) && !ready(it, variant) })
            val stats = JSONObject()
            for (corpus in listOf("new", "legacy")) {
                val subset = all.filter { it.getString("corpus") == corpus }
                val byVariant = JSONObject()
                for (v in listOf("v1", "A", "B")) {
                    val byCategory = JSONObject()
                    subset.map { it.getString("category") }.distinct().forEach { c -> byCategory.put(c, stats(subset.filter { it.getString("category") == c }, v)) }
                    byVariant.put(v, stats(subset, v).put("categories", byCategory))
                }
                stats.put(corpus, byVariant)
            }
            val new = all.filter { it.getString("corpus") == "new" }; val legacy = all.filter { it.getString("corpus") == "legacy" }
            val short = new.filter { it.getString("category") == "short" }
            fun correctReady(rows: List<JSONObject>, v: String) = rows.count { matches(it, v) && ready(it, v) }
            val gates = JSONObject().put("newNoWrongAutomatic", new.none { !matches(it, "B") && ready(it, "B") })
                .put("newPureAtLeast10", correctReady(new.filter { it.getString("category") == "pure" }, "B") >= 10)
                .put("newShortAtLeast6", correctReady(short, "B") >= 6)
                .put("newShortImprovesOverV1", correctReady(short, "B") > correctReady(short, "v1"))
                .put("literalConflictAllCorrect", new.filter { it.getString("category") == "literal-conflict" }.all { matches(it, "B") })
                .put("legacyNoWrongAutomatic", legacy.none { !matches(it, "B") && ready(it, "B") })
                .put("legacyPureAtLeast15", correctReady(legacy.filter { it.getString("category") in setOf("en", "fr", "zh") }, "B") >= 15)
            report.put("stats", stats).put("gates", gates).put("qualityAccepted", gates.keys().asSequence().all { gates.getBoolean(it) })
                .put("technicalPassed", true).put("status", "complete")
        } catch (e: Throwable) {
            errors.put(JSONObject().put("error", e.stackTraceToString())); report.put("technicalPassed", false).put("status", "failed")
        } finally {
            try { client?.let { it.close(); closed++ } } catch (e: Throwable) {
                errors.put(JSONObject().put("cleanupError", e.stackTraceToString()));report.put("technicalPassed", false).put("status", "failed")
            }
            report.put("clientsOpened", opened).put("clientsClosed", closed).put("evidenceQueries", queryCount).put("uniqueModelInferences", cache.size)
            if (opened != 1 || closed != 1) report.put("technicalPassed", false)
            save()
        }
        assertTrue("Read qualityAccepted separately; this assertion verifies execution only", report.getBoolean("technicalPassed"))
    }
}
