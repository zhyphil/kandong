package com.kandong.ocrlab

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class OcrReport {
    val runId: String = UUID.randomUUID().toString()
    private val startedElapsed = SystemClock.elapsedRealtime()
    val results = JSONArray()
    private val document = JSONObject()
        .put("schema", 1)
        .put("runId", runId)
        .put("scope", "fixed_packaged_synthetic_ocr_only")
        .put("sdk", "com.google.mlkit:text-recognition:16.0.1")
        .put("fixtureSha256", SyntheticFixtures.SHA256)
        .put("startedAtEpochMs", System.currentTimeMillis())
        .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL).put("api", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT))
        .put("render", JSONObject().put("widthPx", SyntheticFixtures.WIDTH)
            .put("paddingPx", SyntheticFixtures.PADDING).put("font", SyntheticFixtures.FONT)
            .put("sizesPx", JSONArray(listOf(16, 24, 32)))
            .put("background", "#FFFFFF").put("foreground", "#000000")
            .put("antiAlias", true).put("subpixelText", true)
            .put("bitmapConfig", "ARGB_8888").put("bitmapDensity", "DENSITY_NONE")
            .put("includeFontPadding", true).put("lineSpacingMultiplier", 1.0)
            .put("wrap", "StaticLayout SIMPLE; no hyphenation; no maxLines; no ellipsis"))
        .put("normalization", "NFC + Unicode White_Space folding and trim only")
        .put("expectedTotal", SyntheticFixtures.TOTAL)
        .put("lastAttemptedCase", JSONObject.NULL)
        .put("results", results)

    fun beginCase(case: SyntheticCase) {
        document.put("lastAttemptedCase", JSONObject().put("caseId", case.id)
            .put("fontPx", case.fontPx).put("source", case.source))
    }

    fun snapshot(status: String, error: String? = null, cleanupPending: Boolean = false): JSONObject {
        document.put("status", status)
            .put("completed", status == "completed")
            .put("cancelled", status == "cancelled")
            .put("processedCount", results.length())
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedElapsed)
            .put("updatedAtEpochMs", System.currentTimeMillis())
            .put("cleanupPendingAtPublication", cleanupPending)
            .put("error", error ?: JSONObject.NULL)
        return document
    }

    companion object {
        fun caseResult(case: SyntheticCase, render: RenderedCase, raw: String?, status: String,
                       elapsedMs: Long, error: String? = null): JSONObject = JSONObject()
            .put("caseId", case.id).put("language", case.language)
            .put("blankNegative", case.id == "blank-negative")
            .put("fontPx", case.fontPx).put("widthPx", render.bitmap.width)
            .put("heightPx", render.bitmap.height).put("lineCount", render.lineCount)
            .put("textHeightPx", render.textHeight).put("source", case.source)
            .put("recognizedRaw", raw ?: JSONObject.NULL)
            .put("sourceNormalized", OcrComparison.normalize(case.source))
            .put("recognizedNormalized", raw?.let(OcrComparison::normalize) ?: JSONObject.NULL)
            .put("exactNormalizedMatch", raw?.let { OcrComparison.matches(case.source, it) } ?: JSONObject.NULL)
            .put("status", status).put("error", error ?: JSONObject.NULL)
            .put("ocrElapsedMs", elapsedMs)
    }
}

/** Calls and cancellation are serialized on the main executor. No text is logged. */
internal class ReportStore(directory: File) {
    private val file = AtomicFile(File(directory, "ocr-report.json"))

    fun write(document: JSONObject) {
        val bytes = document.toString(2).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            // AtomicFile's platform implementation can log a failed rename without throwing.
            // Read back the exact bytes before claiming a committed report.
            check(file.openRead().use { it.readBytes() }.contentEquals(bytes)) { "report_commit_failed" }
        } catch (failure: Throwable) {
            try { file.failWrite(stream) } finally { file.delete() }
            throw failure
        }
    }

    fun begin(document: JSONObject) {
        // If replacing the old success fails, remove it rather than expose it as this run.
        try { write(document) } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }
}
