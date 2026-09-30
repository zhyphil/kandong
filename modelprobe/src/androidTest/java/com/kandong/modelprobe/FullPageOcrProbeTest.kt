package com.kandong.modelprobe

import ai.onnxruntime.OrtSession
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.io.File
import java.util.UUID

/** Technical transport/shape/cleanup assertions are independent of raw OCR quality. */
@RunWith(AndroidJUnit4::class)
internal class FullPageOcrProbeTest : DetectorProbeTestSupport() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val geometry = GeometryCleanup()
    private val ort = Cleanup()
    private fun output(name: String) = AtomicFile(File(instrumentation.targetContext.filesDir, name))
    private fun rect(r: FullPageStripPlanner.Rect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))
    private fun quad(q: List<GeometryProbeContract.Point>) = JSONArray(q.map { JSONArray(listOf(it.x, it.y)) })
    private fun versionJson(v: CaptureVersion) = JSONObject().put("session", v.session).put("snapshot", v.snapshot)
        .put("page", v.page).put("revision", v.revision).put("window", v.window).put("display", v.display)
    private fun reason(e: Exception) = if (e is ProbeFailure) e.code else
        e.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: e.javaClass.simpleName
    private fun candidate(c: FullPageOcrContract.Candidate): JSONObject {
        val p = c.provenance; val v = p.version
        return JSONObject().put("id", p.id).put("modelId", c.modelId).put("rawText", c.rawText)
            .put("version", JSONObject().put("session", v.session).put("snapshot", v.snapshot).put("page", v.page)
                .put("revision", v.revision).put("window", v.window).put("display", v.display))
            .put("pageFixtureId", p.pageFixtureId).put("stripIndex", p.stripIndex).put("read", rect(p.read)).put("core", rect(p.core))
            .put("contourIndex", p.contourIndex).put("rawBoxIndex", p.rawBoxIndex).put("finalBoxIndex", p.finalBoxIndex)
            .put("stripReadingOrder", p.stripReadingOrder).put("localQuad", quad(p.localQuad)).put("pageQuad", quad(p.pageQuad))
            .put("detectorScore", p.detectorScore).put("ownsCoreCenter", p.ownsCoreCenter)
            .put("recognitionInputShape", JSONArray(listOf(1, 3, 48, c.recognitionWidth)))
            .put("recognitionOutputShape", JSONArray(listOf(1, c.recognitionTime,
                FullPageOcrContract.models.single { it.id == c.modelId }.vocabulary)))
    }

    private fun association(outcome: FullPageOcrPublication.Outcome): JSONObject {
        val result = outcome.association
        val identity = result?.identity
        return JSONObject().put("published", outcome.published).put("rejection", outcome.reason ?: JSONObject.NULL)
            .put("identity", identity?.let { i -> JSONObject().put("sourceBatch", i.sourceBatch)
                .put("version", versionJson(i.version)).put("pageFixtureId", i.pageFixtureId)
                .put("model", JSONObject().put("id", i.model.id).put("sha256", i.model.sha).put("vocabulary", i.model.vocabulary))
                .put("detectorSha256", i.detectorSha).put("dictionarySha256", i.dictionarySha) } ?: JSONObject.NULL)
            // Raw fields remain in the existing rawCandidates schema; these IDs reference them exactly.
            .put("rawCandidateIds", JSONArray(result?.rawCandidates?.map { it.provenance.id }.orEmpty()))
            .put("candidateCount", result?.rawCandidates?.size ?: 0)
            .put("edgeCount", result?.edges?.size ?: 0).put("groupCount", result?.groups?.size ?: 0)
            .put("edges", JSONArray(result?.edges?.map { e -> JSONObject().put("leftId", e.leftId)
                .put("rightId", e.rightId).put("kind", e.kind.name) }.orEmpty()))
            .put("groups", JSONArray(result?.groups?.map { g -> JSONObject().put("id", g.id)
                .put("memberIds", JSONArray(g.memberIds)).put("text", g.text.name).put("geometry", g.geometry.name)
                .put("reasons", JSONArray(g.reasons.map { it.name })).put("agreedRaw", g.agreedRaw ?: JSONObject.NULL) }.orEmpty()))
    }

    /** Every source field compared by value, including both quads and the unmodified raw text. */
    private fun candidateFields(c: FullPageOcrContract.Candidate): List<Any> {
        val p = c.provenance
        return listOf(p.version, p.pageFixtureId, p.stripIndex, p.read, p.core, p.contourIndex, p.rawBoxIndex,
            p.finalBoxIndex, p.stripReadingOrder, p.localQuad, p.pageQuad, p.detectorScore.toRawBits(),
            p.ownsCoreCenter, p.id, c.modelId, c.rawText, c.recognitionWidth, c.recognitionTime)
    }

    private fun verifyAssociation(staged: FullPageOcrPipeline.StagedPage, result: FullPageOcrAssociation.Result) {
        check(result.published && result.identity?.version == staged.metadata.version) { "ASSOCIATION_VERSION" }
        val original = staged.page.candidates.associate { it.provenance.id to candidateFields(it) }
        check(original.size == staged.page.candidates.size && result.rawCandidates.size == original.size &&
            original == result.rawCandidates.associate { it.provenance.id to candidateFields(it) }) { "ASSOCIATION_FIELDS_CHANGED" }
        val members = result.groups.flatMap { it.memberIds }
        check(members.size == original.size && members.toSet() == original.keys) { "ASSOCIATION_MEMBERS_CHANGED" }
    }

    /** Exact, position-aware grading AFTER inference. Never chooses or rewrites a raw result.
     * Multiple exact candidates are diagnosed as duplicates, not counted as a clean page.
     * A non-exact candidate is retained for manual amount/unit/negation/accent/Hans/Hant review.
     */
    private fun grade(fixtures: FullPageOcrFixtures, page: JSONObject, actual: List<FullPageOcrContract.Candidate>): JSONObject {
        val lines = JSONArray(); val matched = mutableSetOf<String>(); var missing = 0; var duplicates = 0
        val placements = page.getJSONArray("placements")
        for (i in 0 until placements.length()) {
            val p = placements.getJSONObject(i); val f = fixtures.file(p.getString("asset")); val expected = f.getJSONArray("lines")
            for (j in 0 until expected.length()) {
                val line = expected.getJSONObject(j); val g = line.getJSONArray("glyph")
                val left = p.getInt("left") + g.getInt(0); val top = p.getInt("top") + g.getInt(1)
                val right = p.getInt("left") + g.getInt(2); val bottom = p.getInt("top") + g.getInt(3)
                val touching = actual.filter { c ->
                    val q = c.provenance.pageQuad
                    q.maxOf { it.x } > left && q.minOf { it.x } < right && q.maxOf { it.y } > top && q.minOf { it.y } < bottom
                }
                val exact = touching.filter { it.rawText == line.getString("text") }
                if (exact.isEmpty()) missing++
                if (exact.size > 1) duplicates += exact.size - 1
                exact.forEach { matched += it.provenance.id }
                lines.put(JSONObject().put("lineId", "${p.getString("id")}/$j").put("asset", p.getString("asset"))
                    .put("pngSha256", f.getString("sha256")).put("placement", p).put("expectedRaw", line.getString("text"))
                    .put("pageGlyphBounds", JSONArray(listOf(left, top, right, bottom)))
                    .put("touchingCandidateIds", JSONArray(touching.map { it.provenance.id }))
                    .put("exactCandidateIds", JSONArray(exact.map { it.provenance.id })))
            }
        }
        val unexpected = actual.filter { it.provenance.id !in matched }
        return JSONObject().put("assessed", true).put("normalization", "none; exact raw text plus glyph-box intersection")
            .put("lines", lines).put("expectedLines", lines.length()).put("linesWithoutExactCandidate", missing)
            .put("extraExactCandidates", duplicates).put("emptyCandidates", actual.count { it.rawText.isEmpty() })
            .put("fragmentOrErrorOrExtraCandidateIds", JSONArray(unexpected.map { it.provenance.id }))
            .put("strictExactPage", missing == 0 && duplicates == 0 && unexpected.isEmpty() && actual.size == lines.length())
            .put("interpretation", "Diagnostic only. Inspect every raw candidate, including duplicates/fragments; no translation or semantic acceptance.")
    }

    @Test fun fixedFullPagesThroughActualDetectorAndBothRecognizers() {
        // Device validation may run each fixed model as a separate invocation. This bounds
        // diagnostic retention without changing any page's acquisition clock or 60-second TTL.
        val selectedModel = InstrumentationRegistry.getArguments().getString("fullPageModel")
        require(selectedModel == null || selectedModel in setOf("ch", "latin"))
        val expectedModels = if (selectedModel == null) 2 else 1
        val expectedPages = expectedModels * FullPageOcrFixtures.PAGE_IDS.size
        val runId = UUID.randomUUID().toString(); val runStarted = SystemClock.elapsedRealtime()
        val reports = arrayListOf<Pair<String, JSONObject>>() // Text + bounded metadata only, <=20 x 256KiB.
        val stagedPages = mutableMapOf<JSONObject, FullPageOcrPipeline.StagedPage>()
        fun discard(report: JSONObject, why: String) {
            stagedPages.remove(report)?.discard()
            report.put("pageComplete", false).put("incompleteReason", why).put("technicalPassed", false)
                .put("rawCandidates", JSONArray()).put("quality", JSONObject().put("assessed", false))
                .put("association", association(FullPageOcrPublication.Outcome(false, why, null)))
                .put("associationFieldsPreserved", false).put("associationMembersComplete", false)
        }
        val errors = JSONArray(); val groups = JSONArray()
        val summary = JSONObject().put("schema", 1).put("runId", runId).put("status", "started")
            .put("fixtureVersion", FullPageOcrFixtures.NAMESPACE).put("fixtureSha256", FullPageOcrFixtures.MANIFEST_SHA)
            .put("selectedModel", selectedModel ?: "both")
            .put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT).put("groups", groups).put("errors", errors)
            .put("scope", "Fixed synthetic ImageWriter -> ImageReader -> strips -> actual DB/crop/CTC -> diagnostic association. No real screens, translation, freshness, text merging or deduplication.")
            .put("resourceScope", "New two-session diagnostic, not the prior one-session memory acceptance. Sequential single worker; one strip and one crop; owned arrays/direct inputs wiped. Existing helpers release native buffers without proving secure erasure or RSS bounds.")
            .put("nativeCancellation", "Native calls are synchronous; version/expiry checks reject between calls, not during a native call.")
        save(output("full-page-ocr-summary.json"), summary)
        var previousThreads: Int? = null; var restored = false; var fixtures: FullPageOcrFixtures? = null
        var live = 0; var peak = 0; var sessionAttempts = 0; var sessionOpened = 0; var sessionClosed = 0
        val sessionCounts = linkedMapOf("detectorAttempts" to 0, "detectorOpened" to 0, "detectorClosed" to 0,
            "recognizerAttempts" to 0, "recognizerOpened" to 0, "recognizerClosed" to 0)
        fun countSession(key: String) { sessionCounts[key] = sessionCounts.getValue(key) + 1 }
        var detectorInvocations = 0; var recognitionInvocations = 0
        var outerSucceeded = false
        try {
            check(OpenCVLoader.initLocal()); check(Core.VERSION == "5.0.0")
            previousThreads = Core.getNumThreads(); Core.setNumThreads(1); check(Core.getNumThreads() == 1)
            val fixture = FullPageOcrFixtures(instrumentation.context.assets, geometry); fixtures = fixture
            val inputs = ProbeInputs { instrumentation.targetContext.assets.open(it) }
            val models = inputs.manifest().models
            val detectorInputs = DetectorProbeInputs { instrumentation.context.assets.open(it) }
            val engine = OrtProbeEngine(ort); check(engine.runtime == "1.30.0")
            summary.put("opencv", Core.VERSION).put("ort", engine.runtime).put("detectorModelSha256", DetectorProbeInputs.MODEL_SHA)
            fun <T> withSession(kind: String, bytes: ByteArray, block: (OrtSession) -> T): T {
                val outstanding = ort.opened - ort.closed; var entered = false
                sessionAttempts++; countSession("${kind}Attempts")
                try {
                    return engine.withModel(bytes) { session ->
                        bytes.fill(0) // Session creation has synchronously consumed authenticated bytes.
                        entered = true; sessionOpened++; countSession("${kind}Opened"); live++; peak = maxOf(peak, live); check(live <= 2)
                        block(session)
                    }
                } finally {
                    bytes.fill(0)
                    if (entered) {
                        live--
                        if (!ort.uncertain && ort.opened - ort.closed == outstanding) { sessionClosed++; countSession("${kind}Closed") }
                    }
                }
            }
            // BOTH models see the same immutable page list. Never select a model by expected text.
            for ((modelIndex, model) in models.withIndex()) {
                if (selectedModel != null && model.id != selectedModel) continue
                check(live == 0 && !ort.uncertain && ort.opened == ort.closed)
                FullPageOcrContract.outputShape(model.id, model.sha, model.vocabulary, 320)
                val groupReports = arrayListOf<JSONObject>(); val beforeOpen = sessionOpened; val beforeClose = sessionClosed
                val beforeSessions = sessionCounts.toMap()
                val group = JSONObject().put("modelId", model.id).put("sha256", model.sha).put("vocabulary", model.vocabulary)
                    .put("dictionarySha256", model.dictionarySha).put("pageIds", JSONArray(FullPageOcrFixtures.PAGE_IDS))
                groups.put(group)
                var groupSucceeded = false
                try {
                    val dictionary = inputs.dictionary(model)
                    withSession("detector", detectorInputs.model()) { detector ->
                        withSession("recognizer", inputs.model(model)) { recognizer ->
                            val pipeline = FullPageOcrPipeline(engine, detector, recognizer, model, dictionary, geometry, ort)
                            for ((pageIndex, page) in fixture.pages.withIndex()) {
                                val id = page.getString("id")
                                val filename = "full-page-ocr-${model.id}-$id.json"
                                val report = JSONObject().put("schema", 1).put("runId", runId).put("pageFixtureId", id)
                                    .put("fixtureSha256", FullPageOcrFixtures.MANIFEST_SHA).put("modelId", model.id).put("modelSha256", model.sha)
                                    .put("vocabulary", model.vocabulary).put("detectorModelSha256", DetectorProbeInputs.MODEL_SHA)
                                    .put("technicalPassed", false).put("status", "started")
                                    .put("rawCandidates", JSONArray()).put("quality", JSONObject().put("assessed", false))
                                    .put("association", association(FullPageOcrPublication.Outcome(false, "NOT_STAGED", null)))
                                reports += filename to report; groupReports += report
                                try {
                                    val version = CaptureVersion(runStarted, (modelIndex * 10 + pageIndex + 1).toLong(), pageIndex.toLong(), 1, 0, 0)
                                    val meta = RgbaFrameMetadata(page.getInt("width"), page.getInt("height"), version,
                                        SystemClock.elapsedRealtime(), 60000)
                                    val started = SystemClock.elapsedRealtime()
                                    report.put("version", versionJson(version)) // Includes blank; never inferred from another page.
                                    val staged = fixture.withFrame(page, meta) { frame ->
                                        pipeline.runStaged(runId, id, frame, meta) {
                                            CaptureCheckpoint(version, true, SystemClock.elapsedRealtime())
                                        }.also { stagedPages[report] = it }
                                    }
                                    val actual = staged.page
                                    detectorInvocations += actual.detectorInvocations; recognitionInvocations += actual.recognitionInvocations
                                    val expectedStrips = FullPageStripPlanner.plan(meta.width, meta.height).strips.size
                                    val transportAndShape = actual.complete && actual.detectorInvocations == expectedStrips &&
                                        actual.strips.map { it.index } == (0 until expectedStrips).toList() &&
                                        actual.recognitionInvocations == actual.candidates.size &&
                                        actual.candidates.map { it.provenance.id }.toSet().size == actual.candidates.size
                                    report.put("elapsedMillis", SystemClock.elapsedRealtime() - started).put("layout", page)
                                        .put("pageComplete", actual.complete).put("incompleteReason", actual.reason ?: JSONObject.NULL)
                                        .put("transportAndShapePassed", transportAndShape)
                                        .put("actualStripWithMoreThanThreeCandidates", actual.strips.any { it.boxes > 3 })
                                        .put("transportRejection", actual.transportRejection ?: JSONObject.NULL)
                                        .put("frameCloseAttempts", actual.frameCloseAttempts).put("frameClosed", actual.frameClosed)
                                        .put("detectorInvocations", actual.detectorInvocations).put("recognitionInvocations", actual.recognitionInvocations)
                                        .put("rawCandidates", JSONArray(actual.candidates.map(::candidate)))
                                        .put("strips", JSONArray(actual.strips.map { s -> JSONObject().put("index", s.index).put("read", rect(s.read))
                                            .put("core", rect(s.core)).put("detectorShape", JSONArray(listOf(1, 1, s.detectorHeight, s.detectorWidth)))
                                            .put("status", s.status).put("contours", s.contours ?: JSONObject.NULL).put("boxes", s.boxes) }))
                                        .put("geometryCleanupAfterPage", geometry.json()).put("transportCleanupAfterPage", JSONObject(fixture.transport.toMap()))
                                        .put("pageResourcesBalanced", fixture.balanced && geometry.balanced && !ort.uncertain)
                                    if (actual.complete) report.put("quality", grade(fixture, page, actual.candidates))
                                    // Fail closed before a report is retained if bounded metadata would exceed save().
                                    require(report.toString().toByteArray(Charsets.UTF_8).size <= 240 * 1024) { "REPORT_BUDGET" }
                                } catch (e: Exception) {
                                    discard(report, reason(e))
                                }
                                if (ort.uncertain || !geometry.balanced || !fixture.balanced) error("CLEANUP_UNCERTAIN")
                            }
                        }
                    }
                    groupSucceeded = true // Both nested session callbacks AND closes returned normally.
                } finally {
                    val good = !ort.uncertain && ort.opened == ort.closed && ort.opened == ort.closeAttempts && live == 0
                    group.put("sessionsOpened", sessionOpened - beforeOpen).put("sessionsClosed", sessionClosed - beforeClose)
                        .put("sessionCounts", JSONObject(sessionCounts.mapValues { (key, value) -> value - beforeSessions.getValue(key) }))
                        .put("closedBeforeNextModel", good).put("scopeSucceeded", groupSucceeded)
                        .put("ortOpened", ort.opened).put("ortClosed", ort.closed)
                        .put("ortCloseAttempts", ort.closeAttempts).put("ortUncertain", ort.uncertain)
                    groupReports.forEach { report ->
                        report.put("modelGroupSessions", group)
                        if (!groupSucceeded || !good) discard(report,
                            if (!groupSucceeded) "SESSION_SCOPE_FAILED" else "SESSION_CLEANUP_UNCERTAIN")
                    }
                }
            }
            outerSucceeded = true
        } catch (e: Exception) { errors.put(reason(e)) }
        finally {
            previousThreads?.let { prior ->
                try { Core.setNumThreads(prior); restored = Core.getNumThreads() == prior }
                catch (e: Exception) { errors.put("THREAD_RESTORE_${e.javaClass.simpleName}") }
            }
            val cleanupGood = !ort.uncertain && ort.opened == ort.closed && ort.opened == ort.closeAttempts &&
                geometry.balanced && fixtures?.balanced == true && live == 0 && restored
            var passedPages = 0
            var associatedPages = 0; var associatedCandidates = 0; var associatedGroups = 0; var associatedEdges = 0
            reports.forEach { (name, report) ->
                if (!outerSucceeded || !cleanupGood) discard(report,
                    if (!outerSucceeded) "OUTER_SCOPE_FAILED" else "OUTER_CLEANUP_UNCERTAIN")
                val staged = stagedPages.remove(report)
                if (staged != null) {
                    try {
                        val outcome = staged.publish(outerSucceeded && report.getJSONObject("modelGroupSessions").getBoolean("scopeSucceeded"),
                            cleanupGood && report.optBoolean("pageResourcesBalanced"))
                        if (!outcome.published) discard(report, outcome.reason ?: "ASSOCIATION_REJECTED")
                        else {
                            verifyAssociation(staged, checkNotNull(outcome.association))
                            report.put("association", association(outcome)).put("associationFieldsPreserved", true)
                                .put("associationMembersComplete", true)
                            require(report.toString().toByteArray(Charsets.UTF_8).size <= 240 * 1024) { "REPORT_BUDGET" }
                        }
                    } catch (e: Exception) { discard(report, reason(e)) }
                    finally { staged.discard() }
                }
                val passed = cleanupGood && report.optBoolean("pageComplete") && report.optBoolean("pageResourcesBalanced") &&
                    report.optBoolean("transportAndShapePassed") && report.getJSONObject("association").getBoolean("published")
                report.put("technicalPassed", passed).put("status", if (passed) "complete" else "incomplete")
                    .put("threadsRestored", restored)
                if (!passed) discard(report,
                    if (report.isNull("incompleteReason")) "PAGE_REJECTED" else report.getString("incompleteReason"))
                val associated = report.getJSONObject("association")
                if (associated.getBoolean("published")) {
                    associatedPages++; associatedCandidates += associated.getInt("candidateCount")
                    associatedGroups += associated.getInt("groupCount"); associatedEdges += associated.getInt("edgeCount")
                }
                if (passed) passedPages++
                save(output(name), report)
            }
            val technicalPassed = outerSucceeded && passedPages == expectedPages && associatedPages == expectedPages &&
                sessionOpened == expectedModels * 2 && sessionClosed == expectedModels * 2 && peak == 2 && errors.length() == 0
            summary.put("status", if (technicalPassed) "complete" else "incomplete").put("technicalPassed", technicalPassed)
                .put("qualityAcceptance", "Not a technical assertion; inspect each page quality and rawCandidates separately.")
                .put("associationAcceptance", "Same-inference candidates preserved by value and covered once; publication requires normal scope completion, cleanup and the original guard. No OCR quality improvement claim.")
                .put("associationPublishedPages", associatedPages).put("associationCandidateCount", associatedCandidates)
                .put("associationGroupCount", associatedGroups).put("associationEdgeCount", associatedEdges)
                .put("scopeSucceeded", outerSucceeded)
                .put("reports", JSONArray(reports.map { it.first })).put("expectedPages", expectedPages).put("technicalPassedPages", passedPages)
                .put("sessionAttempts", sessionAttempts).put("sessionsOpened", sessionOpened).put("sessionsClosed", sessionClosed)
                .put("sessionCounts", JSONObject(sessionCounts.toMap()))
                .put("peakSimultaneousSessions", peak).put("detectorInvocations", detectorInvocations).put("recognitionInvocations", recognitionInvocations)
                .put("geometryCleanup", geometry.json()).put("transportCleanup", JSONObject(fixtures?.transport?.toMap().orEmpty()))
                .put("ortCleanup", JSONObject().put("opened", ort.opened).put("closeAttempts", ort.closeAttempts).put("closed", ort.closed).put("uncertain", ort.uncertain))
                .put("threadsRestored", restored).put("elapsedMillis", SystemClock.elapsedRealtime() - runStarted)
            save(output("full-page-ocr-summary.json"), summary)
        }
        assertTrue("Technical transport/shape/cleanup incomplete; inspect full-page-ocr-summary.json and page reports",
            summary.getBoolean("technicalPassed"))
    }
}
