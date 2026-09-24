package com.kandong.modelprobe

import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Immutable synthetic result, created only AFTER actual inference. */
data class ProbeRecord(val model: ProbeModel, val task: ProbeTask, val decoded: Decoded, val elapsedMs: Long) {
    val rawMatches = decoded.raw == task.expectedRaw
    val argmaxMatches = decoded.argmaxSha256 == task.expectedArgmax
}

/** Schema 1: operational status is independent of raw/argmax host consistency.
 * Raw text is synthetic fixture output in tensor batch order, never OCR quality evidence.
 * Cancellation freezes main-thread accepted results and records cleanup as pending.
 */
object ProbeReport {
    const val CAP = 512 * 1024
    const val FINAL = "model-probe-report.json"
    private const val STAGE = "model-probe-report.stage.json"
    fun encode(token: ProbeGate.Token, status: String, records: List<ProbeRecord>, elapsed: Long,
        runtime: String, error: String?, cleanup: Cleanup? = null): ByteArray {
        val result = JSONObject()
            .put("schema", 1).put("runId", token.id)
            .put("scope", "packaged_synthetic_same_tensor_feasibility_not_full_OCR_or_translation")
            .put("operationalStatus", status).put("plannedCount", 10).put("completedCount", records.size)
            .put("manifestSha256", ProbeInputs.MANIFEST_SHA).put("elapsedMs", elapsed)
            .put("runtime", runtime).put("api", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("execution", "CPU_SEQUENTIAL_intra1_inter1_ALL_OPT_arenaOff_telemetryOff")
            .put("environmentLifetime", "process_singleton_OrtEnvironment_close_is_no_op")
            .put("rowOrder", "crop_aspect_ratio_tensor_batch_order_not_page_reading_order")
            .put("comparisonRule", "exact_raw_strings_no_NFC_or_whitespace_changes; argmax_int32LE_full_N_T")
            .put("qualityCaveat", "Matching Latin Chinese garbage references is not Chinese OCR correctness")
            .put("hostConsistency", JSONObject().put("comparedCount", records.size)
                .put("rawMatchingCount", records.count { it.rawMatches })
                .put("argmaxMatchingCount", records.count { it.argmaxMatches })
                .put("allTenRawMatch", records.size == 10 && records.all { it.rawMatches })
                .put("allTenArgmaxMatch", records.size == 10 && records.all { it.argmaxMatches }))
            .put("errorCode", error ?: JSONObject.NULL)
            .put("cleanup", if (cleanup == null) JSONObject().put("state", "pending_at_cancellation_frozen") else
                JSONObject().put("state", if (cleanup.uncertain) "uncertain_process_blocked" else "closed")
                    .put("opened", cleanup.opened).put("closeAttempts", cleanup.closeAttempts).put("closed", cleanup.closed))
        val rows = JSONArray()
        records.forEach { record ->
            val t = record.task; val m = record.model
            rows.put(JSONObject().put("id", t.id).put("sourceInputId", t.source).put("sourcePixelSha256", t.sourceSha)
                .put("modelSha256", m.sha).put("modelBytes", m.bytes).put("dictionarySha256", m.dictionarySha)
                .put("vocabulary", m.vocabulary).put("compressedSha256", t.compressedSha)
                .put("inputSha256", t.tensorSha).put("inputShape", JSONArray(t.shape.toList()))
                .put("inputDtype", "float32LE").put("outputShape", JSONArray(record.decoded.shape))
                .put("outputDtype", "FLOAT").put("rawDecoded", JSONArray(record.decoded.raw))
                .put("argmaxSha256", record.decoded.argmaxSha256).put("rawMatchesHost", record.rawMatches)
                .put("argmaxMatchesHost", record.argmaxMatches).put("expectedHostRaw", JSONArray(t.expectedRaw))
                .put("expectedHostArgmaxSha256", t.expectedArgmax)
                .put("hostPipelineLinesReadingOrder", JSONArray(t.readingOrder)).put("elapsedMs", record.elapsedMs))
        }
        result.put("results", rows)
        return result.toString().toByteArray(Charsets.UTF_8).also { requireProbe(it.size <= CAP, "REPORT_CAP") }
    }
    private fun write(file: File, bytes: ByteArray) {
        requireProbe(bytes.size <= CAP, "REPORT_CAP")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Throwable) { atomic.failWrite(stream); throw e }
        val readback = atomic.openRead().use { ProbeInputs.bounded(it, CAP, bytes.size) }
        requireProbe(readback.contentEquals(bytes), "REPORT_READBACK")
    }
    fun stage(directory: File, bytes: ByteArray) = write(File(directory, STAGE), bytes)
    /** Called on the main thread only, after checking the current token. */
    fun publishStage(directory: File) {
        requireProbe(File(directory, STAGE).renameTo(File(directory, FINAL)), "REPORT_PUBLISH")
    }
    fun cancelFinal(directory: File, bytes: ByteArray) = write(File(directory, FINAL), bytes)
    fun deleteStage(directory: File) {
        for (suffix in listOf("", ".new", ".bak")) {
            val f = File(directory, STAGE + suffix)
            requireProbe(!f.exists() || f.delete(), "STAGE_CLEANUP")
        }
    }
}
