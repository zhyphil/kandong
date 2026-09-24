package com.kandong.modelprobe

import ai.onnxruntime.OrtSession
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** Fixed SAME RESIZED PIXELS numeric parity, not original-image Android resizing or OCR quality. */
@RunWith(AndroidJUnit4::class)
internal class AndroidDetectorProbeTest : DetectorProbeTestSupport() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets
    private fun inputs() = DetectorProbeInputs { assets.open(it) }
    /** Arrays/direct storage are scoped to one invocation and never placed in the cross-case report. */
    private fun compareCase(case: DetectorCase, reader: DetectorProbeInputs, session: OrtSession, cleanup: Cleanup,
        reports: JSONArray): Boolean {
        val row = JSONObject().put("id", case.id).put("sourceWidth", case.sourceWidth).put("sourceHeight", case.sourceHeight)
            .put("inputShape", JSONArray(case.plan.inputShape.toList())).put("expectedOutputShape", JSONArray(case.plan.outputShape.toList()))
            .put("effectiveLimit", case.plan.effectiveLimit).put("sourceSha256", reader.metadata(case.source).sha)
            .put("resizedArgbSha256", reader.metadata(case.argb).decodedSha)
            .put("referenceInputSha256", reader.metadata(case.input).decodedSha)
            .put("referenceOutputSha256", reader.metadata(case.output).decodedSha)
            .put("referenceMaskSha256", reader.metadata(case.mask).decodedSha)
            .put("referencePositivePixels", case.positives).put("status", "started")
        reports.put(row) // Keep partial evidence even if loading/inference fails.
        val data = reader.load(case)
        val packed = DetectorPacking.pack(case.plan.width, case.plan.height, data.argb)
        require(packed.size == data.referenceInput.size)
        val bits = packed.indices.count { packed[it].toRawBits() != data.referenceInput[it].toRawBits() }
        row.put("inputBitDifferences", bits).put("actualInputSha256", ProbeInputs.sha(serialized(packed)))
        if (bits != 0) { row.put("status", "input-mismatch").put("numericParityPassed", false); return false }
        val result = infer(session, tensor(packed, case.plan.inputShape), case.plan.outputShape, cleanup) { output, shape ->
            row.put("actualOutputShape", JSONArray(shape.toList())).put("actualOutputSha256", floatHash(output))
            DetectorComparison.compare(output, data.referenceOutput, data.referenceMask)
        }
        val passed = result.passed && (case.id != "blank-negative-24" || result.positivePixels == 0)
        row.put("comparison", metrics(result)).put("blankMaskZero", if (case.id == "blank-negative-24") result.positivePixels == 0 else JSONObject.NULL)
            .put("status", if (passed) "numeric-parity-passed" else "numeric-mismatch").put("numericParityPassed", passed)
        requireProbe(!cleanup.uncertain, "DETECTOR_CLOSE_UNCERTAIN")
        return passed
    }

    @Test fun sameResizedPixelsDetectorNumericParity() {
        val atomic = AtomicFile(File(instrumentation.targetContext.filesDir, "detector-probe-report.json"))
        atomic.delete()
        val started = SystemClock.elapsedRealtime()
        val cleanup = Cleanup(); val rows = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", UUID.randomUUID().toString())
            .put("scope", "same_resized_pixels_packing_and_detector_numeric_parity_only_not_Android_resize_boxes_crops_or_OCR_quality")
            .put("fixtureManifestSha256", DetectorProbeInputs.MANIFEST_SHA).put("sourceManifestSha256", DetectorProbeInputs.SOURCE_MANIFEST_SHA)
            .put("modelSha256", DetectorProbeInputs.MODEL_SHA).put("modelBytes", DetectorProbeInputs.MODEL_BYTES)
            .put("api", Build.VERSION.SDK_INT).put("deviceModel", Build.MODEL).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("runtimeSettings", JSONObject().put("provider", "CPUExecutionProvider").put("execution", "SEQUENTIAL")
                .put("intraOpThreads", 1).put("interOpThreads", 1).put("optimization", "ALL").put("cpuArena", false).put("telemetry", false))
            .put("acceptance", JSONObject().put("inputBitDifferences", 0).put("absoluteTolerance", DetectorComparison.ATOL)
                .put("relativeTolerance", DetectorComparison.RTOL).put("meanAbsoluteMax", DetectorComparison.MEAN_ABS_MAX)
                .put("threshold", DetectorComparison.THRESHOLD.toDouble()).put("thresholdOperator", ">").put("maskFlipsMax", 0)
                .put("nearThresholdBand", DetectorComparison.NEAR_BAND.toDouble()))
            .put("environmentLifetime", "ORT_1.30_Java_process_singleton_close_is_no_op").put("cases", rows)
        var failure: Throwable? = null; var passed = true
        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            val reader = inputs(); val cases = reader.manifest()
            val engine = OrtProbeEngine(cleanup) // Existing CPU-only sequential 1/1 ALL arena-off settings, unchanged.
            report.put("runtime", engine.runtime)
            require(engine.runtime == "1.30.0") { "DETECTOR_RUNTIME_VERSION" }
            engine.withModel(reader.model()) { session ->
                for (case in cases) if (!compareCase(case, reader, session, cleanup, rows)) passed = false
            }
            require(rows.length() == 10)
        } catch (e: Throwable) { failure = e; passed = false }
        finally {
            val clean = !cleanup.uncertain && cleanup.opened == cleanup.closed && cleanup.closeAttempts == cleanup.opened
            passed = passed && clean && rows.length() == 10
            report.put("status", if (failure != null) "error" else if (passed) "numeric-parity-passed" else "failed")
                .put("numericParityPassed", passed).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("nativeOpened", cleanup.opened).put("nativeCloseAttempts", cleanup.closeAttempts).put("nativeClosed", cleanup.closed)
                .put("nativeClosureFailures", cleanup.closeAttempts - cleanup.closed).put("cleanupUncertain", cleanup.uncertain)
                .put("failureClass", failure?.javaClass?.simpleName ?: JSONObject.NULL)
            if (failure is ProbeFailure) report.put("failureCode", (failure as ProbeFailure).code)
            // Failed numeric comparisons and closure failures are written and read back BEFORE assertions.
            try { save(atomic, report) } catch (writeFailure: Throwable) {
                failure?.let { writeFailure.addSuppressed(it) }; throw writeFailure
            }
        }
        failure?.let { throw it }
        assertTrue("Detector numeric parity failed; inspect private detector-probe-report.json", passed)
    }

    @Test fun loaderRejectsCorruptTruncatedOversizedAndMalformedAssets() {
        val reader = inputs(); val first = reader.manifest().first()
        val manifest = assets.open("detector-v1/manifest.json").use { ProbeInputs.bounded(it, DetectorProbeInputs.MANIFEST_CAP) }
        fun brokenManifest(raw: ByteArray) = DetectorProbeInputs { ByteArrayInputStream(raw) }
        assertThrows(ProbeFailure::class.java) { brokenManifest(manifest.copyOf().also { it[0] = '!'.code.toByte() }).manifest() }
        assertThrows(ProbeFailure::class.java) { brokenManifest(manifest.copyOf(manifest.size - 1)).manifest() }
        assertThrows(ProbeFailure::class.java) { brokenManifest(ByteArray(DetectorProbeInputs.MANIFEST_CAP + 1)).manifest() }
        // Invalid shapes/paths never get interpreted when the authenticated manifest is changed.
        val malformed = JSONObject(String(manifest, Charsets.UTF_8))
        malformed.getJSONArray("cases").getJSONObject(0).put("inputShape", JSONArray(listOf(1, 3, Long.MAX_VALUE, 32)))
            .put("input", "../outside.f32z")
        assertThrows(ProbeFailure::class.java) { brokenManifest(malformed.toString().toByteArray()).manifest() }
        val path = "detector-v1/${first.argb}"
        val original = reader.asset(first.argb)
        for (replacement in listOf(original.copyOf().also { it[0] = 0 }, original.copyOf(original.size - 1), ByteArray(DetectorProbeInputs.FILE_CAP + 1))) {
            val corrupted = DetectorProbeInputs { if (it == path) ByteArrayInputStream(replacement) else assets.open(it) }
            val case = corrupted.manifest().first()
            assertThrows(ProbeFailure::class.java) { corrupted.load(case) }
        }
        var opened = false
        val untouched = DetectorProbeInputs { opened = true; ByteArrayInputStream(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { untouched.asset("../outside") }; assertFalse(opened)
        val oversizedModel = DetectorProbeInputs { object : InputStream() {
            var left = DetectorProbeInputs.MODEL_BYTES + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val count = minOf(len, left); b.fill(0, off, off + count); left -= count; return count
            }
        } }
        assertThrows(ProbeFailure::class.java) { oversizedModel.model() }
    }

    @Test fun boundedInflationRejectsBombsTruncationHashAndInvalidFloats() {
        fun gzip(raw: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(); GZIPOutputStream(out).use { it.write(raw) }; return out.toByteArray()
        }
        val raw = serialized(floatArrayOf(0f, 0.5f)); val zip = gzip(raw)
        fun inflate(compressed: ByteArray, expected: Int = raw.size, hash: String = ProbeInputs.sha(raw)) =
            DetectorProbeInputs.inflate(compressed, compressed.size, ProbeInputs.sha(compressed), expected, hash)
        assertArrayEquals(raw, inflate(zip))
        assertThrows(Exception::class.java) { inflate(zip.copyOf(zip.size - 3)) }
        assertThrows(ProbeFailure::class.java) { inflate(gzip(ByteArray(4096)), 8) }
        assertThrows(ProbeFailure::class.java) { inflate(zip, 12) }
        assertThrows(ProbeFailure::class.java) { inflate(zip, hash = "0".repeat(64)) }
        assertThrows(ProbeFailure::class.java) { DetectorProbeInputs.inflate(zip, zip.size, "0".repeat(64), 8, ProbeInputs.sha(raw)) }
        assertThrows(IllegalArgumentException::class.java) { inflate(zip, DetectorPacking.MAX_PIXELS * 12 + 1) }
        for (f in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f)) {
            assertThrows(IllegalArgumentException::class.java) { DetectorProbeInputs.floats(serialized(floatArrayOf(f)), 0f, 1f) }
        }
        assertThrows(IllegalArgumentException::class.java) { DetectorProbeInputs.floats(byteArrayOf(1, 2, 3), 0f, 1f) }
    }

    @Test fun deliberateFailureAfterNativeCreationClosesEveryOwnedResource() {
        val cleanup = Cleanup(); val reader = inputs(); val case = reader.manifest().first()
        val engine = OrtProbeEngine(cleanup)
        val error = assertThrows(ProbeFailure::class.java) {
            engine.withModel(reader.model()) { session ->
                val data = reader.load(case)
                val packed = DetectorPacking.pack(case.plan.width, case.plan.height, data.argb)
                infer(session, tensor(packed, case.plan.inputShape), case.plan.outputShape, cleanup) { _, _ ->
                    throw ProbeFailure("DETECTOR_DELIBERATE_AFTER_RESULT")
                }
            }
        }
        assertEquals("DETECTOR_DELIBERATE_AFTER_RESULT", error.code)
        assertEquals(4, cleanup.opened); assertEquals(4, cleanup.closeAttempts); assertEquals(4, cleanup.closed)
        assertFalse(cleanup.uncertain)
        val failedClose = Cleanup()
        val resource = OnceResource(AutoCloseable { throw IllegalStateException("deliberate-close") }, failedClose)
        resource.close(); resource.close()
        assertEquals(1, failedClose.opened); assertEquals(1, failedClose.closeAttempts); assertEquals(0, failedClose.closed)
        assertTrue(failedClose.uncertain)
    }
}
