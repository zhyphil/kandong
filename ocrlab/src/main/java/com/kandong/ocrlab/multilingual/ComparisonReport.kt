package com.kandong.ocrlab.multilingual

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import com.google.mlkit.vision.text.Text
import com.kandong.ocrlab.OcrComparison
import com.kandong.ocrlab.SyntheticFixtures
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class ComparisonReport {
    val runId = UUID.randomUUID().toString()
    private val started = SystemClock.elapsedRealtime()
    private val results = JSONArray()
    private var resultBytes = 0
    var recognizedCount = 0; private set
    var mismatchCount = 0; private set
    var operationalCompletedCount = 0; private set
    private val document = JSONObject()
        .put("schema", 1).put("runId", runId).put("scope", "fixed_packaged_synthetic_ocr_only")
        .put("fixtureVersion", "trilingual-v1").put("manifestSha256", ComparisonPlan.MANIFEST_SHA256)
        .put("originalFixtureSha256", SyntheticFixtures.SHA256)
        .put("models", JSONObject().put("latin", "com.google.mlkit:text-recognition:16.0.1")
            .put("chinese", "com.google.mlkit:text-recognition-chinese:16.0.1"))
        .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT))
        .put("startedAtEpochMs", System.currentTimeMillis()).put("expectedTotal", ComparisonPlan.TOTAL)
        .put("expectedInputs", 18).put("expectedBaseInputs", 36).put("expectedProcessedInputs", 72)
        .put("pixelHashFormat", "row-major RGBA8888, opaque alpha 255; dimensions separate")
        .put("nativeRender", "unchanged SyntheticFixtures.render; sans-serif NORMAL; padding 32; width 640")
        .put("normalization", "NFC + Unicode White_Space folding and trim only")
        .put("languagePolicy", "fixture language is scoring metadata only; SDK unknown/und does not establish EN/FR support")
        .put("lastAttemptedTask", JSONObject.NULL).put("results", results)

    fun beginTask(task: ComparisonTask) { document.put("lastAttemptedTask", task.taskId) }
    fun append(result: JSONObject) {
        val bytes = result.toString().toByteArray(Charsets.UTF_8).size
        require(resultBytes + bytes < ComparisonStore.MAX_BYTES - 65536) { "report_size_limit" }
        results.put(result); resultBytes += bytes
        if (result.getString("status") in listOf("recognized", "no_text")) {
            operationalCompletedCount++
            if (result.getString("status") == "recognized") recognizedCount++
            if (!result.getBoolean("exactNormalizedMatch")) mismatchCount++
        }
    }
    fun snapshot(status: String, error: String? = null, cleanupPending: Boolean = false): JSONObject = document
        .put("status", status).put("completed", status == "completed").put("cancelled", status == "cancelled")
        .put("processedCount", results.length()).put("operationalCompletedCount", operationalCompletedCount)
        .put("recognizedCount", recognizedCount).put("mismatchCount", mismatchCount)
        .put("errorCount", results.length() - operationalCompletedCount)
        .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("updatedAtEpochMs", System.currentTimeMillis())
        .put("cleanupPendingAtPublication", cleanupPending).put("error", error ?: JSONObject.NULL)

    companion object {
        fun result(task: ComparisonTask, input: ComparisonInput, image: ComparisonImage, text: Text?,
                   elapsed: Long, error: String? = null): JSONObject {
            val budget = EvidenceBudget()
            val raw = text?.text?.also { budget.text(it) }
            val status = if (error != null) "failed" else if (raw.isNullOrBlank()) "no_text" else "recognized"
            val blocks = JSONArray()
            text?.textBlocks?.forEach { block ->
                val lines = JSONArray()
                val blockJson = node(block.text, block.boundingBox, block.recognizedLanguage, null, image, budget)
                block.lines.forEach { line ->
                    val elements = JSONArray()
                    val lineJson = node(line.text, line.boundingBox, line.recognizedLanguage, line.confidence, image, budget)
                    line.elements.forEach { element ->
                        elements.put(node(element.text, element.boundingBox, element.recognizedLanguage,
                            element.confidence, image, budget))
                    }
                    lines.put(lineJson.put("elements", elements))
                }
                blocks.put(blockJson.put("lines", lines))
            }
            return JSONObject().put("taskId", task.taskId).put("inputId", task.inputId)
                .put("caseId", input.case.id).put("engine", task.engine).put("sourceKind", task.sourceKind)
                .put("language", input.case.language).put("languageRole", "fixture_scoring_label_only")
                .put("source", input.case.source).put("fontPx", input.case.fontPx)
                .put("blankNegative", input.case.id == "blank-negative")
                .put("fixedReference", JSONObject().put("asset", input.asset).put("pngSha256", input.pngSha256)
                    .put("pixelSha256", input.pixelSha256).put("width", input.width).put("height", input.height))
                .put("base", JSONObject().put("pixelSha256", image.basePixelSha256)
                    .put("width", image.baseWidth).put("height", image.baseHeight).put("lineCount", image.lineCount))
                .put("processed", JSONObject().put("pixelSha256", image.processedPixelSha256)
                    .put("width", image.bitmap.width).put("height", image.bitmap.height)
                    .put("scaleX", image.scaleX.toDouble()).put("scaleY", image.scaleY.toDouble())
                    .put("algorithm", if (task.scale == 1) "identity-v1" else "nearest-neighbor-exact-2x2-v1"))
                .put("recognizedRaw", raw ?: JSONObject.NULL).put("blocks", blocks)
                .put("sdkRecognizedLanguage", JSONObject.NULL).put("sdkLanguageStatus", "not_available_at_document_level")
                .put("sourceNormalized", OcrComparison.normalize(input.case.source))
                .put("recognizedNormalized", raw?.let(OcrComparison::normalize) ?: JSONObject.NULL)
                .put("exactNormalizedMatch", raw?.let { OcrComparison.matches(input.case.source, it) } ?: JSONObject.NULL)
                .put("status", status).put("error", error ?: JSONObject.NULL).put("ocrElapsedMs", elapsed)
        }
        private fun node(text: String, rect: Rect?, language: String?, confidence: Float?,
                         image: ComparisonImage, budget: EvidenceBudget): JSONObject {
            budget.node(); budget.text(text); language?.let { budget.text(it) }
            val bounds = rect?.let { intArrayOf(it.left, it.top, it.right, it.bottom) }
            return JSONObject().put("text", text)
                .put("boundsRaw", bounds?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                .put("boundsUnscaled", bounds?.let {
                    JSONArray(ComparisonPlan.unscale(it, image.scaleX, image.scaleY).map(Float::toDouble))
                } ?: JSONObject.NULL)
                .put("sdkRecognizedLanguage", language ?: JSONObject.NULL)
                .put("sdkLanguageStatus", if (language.isNullOrBlank() || language == "und") "unknown" else "sdk_reported")
                .put("confidence", confidence?.takeIf { it.isFinite() && it in 0f..1f }?.toDouble() ?: JSONObject.NULL)
                .put("confidenceStatus", if (confidence != null && confidence.isFinite() && confidence in 0f..1f)
                    "sdk_reported" else "unknown")
        }
        private class EvidenceBudget {
            private var nodes = 0
            private var characters = 0
            fun node() { require(++nodes <= 2048) { "result_node_limit" } }
            fun text(value: String) {
                require(value.length <= 32768 && characters.toLong() + value.length <= 65536) { "result_text_limit" }
                characters += value.length
            }
        }
    }
}

internal class ComparisonStore(directory: File) {
    private val file = AtomicFile(File(directory, "ocr-comparison-report.json"))
    fun write(document: JSONObject) {
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "report_size_limit" }
        val stream = file.startWrite()
        try {
            stream.write(bytes); stream.fd.sync(); file.finishWrite(stream)
            check(file.openRead().use { it.readBytes() }.contentEquals(bytes)) { "report_commit_failed" }
        } catch (failure: Throwable) {
            try { file.failWrite(stream) } finally { file.delete() }
            throw failure
        }
    }
    fun begin(document: JSONObject) {
        try { write(document) } catch (failure: Throwable) { file.delete(); throw failure }
    }
    companion object { const val MAX_BYTES = 10 * 1024 * 1024 }
}
