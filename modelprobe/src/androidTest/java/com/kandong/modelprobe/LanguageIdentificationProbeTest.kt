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

/** Only the hash-pinned authored corpus may enter this model. Never reads screen/app text. */
@RunWith(AndroidJUnit4::class)
class LanguageIdentificationProbeTest {
    @Test fun frozenSyntheticLanguageEvidenceAndRouting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val target = instrumentation.targetContext
        val file = AtomicFile(File(target.filesDir, "language-routing-probe-report.json"))
        val cases = JSONArray()
        val errors = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString())
            .put("status", "started").put("technicalPassed", false).put("qualityAccepted", false)
            .put("scope", "38 authored strings only; no OCR, real screen, translation, product, or Huawei acceptance")
            .put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
            .put("sdk", "com.google.mlkit:language-id:17.0.6").put("candidateThreshold", 0.01)
            .put("configuration", LanguageRoute.CONFIG).put("cases", cases).put("errors", errors)
        fun save() {
            val stream = file.startWrite()
            try { stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (e: Throwable) { file.failWrite(stream); throw e }
        }
        var client: LanguageIdentifier? = null
        var opened = 0
        var closed = 0
        save()
        try {
            val permissions = JSONArray()
            for (context in listOf(testContext, target)) {
                for (permission in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
                    val granted = context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
                    permissions.put(JSONObject().put("package", context.packageName).put("permission", permission).put("granted", granted))
                    check(!granted) { "UNEXPECTED_NETWORK_PERMISSION" }
                }
            }
            report.put("permissions", permissions)
            val bytes = testContext.assets.open("language-routing-v1.json").use { ProbeInputs.bounded(it, 65536) }
            ProbeInputs.verifyHash(bytes, "733f883e4e974f110b201ceb9307d648f53bc14b2e6b46719a5a097eb33d6e7c")
            report.put("fixtureSha256", ProbeInputs.sha(bytes))
            val fixture = JSONObject(String(bytes, Charsets.UTF_8))
            check(fixture.getInt("schema") == 1)
            val rows = fixture.getJSONArray("cases")
            check(rows.length() == 38)
            check((0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet().size == 38)
            // Dependency exists in the instrumentation APK only. Use the documented public initializer
            // with that package's context, so bundled component metadata/assets are discoverable.
            // Instrumentation's package context has no Application. Keep its package/assets/metadata,
            // but supply a stable process-lifetime context to downstream GoogleApi components.
            val sdkContext = object : ContextWrapper(testContext) {
                override fun getApplicationContext(): Context = this
            }
            report.put("testPackageApplicationContextWasNull", testContext.applicationContext == null)
                .put("sdkContextPackage", sdkContext.packageName)
            check(sdkContext.packageName == testContext.packageName && sdkContext.applicationContext === sdkContext)
            MlKit.initialize(sdkContext)
            client = LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.01f).build())
            opened++
            for (i in 0 until rows.length()) {
                val fixtureRow = rows.getJSONObject(i)
                val text = fixtureRow.getString("text")
                check(text.length <= 4096)
                val row = JSONObject().put("id", fixtureRow.getString("id")).put("status", "started")
                cases.put(row)
                save()
                val start = SystemClock.elapsedRealtimeNanos()
                val candidates = Tasks.await(client.identifyPossibleLanguages(text), 30, TimeUnit.SECONDS)
                    .map { LanguageRoute.Candidate(it.languageTag, it.confidence.toDouble()) }
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                check(candidates.isNotEmpty() && candidates.all { it.score.isFinite() && it.score in 0.0..1.0 })
                val decision = LanguageRoute.choose(text, candidates)
                check(decision.raw == text && decision.reason != "INVALID_EVIDENCE")
                // Expected labels are first consulted AFTER model inference and routing.
                val expected = fixtureRow.getJSONObject("expected")
                val expectedLanguage = if (expected.has("language")) expected.getString("language") else null
                val correct = expected.getString("action") == decision.action && expectedLanguage == decision.language
                row.put("status", "complete").put("group", fixtureRow.getString("group")).put("raw", text)
                    .put("candidates", JSONArray(candidates.map { JSONObject().put("language", it.language).put("score", it.score) }))
                    .put("decision", JSONObject().put("action", decision.action).put("language", decision.language ?: JSONObject.NULL)
                        .put("raw", decision.raw).put("reason", decision.reason))
                    .put("expected", expected).put("qualityCorrect", correct)
                    .put("wrongAutomaticRoute", !correct && decision.action != "review").put("inferenceMs", elapsedMs)
                save()
            }
            report.put("qualityCorrectCount", (0 until cases.length()).count { cases.getJSONObject(it).getBoolean("qualityCorrect") })
                .put("wrongAutomaticRoutes", (0 until cases.length()).count { cases.getJSONObject(it).getBoolean("wrongAutomaticRoute") })
                .put("reviewCount", (0 until cases.length()).count { cases.getJSONObject(it).getJSONObject("decision").getString("action") == "review" })
                .put("qualityAccepted", (0 until cases.length()).all { cases.getJSONObject(it).getBoolean("qualityCorrect") })
                .put("technicalPassed", true).put("status", "complete")
        } catch (e: Throwable) {
            errors.put(JSONObject().put("error", e.stackTraceToString()))
            report.put("status", "failed").put("technicalPassed", false)
        } finally {
            try { client?.let { it.close(); closed++ } }
            catch (e: Throwable) {
                errors.put(JSONObject().put("cleanupError", e.stackTraceToString()))
                report.put("status", "failed").put("technicalPassed", false)
            }
            report.put("clientsOpened", opened).put("clientsClosed", closed)
            if (opened != 1 || closed != 1) report.put("technicalPassed", false)
            save()
        }
        // Quality is a separate reported gate. Keep every failure instead of aborting at the first mismatch.
        assertTrue("Inspect fresh language-routing-probe-report.json; qualityAccepted is a separate result", report.getBoolean("technicalPassed"))
    }
}
