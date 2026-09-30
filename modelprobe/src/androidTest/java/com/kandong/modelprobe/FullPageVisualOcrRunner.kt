package com.kandong.modelprobe

import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetManager
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import com.kandong.ocrlab.context.ClearReason
import com.kandong.ocrlab.context.ContextRect
import com.kandong.ocrlab.context.MirrorTransform
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Fixed, authenticated fixture inference. No instrumentation, Activity, answers, or live capture.
 * Every original controller/permit/native object is worker-confined and released before delivery. */
internal class FullPageVisualOcrRunner(application: Context, private val fixtureAssets: AssetManager,
    private val afterInferenceForProbe: () -> Unit = {}) {
    data class Spec(val id: String, val language: String, val modelId: String,
        val width: Int = 1176, val height: Int = 2400)
    companion object {
        val PAGES: List<Spec> = Collections.unmodifiableList(listOf(
            Spec("en-normal", "en", "latin"), Spec("fr-seam", "fr", "latin"),
            Spec("hans-normal", "zh-Hans", "ch"), Spec("hant-seam", "zh-Hant", "ch")))
        private val requests = AtomicLong()
    }
    private val app = application.applicationContext
    data class Diagnostics(val detectors: Int, val recognitions: Int, val sessionsOpened: Int,
        val sessionsClosed: Int, val peakSessions: Int, val ortOpened: Int, val ortAttempts: Int,
        val ortClosed: Int, val matsOpened: Int, val matsAttempted: Int, val matsReleased: Int,
        val bitmapsOpened: Int, val bitmapsRecycled: Int, val transport: Map<String, Int>,
        val threadsBefore: Int, val threadsAfter: Int, val originalCandidatesEqual: Boolean,
        val cleanupBalanced: Boolean)
    data class Result(val metadata: RgbaFrameMetadata, val page: FullPageOcrAssociation.Result,
        val raster: FullPageOcrFixtures.PreparedPage, val diagnostics: Diagnostics) {
        val display = RegionVisualSession.Evidence(metadata, page)
    }

    fun run(spec: Spec, cancellation: RegionOcrBridge.Ticket): RegionOcrBridge.Evidence<Result> {
        check(Looper.myLooper() !== Looper.getMainLooper()) { "NATIVE_ON_MAIN" }
        require(spec in PAGES)
        cancellation.checkpoint()
        val geometry = GeometryCleanup()
        val ort = Cleanup()
        var fixture: FullPageOcrFixtures? = null
        val request = requests.incrementAndGet()
        val version = CaptureVersion(Process.myPid().toLong(), request, PAGES.indexOf(spec).toLong(), request, 0, 0)
        var lastWorkerTime = 0L
        val controller = FullPageRegionController {
            cancellation.checkpoint()
            CaptureCheckpoint(version, true, SystemClock.elapsedRealtime().also { lastWorkerTime = it })
        }
        controller.observePage(version)
        val clicked = checkNotNull(controller.click()) { "CLICK_REJECTED" }
        var staged: FullPageOcrPipeline.StagedPage? = null
        var permit: FullPageOcrPublication.RegionPermit? = null
        var priorThreads: Int? = null
        var threadsAfter: Int? = null
        var touchedThreads = false
        var restored = false
        var scopeSucceeded = false
        var sessionsOpened = 0; var sessionsClosed = 0; var live = 0; var peak = 0
        fun ready() { cancellation.checkpoint(); controller.checkpoint(clicked) }
        fun cleanupCertain() = !ort.uncertain && ort.opened == ort.closeAttempts && ort.opened == ort.closed &&
            geometry.balanced && fixture?.balanced != false && live == 0 &&
            sessionsOpened == sessionsClosed && (!touchedThreads || restored)
        try {
            ready()
            check(OpenCVLoader.initLocal() && Core.VERSION == "5.0.0") { "OPENCV_RUNTIME" }
            ready()
            val fixtures = FullPageOcrFixtures(fixtureAssets, geometry); fixture = fixtures
            val page = fixtures.pages.single { it.getString("id") == spec.id }
            check(page.getString("language") == spec.language && page.getInt("width") == spec.width &&
                page.getInt("height") == spec.height) { "PAGE_SPEC" }
            val raster = fixtures.prepare(page)
            ready()
            val inputs = ProbeInputs { app.assets.open(it) }
            val model = inputs.manifest().models.single { it.id == spec.modelId }
            val dictionary = inputs.dictionary(model)
            val detectorInputs = DetectorProbeInputs { fixtureAssets.open(it) }
            detectorInputs.manifest()
            ready()
            val engine = OrtProbeEngine(ort)
            check(engine.runtime == "1.30.0") { "ORT_RUNTIME" }
            fun <T> withSession(bytes: ByteArray, body: (OrtSession) -> T): T {
                val outstanding = ort.opened - ort.closed
                var entered = false
                try {
                    ready()
                    return engine.withModel(bytes) { native ->
                        bytes.fill(0); entered = true; sessionsOpened++; live++; peak = maxOf(peak, live)
                        check(live <= 2) { "SESSION_BUDGET" }
                        ready(); body(native)
                    }
                } finally {
                    bytes.fill(0)
                    if (entered) {
                        live--
                        if (!ort.uncertain && ort.opened - ort.closed == outstanding) sessionsClosed++
                    }
                }
            }
            priorThreads = Core.getNumThreads()
            touchedThreads = true
            try {
                Core.setNumThreads(1); check(Core.getNumThreads() == 1) { "THREAD_SETUP" }
                ready()
                withSession(detectorInputs.model()) { detector ->
                    withSession(inputs.model(model)) { recognizer ->
                        val pipeline = FullPageOcrPipeline(engine, detector, recognizer, model, dictionary, geometry, ort)
                        ready()
                        // Exactly one original acquisition; delivery never constructs replacement metadata.
                        val meta = RgbaFrameMetadata(raster.width, raster.height, version, SystemClock.elapsedRealtime(), 60_000)
                        fixtures.withFrame(raster, meta) { image ->
                            pipeline.runStaged("visual-real/$request", spec.id, image, meta) {
                                cancellation.checkpoint(); controller.checkpoint(clicked)
                            }.also {
                                staged = it
                                // In-process test seam only: actual inference has completed, but
                                // Image/session scopes have not exited and no permit is published.
                                afterInferenceForProbe()
                            }
                        }
                    }
                }
                scopeSucceeded = true
            } finally {
                Core.setNumThreads(checkNotNull(priorThreads))
                threadsAfter = Core.getNumThreads()
                restored = threadsAfter == priorThreads
            }
            if (!cleanupCertain()) throw RegionOcrBridge.UncertainCleanup()
            ready()
            val original = checkNotNull(staged) { "NOT_STAGED" }
            val actual = original.page
            val meta = original.metadata
            val resourcesClosed = scopeSucceeded && restored && cleanupCertain() &&
                sessionsOpened == 2 && sessionsClosed == 2 && peak == 2
            check(resourcesClosed) { "RESOURCE_SCOPE" }
            // These flags derive from real return/close counters, not assumed close receipts.
            permit = original.claimForRegion(scopeSucceeded, resourcesClosed)
            check(controller.accept(clicked, permit)) { "REGION_REJECTED" }
            permit?.close(); original.discard()
            check(actual.complete && actual.reason == null && actual.transportRejection == null &&
                actual.detectorInvocations == FullPageStripPlanner.plan(meta.width, meta.height).strips.size &&
                actual.recognitionInvocations == actual.candidates.size && actual.frameCloseAttempts == 1 &&
                actual.frameClosed == 1) { "SOURCE_INCOMPLETE" }
            val whole = checkNotNull(controller.render(ContextRect(0.0, 0.0, meta.width.toDouble(), meta.height.toDouble()),
                MirrorTransform(1.0, meta.width.toDouble(), meta.height.toDouble()))) { "WHOLE_PAGE_REJECTED" }
            check(whole.metadata === meta && whole.page.identity == FullPageOcrAssociation.PageIdentity(
                "visual-real/$request", version, spec.id, FullPageOcrContract.Model(model.id, model.sha, model.vocabulary),
                DetectorProbeInputs.MODEL_SHA, model.dictionarySha)) { "IDENTITY_CHANGED" }
            val originalById = actual.candidates.associateBy { it.provenance.id }
            val identical = originalById.size == actual.candidates.size &&
                whole.page.rawCandidates.size == actual.candidates.size &&
                whole.page.rawCandidates.all { c -> originalById[c.provenance.id]?.let { before ->
                    FullPageCandidateEvidence.same(before,c)
                } == true }
            check(identical) { "EVIDENCE_CHANGED" }
            val members = whole.page.groups.flatMap { it.memberIds }
            check(members.size == originalById.size && members.toSet() == originalById.keys) { "SPATIAL_MEMBERS_CHANGED" }
            val diagnostics = Diagnostics(actual.detectorInvocations, actual.recognitionInvocations, sessionsOpened,
                sessionsClosed, peak, ort.opened, ort.closeAttempts, ort.closed, geometry.matOpened,
                geometry.attempts.values.sum(), geometry.released.values.sum(), geometry.bitmapOpened,
                geometry.bitmapRecycled, Collections.unmodifiableMap(LinkedHashMap(fixtures.transport)),
                checkNotNull(priorThreads), checkNotNull(threadsAfter), identical, resourcesClosed)
            ready()
            writeDiagnostic(spec, request, meta, diagnostics)
            ready()
            // A final guarded render carries the Guard's full time history into the UI boundary.
            check(controller.render(whole.roi, whole.transform) != null) { "FINAL_REVOKED" }
            return RegionOcrBridge.Evidence(Result(meta, whole.page, raster, diagnostics),
                meta.acquiredAtMillis, meta.ttlMillis, lastWorkerTime)
        } finally {
            // This original authority never crosses the worker boundary, even on a failed JNI load.
            try { controller.clear(ClearReason.STOP) }
            finally { try { permit?.close() } finally { staged?.discard() } }
            if (!cleanupCertain()) throw RegionOcrBridge.UncertainCleanup()
        }
    }

    /** Four bounded fixed-fixture counter files, private to the host. Never any candidate text/pixels.
     * This records worker validation only; a UI delivery or OCR-quality pass is not implied. */
    private fun writeDiagnostic(spec: Spec, request: Long, meta: RgbaFrameMetadata, d: Diagnostics) {
        val json = JSONObject().put("schema", 1).put("scope", "fixed-fixture-worker-only")
            .put("pid", Process.myPid()).put("request", request).put("pageId", spec.id).put("modelId", spec.modelId)
            .put("fixtureSha256", FullPageOcrFixtures.MANIFEST_SHA).put("acquiredAtMillis", meta.acquiredAtMillis)
            .put("ttlMillis", meta.ttlMillis).put("detectors", d.detectors).put("recognitions", d.recognitions)
            .put("sessionsOpened", d.sessionsOpened).put("sessionsClosed", d.sessionsClosed).put("peakSessions", d.peakSessions)
            .put("ortOpened", d.ortOpened).put("ortCloseAttempts", d.ortAttempts).put("ortClosed", d.ortClosed)
            .put("matsOpened", d.matsOpened).put("matsAttempted", d.matsAttempted).put("matsReleased", d.matsReleased)
            .put("bitmapsOpened", d.bitmapsOpened).put("bitmapsRecycled", d.bitmapsRecycled)
            .put("transport", JSONObject(d.transport)).put("threadsBefore", d.threadsBefore).put("threadsAfter", d.threadsAfter)
            .put("originalCandidatesEqual", d.originalCandidatesEqual).put("cleanupBalanced", d.cleanupBalanced)
            .put("uiDeliveryVerified", false).put("qualityAccepted", false)
        try {
            val file = AtomicFile(File(app.filesDir, "region-visual-${spec.id}-counters.json"))
            val out = file.startWrite()
            try { out.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
            catch (_: Exception) { file.failWrite(out) }
        } catch (_: Exception) { /* Optional diagnostics never log content or change OCR eligibility. */ }
    }
}
