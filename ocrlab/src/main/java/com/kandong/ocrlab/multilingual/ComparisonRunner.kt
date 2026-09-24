package com.kandong.ocrlab.multilingual

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kandong.ocrlab.OcrRunner
import com.kandong.ocrlab.OnceResource
import com.kandong.ocrlab.RunGate
import org.json.JSONObject
import java.util.concurrent.Executor

/** Process-wide gate and non-Activity task listeners keep draining ownership across rotation. */
internal object ComparisonRunner {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val gate = RunGate()
    private var current: Run? = null
    private class Run(val token: RunGate.Token, val owner: Any, val context: Context, val profile: ComparisonProfile,
                      var observer: ((String, Boolean) -> Unit)?) {
        val report = ComparisonReport(profile)
        val store = ComparisonStore(context.filesDir)
        var inputs: Map<String, ComparisonInput> = emptyMap()
        var cursor = ComparisonCursor(emptyList())
        val recognizers = mutableMapOf<String, OnceResource<TextRecognizer>>()
        var flight: Flight? = null
    }
    private class Flight(val task: ComparisonTask, val input: ComparisonInput, val image: ComparisonImage) {
        val bitmap = OnceResource<Bitmap>(image.bitmap) { it.recycle() }
        val started = SystemClock.elapsedRealtime()
        var consumed = false
    }
    fun isBusy() = current != null
    fun start(context: Context, owner: Any, profile: ComparisonProfile = ComparisonProfile.BASELINE, observer: (String, Boolean) -> Unit): Boolean {
        checkMain()
        if (!ComparisonPlan.canStart(OcrRunner.isBusy(), isBusy())) return false
        val token = gate.acquire() ?: return false
        val run = try { Run(token, owner, context.applicationContext, profile, observer) } catch (_: Throwable) {
            gate.release(token); observer("无法初始化三语实验；没有启动 OCR。", false); return true
        }
        current = run
        try { run.store.begin(run.report.snapshot("running")) } catch (_: Throwable) {
            finish(run, "failed", "initial_report_write_failed"); return true
        }
        try {
            val inputs = ComparisonInputs.load(run.context)
            run.inputs = inputs.associateBy { it.inputId }
            run.cursor = ComparisonCursor(ComparisonPlan.matrix(inputs.map { it.inputId }, profile))
            run.recognizers["latin"] = OnceResource(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)) { it.close() }
            run.recognizers["chinese"] = OnceResource(TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())) { it.close() }
        } catch (_: Throwable) {
            finish(run, "failed", "fixture_or_model_initialization_failed"); return true
        }
        if (publish(run, "正在识别：0 / 144\n仅随包合成图片，无翻译。\nrunId: ${run.report.runId}", true))
            handler.post { next(run) }
        return true
    }
    fun cancel(owner: Any): String? {
        checkMain()
        val run = current ?: return null
        if (run.owner !== owner || !gate.cancel(run.token)) return null
        run.observer = null
        val saved = try {
            run.store.write(run.report.snapshot("cancelled", cleanupPending = run.flight != null)); true
        } catch (_: Throwable) { false }
        if (run.flight == null) cleanupCancelled(run)
        return if (saved) "已取消（完成 ${run.cursor.completedCount} / 144）；迟到结果不会发布。SDK 清理期间不能重开。"
        else "已取消，但取消报告保存失败；旧文件不能作为本轮完成证据。"
    }
    private fun next(run: Run) {
        checkMain()
        val descriptor = run.cursor.next(gate.canPublish(run.token))
        if (!gate.canPublish(run.token)) return
        if (descriptor == null) { finish(run, "completed"); return }
        run.report.beginTask(descriptor)
        val input = run.inputs.getValue(descriptor.inputId)
        val image = try { ComparisonInputs.render(run.context, input, descriptor.sourceKind, descriptor.scale, descriptor.algorithm) }
        catch (_: Throwable) { finish(run, "failed", "input_validation_or_render_failed"); return }
        val flight = Flight(descriptor, input, image)
        if (!gate.beginTask(run.token)) { flight.bitmap.close(); return }
        run.flight = flight
        val task = try { run.recognizers.getValue(descriptor.engine).value.process(InputImage.fromBitmap(image.bitmap, 0)) }
        catch (_: Throwable) {
            try { run.report.append(ComparisonReport.result(descriptor, input, image, null,
                SystemClock.elapsedRealtime() - flight.started, "synchronous_process_failed")) } catch (_: Throwable) { }
            disposeFlight(run, flight); finish(run, "failed", "synchronous_process_failed"); return
        }
        try { task.addOnCompleteListener(executor) { complete(run, flight, it) } }
        catch (_: Throwable) {
            abortPending(run, "completion_listener_failed")
            awaitCleanup(run, flight, task)
        }
    }
    private fun complete(run: Run, flight: Flight, task: Task<Text>) {
        checkMain()
        if (flight.consumed) return
        flight.consumed = true
        var result: JSONObject? = null
        var error: String? = null
        try {
            if (gate.canPublish(run.token)) {
                error = when { task.isCanceled -> "sdk_task_cancelled"; !task.isSuccessful -> "ocr_task_failed"; else -> null }
                result = ComparisonReport.result(flight.task, flight.input, flight.image,
                    if (error == null) task.result else null, SystemClock.elapsedRealtime() - flight.started, error)
            }
        } catch (_: Throwable) { error = "result_serialization_or_limit_failed" }
        finally { if (!disposeFlight(run, flight)) error = "bitmap_cleanup_failed" }
        if (!gate.owns(run.token)) return
        if (!gate.canPublish(run.token)) { cleanupCancelled(run); return }
        try {
            result?.let { run.report.append(it) }
            if (error != null) { finish(run, "failed", error); return }
            run.cursor.completed()
            run.store.write(run.report.snapshot("running"))
            if (publish(run, "已完成 ${run.cursor.completedCount} / 144 项识别任务\n" +
                    "有文字 ${run.report.recognizedCount}；严格不匹配 ${run.report.mismatchCount}\n" +
                    "任务完成不代表识别正确。\nrunId: ${run.report.runId}", true)) handler.post { next(run) }
        } catch (_: Throwable) { finish(run, "failed", "report_size_or_write_failed") }
    }
    private fun disposeFlight(run: Run, flight: Flight): Boolean {
        var clean = true
        try { flight.bitmap.close() } catch (_: Throwable) { clean = false }
        if (run.flight === flight) { run.flight = null; gate.endTask(run.token) }
        return clean
    }
    private fun closeRecognizers(run: Run): Boolean {
        var clean = true
        for (recognizer in run.recognizers.values) try { recognizer.close() } catch (_: Throwable) { clean = false }
        run.recognizers.clear()
        return clean
    }
    private fun cleanupCancelled(run: Run) {
        if (!gate.owns(run.token) || run.flight != null) return
        closeRecognizers(run)
        release(run) // Late completion only releases ownership; frozen report is never rewritten.
    }
    private fun release(run: Run) {
        run.observer = null; gate.cancel(run.token)
        if (gate.release(run.token) && current === run) current = null
    }
    private fun finish(run: Run, status: String, error: String? = null) {
        if (!gate.canPublish(run.token)) { cleanupCancelled(run); return }
        if (run.flight != null) { abortPending(run, error ?: "unexpected_pending_task"); return }
        val clean = closeRecognizers(run)
        val finalStatus = if (clean) status else "failed"
        val finalError = if (clean) error else "recognizer_close_failed"
        val saved = try { run.store.write(run.report.snapshot(finalStatus, finalError)); true } catch (_: Throwable) { false }
        val message = when {
            !saved -> "报告保存失败；本轮不能视为成功。"
            finalStatus == "completed" -> "144 项三语对照任务已完成，私有报告已保存。\n" +
                "有文字 ${run.report.recognizedCount}；严格不匹配 ${run.report.mismatchCount}。\n" +
                "这不代表真实页面质量、翻译或兼容性通过。"
            else -> "实验未完成：${finalError ?: "unknown_failure"}；报告已标记失败。"
        } + "\nrunId: ${run.report.runId}"
        try { run.observer?.invoke(message, false) } catch (_: Throwable) {
            run.observer = null
            try { run.store.write(run.report.snapshot("failed", "observer_callback_failed")) } catch (_: Throwable) { }
        }
        release(run)
    }
    private fun publish(run: Run, message: String, busy: Boolean): Boolean {
        if (!gate.canPublish(run.token)) return false
        return try { run.observer?.invoke(message, busy); true } catch (_: Throwable) {
            run.observer = null; finish(run, "failed", "observer_callback_failed"); false
        }
    }
    private fun abortPending(run: Run, error: String) {
        if (!gate.canPublish(run.token)) return
        val observer = run.observer
        run.observer = null; gate.cancel(run.token)
        val saved = try { run.store.write(run.report.snapshot("failed", error, cleanupPending = true)); true }
        catch (_: Throwable) { false }
        try { observer?.invoke(if (saved) "实验失败：$error；等待 SDK 清理。" else "实验失败且报告保存失败；等待 SDK 清理。", false) }
        catch (_: Throwable) { }
    }
    private fun awaitCleanup(run: Run, flight: Flight, task: Task<Text>) {
        if (flight.consumed) return
        val done = try { task.isComplete } catch (_: Throwable) { false }
        if (done) complete(run, flight, task) else handler.postDelayed({ awaitCleanup(run, flight, task) }, 100L)
    }
    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper())
}
