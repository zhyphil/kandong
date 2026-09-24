package com.kandong.modelprobe

import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.io.File
import java.util.UUID

/** Actual native work is completed or unwound before rejecting the whole synthetic page. */
@RunWith(AndroidJUnit4::class)
internal class FullPageOcrLifecycleProbeTest : DetectorProbeTestSupport() {
    @Test fun nestedSessionsReleaseOnInnerCreationAndCallbackFailure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val inputs = ProbeInputs { instrumentation.targetContext.assets.open(it) }
        val detectorInputs = DetectorProbeInputs { instrumentation.context.assets.open(it) }
        val model = inputs.manifest().models.single { it.id == "ch" }
        val cases = JSONArray()
        val report = JSONObject().put("schema", 1).put("passed", false).put("cases", cases)
            .put("scope", "Actual inner ORT creation failure and injected inner callback exception, before any Image allocation.")
        try {
            for (creationFailure in listOf(true, false)) {
                val ort = Cleanup(); val engine = OrtProbeEngine(ort)
                var outerEntered = false; var innerEntered = false; var caught: Exception? = null
                val detectorBytes = detectorInputs.model()
                try {
                    engine.withModel(detectorBytes) { _ ->
                        detectorBytes.fill(0); outerEntered = true
                        val original = inputs.model(model)
                        val bytes = if (creationFailure) original.copyOf(32).also { original.fill(0) } else original
                        try {
                            engine.withModel(bytes) { _ ->
                                bytes.fill(0); innerEntered = true
                                error("INJECTED_SESSION_CALLBACK_FAILURE")
                            }
                        } finally { bytes.fill(0) }
                    }
                } catch (e: Exception) { caught = e }
                finally { detectorBytes.fill(0) }
                val balanced = !ort.uncertain && ort.opened == ort.closed && ort.opened == ort.closeAttempts
                cases.put(JSONObject().put("case", if (creationFailure) "inner-creation" else "inner-callback")
                    .put("outerEntered", outerEntered).put("innerEntered", innerEntered)
                    .put("failureType", caught?.javaClass?.simpleName ?: JSONObject.NULL)
                    .put("opened", ort.opened).put("closed", ort.closed).put("closeAttempts", ort.closeAttempts)
                    .put("balanced", balanced))
                assertTrue(outerEntered); assertEquals(!creationFailure, innerEntered)
                if (creationFailure) assertTrue(caught is ai.onnxruntime.OrtException)
                else { assertTrue(caught is IllegalStateException); assertEquals("INJECTED_SESSION_CALLBACK_FAILURE", caught?.message) }
                assertEquals(if (creationFailure) 3 else 4, ort.opened); assertTrue(balanced)
            }
            report.put("passed", true)
        } finally { save(AtomicFile(File(instrumentation.targetContext.filesDir, "full-page-ocr-session-failures.json")), report) }
    }

    @Test fun nativeBoundariesRejectWholePageAndSessionsRemainReusable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val geometry = GeometryCleanup(); val ort = Cleanup()
        val cases = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString())
            .put("fixtureSha256", FullPageOcrFixtures.MANIFEST_SHA).put("passed", false).put("cases", cases)
            .put("scope", "Actual fixed-page native inference; cancellation is between calls, not native interruption. Injected failure is a checkpoint exception, not a simulated ORT crash.")
        var previousThreads: Int? = null; var restored = false; var fixtures: FullPageOcrFixtures? = null
        var completed = false
        try {
            check(OpenCVLoader.initLocal()); check(Core.VERSION == "5.0.0")
            previousThreads = Core.getNumThreads(); Core.setNumThreads(1)
            val fixture = FullPageOcrFixtures(instrumentation.context.assets, geometry); fixtures = fixture
            val page = fixture.pages.single { it.getString("id") == "en-normal" }
            val inputs = ProbeInputs { instrumentation.targetContext.assets.open(it) }
            val model = inputs.manifest().models.single { it.id == "ch" }
            val dictionary = inputs.dictionary(model)
            val detectorInputs = DetectorProbeInputs { instrumentation.context.assets.open(it) }
            val engine = OrtProbeEngine(ort)
            val detectorBytes = detectorInputs.model()
            try {
                engine.withModel(detectorBytes) { detector ->
                    detectorBytes.fill(0)
                    val recognitionBytes = inputs.model(model)
                    try {
                        engine.withModel(recognitionBytes) { recognizer ->
                            recognitionBytes.fill(0)
                            val pipeline = FullPageOcrPipeline(engine, detector, recognizer, model, dictionary, geometry, ort)
                            val names = listOf("cancel-with-detector-output-live", "revision-after-first-recognition",
                                "expire-after-prior-strip", "checkpoint-failure-after-prior-strip", "reuse-after-rejection")
                            for ((index, name) in names.withIndex()) {
                                val version = CaptureVersion(2, index + 1L, 3, 1, 0, 0)
                                val meta = RgbaFrameMetadata(1176, 2400, version, 1000, 1000)
                                val opened = ort.opened; val closed = ort.closed
                                var triggered = false
                                val started = SystemClock.elapsedRealtime()
                                val actual = fixture.withFrame(page, meta) { frame ->
                                    pipeline.run("en-normal", frame, meta) {
                                        val openedDelta = ort.opened - opened
                                        val closedDelta = ort.closed - closed
                                        val trigger = when (index) {
                                            0 -> openedDelta >= 2 && closedDelta == 0
                                            1 -> closedDelta >= 4
                                            2, 3 -> openedDelta >= 12 && closedDelta == 10
                                            else -> false
                                        }
                                        triggered = triggered || trigger
                                        if (trigger && index == 3) error("INJECTED_CHECKPOINT_FAILURE")
                                        CaptureCheckpoint(if (trigger && index == 1) version.copy(revision = 2) else version,
                                            !(trigger && index == 0), if (trigger && index == 2) 2000 else 1001)
                                    }
                                }
                                val nativeBalanced = ort.opened - opened == ort.closed - closed && !ort.uncertain
                                val row = JSONObject().put("case", name).put("triggered", triggered)
                                    .put("complete", actual.complete).put("reason", actual.reason ?: JSONObject.NULL)
                                    .put("candidateCount", actual.candidates.size).put("detectorInvocations", actual.detectorInvocations)
                                    .put("recognitionInvocations", actual.recognitionInvocations)
                                    .put("frameCloseAttempts", actual.frameCloseAttempts).put("frameClosed", actual.frameClosed)
                                    .put("nativeOpened", ort.opened - opened).put("nativeClosed", ort.closed - closed)
                                    .put("nativeBalanced", nativeBalanced).put("geometryBalanced", geometry.balanced)
                                    .put("transportBalanced", fixture.balanced).put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                                cases.put(row)
                                assertTrue(nativeBalanced && geometry.balanced && fixture.balanced)
                                assertEquals(1, actual.frameCloseAttempts); assertEquals(1, actual.frameClosed)
                                if (index == 4) {
                                    assertFalse(triggered); assertTrue(actual.complete)
                                    assertEquals(4, actual.detectorInvocations); assertEquals(8, actual.recognitionInvocations)
                                    assertEquals(8, actual.candidates.size)
                                } else {
                                    assertTrue("Checkpoint was not reached: $name", triggered)
                                    assertFalse(actual.complete); assertTrue(actual.candidates.isEmpty())
                                    assertEquals(if (index == 3) "INJECTED_CHECKPOINT_FAILURE" else "CANCELLED_OR_STALE", actual.reason)
                                    assertEquals(if (index < 2) 1 else 2, actual.detectorInvocations)
                                    assertEquals(when (index) { 0 -> 0; 1 -> 1; else -> 4 }, actual.recognitionInvocations)
                                }
                            }
                        }
                    } finally { recognitionBytes.fill(0) }
                }
            } finally { detectorBytes.fill(0) }
            completed = true
        } finally {
            previousThreads?.let { Core.setNumThreads(it); restored = Core.getNumThreads() == it }
            val resources = !ort.uncertain && ort.opened == ort.closed && ort.opened == ort.closeAttempts &&
                geometry.balanced && fixtures?.balanced == true && restored
            report.put("passed", completed && resources).put("ortOpened", ort.opened).put("ortClosed", ort.closed)
                .put("ortCloseAttempts", ort.closeAttempts).put("geometryCleanup", geometry.json())
                .put("transportCleanup", JSONObject(fixtures?.transport?.toMap().orEmpty())).put("threadsRestored", restored)
            save(AtomicFile(File(instrumentation.targetContext.filesDir, "full-page-ocr-lifecycle.json")), report)
        }
        assertTrue(report.getBoolean("passed"))
    }
}
