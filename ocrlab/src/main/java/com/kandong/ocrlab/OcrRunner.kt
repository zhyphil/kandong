package com.kandong.ocrlab

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
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.util.concurrent.Executor

/** Process-wide, main-executor-confined ownership; no Activity-bound Task listener. */
internal object OcrRunner {
    private val handler = Handler(Looper.getMainLooper())
    private val completionExecutor = Executor { command -> handler.post(command) }
    private val gate = RunGate()
    private var current: Run? = null

    private class Run(
        val token: RunGate.Token,
        val screenOwner: Any,
        context: Context,
        var observer: ((String, Boolean) -> Unit)?
    ) {
        val report = OcrReport()
        val store = ReportStore(context.filesDir)
        var cases: List<SyntheticCase> = emptyList()
        var index = 0
        var recognizer: OnceResource<TextRecognizer>? = null
        var flight: Flight? = null
    }

    private class Flight(val case: SyntheticCase, val rendered: RenderedCase) {
        val bitmap = OnceResource<Bitmap>(rendered.bitmap) { it.recycle() }
        val started = SystemClock.elapsedRealtime()
        var consumed = false
    }

    fun isBusy(): Boolean = current != null

    /** Only the Activity's explicit button invokes this. There is no queued restart. */
    fun start(context: Context, screenOwner: Any, observer: (String, Boolean) -> Unit): Boolean {
        checkMain()
        val token = gate.acquire() ?: return false
        val run = try {
            Run(token, screenOwner, context.applicationContext, observer)
        } catch (_: Throwable) {
            gate.release(token)
            observer("无法初始化实验；没有启动 OCR。", false)
            return true
        }
        current = run
        try {
            // Replace any old success before fixture loading or SDK initialization.
            run.store.begin(run.report.snapshot("running"))
        } catch (_: Throwable) {
            finish(run, "failed", "initial_report_write_failed")
            return true
        }
        try {
            run.cases = SyntheticFixtures.load(context.applicationContext)
            run.recognizer = OnceResource(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)) { it.close() }
        } catch (_: Throwable) {
            finish(run, "failed", "initialization_failed")
            return true
        }
        if (publish(run, "正在识别固定合成文字：0 / 37\nrunId: ${run.report.runId}", true)) {
            handler.post { next(run) }
        }
        return true
    }

    /** The cancellation event may record its status; later SDK callbacks may only clean up. */
    fun cancel(screenOwner: Any): String? {
        checkMain()
        val run = current ?: return null
        if (run.screenOwner !== screenOwner || !gate.cancel(run.token)) return null
        run.observer = null // Immediately release the Activity, including during rotation.
        val saved = try {
            run.store.write(run.report.snapshot("cancelled", cleanupPending = run.flight != null))
            true
        } catch (_: Throwable) { false }
        if (run.flight == null) cleanupCancelled(run)
        return if (saved) "已取消；迟到结果不会写入。若 SDK 仍在处理，请等待清理后再次点击开始。"
        else "已取消，但取消报告保存失败；不能把旧文件当成本轮完成证据。"
    }

    private fun next(run: Run) {
        checkMain()
        if (!gate.canPublish(run.token)) return
        if (run.index == run.cases.size) {
            finish(run, "completed")
            return
        }
        val case = run.cases[run.index]
        val rendered = try {
            run.report.beginCase(case)
            SyntheticFixtures.render(case)
        } catch (_: Throwable) {
            finish(run, "failed", "render_failed")
            return
        }
        val flight = Flight(case, rendered)
        if (!gate.beginTask(run.token)) {
            flight.bitmap.close()
            return
        }
        run.flight = flight
        val task = try {
            run.recognizer!!.value.process(InputImage.fromBitmap(rendered.bitmap, 0))
        } catch (_: Throwable) {
            try {
                run.report.results.put(OcrReport.caseResult(case, rendered, null, "failed",
                    SystemClock.elapsedRealtime() - flight.started, "synchronous_process_failed"))
            } catch (_: Throwable) { /* lastAttemptedCase still identifies the failed input. */ }
            disposeFlight(run, flight)
            finish(run, "failed", "synchronous_process_failed")
            return
        }
        try {
            // No Activity overload: this listener survives onStop and must own final cleanup.
            task.addOnCompleteListener(completionExecutor) { completed -> complete(run, flight, completed) }
        } catch (_: Throwable) {
            // process() may already be using the Bitmap. Never recycle on listener setup failure.
            abortPending(run, "completion_listener_failed")
            awaitCleanup(run, flight, task)
        }
    }

    private fun complete(run: Run, flight: Flight, task: Task<Text>) {
        checkMain()
        // Both listener and exceptional polling path can arrive; consume once on the main queue.
        if (flight.consumed) return
        flight.consumed = true
        var result: JSONObject? = null
        var failure: String? = null
        try {
            if (gate.canPublish(run.token)) {
                val elapsed = SystemClock.elapsedRealtime() - flight.started
                when {
                    task.isCanceled -> {
                        failure = "sdk_task_cancelled"
                        result = OcrReport.caseResult(flight.case, flight.rendered, null, "cancelled_task", elapsed, failure)
                    }
                    !task.isSuccessful -> {
                        failure = "ocr_task_failed"
                        result = OcrReport.caseResult(flight.case, flight.rendered, null, "failed", elapsed, failure)
                    }
                    else -> {
                        val raw = task.result.text
                        val status = if (OcrComparison.normalize(raw).isEmpty()) "no_text" else "recognized"
                        result = OcrReport.caseResult(flight.case, flight.rendered, raw, status, elapsed)
                    }
                }
            }
        } catch (_: Throwable) {
            failure = "completion_callback_failed"
            try {
                result = OcrReport.caseResult(flight.case, flight.rendered, null, "failed",
                    SystemClock.elapsedRealtime() - flight.started, failure)
            } catch (_: Throwable) { /* Preserve run failure even if result serialization fails. */ }
        } finally {
            if (!disposeFlight(run, flight)) failure = "bitmap_cleanup_failed"
        }
        if (!gate.owns(run.token)) return
        if (!gate.canPublish(run.token)) {
            cleanupCancelled(run)
            return
        }
        try {
            result?.let { run.report.results.put(it) }
            if (failure != null) {
                finish(run, "failed", failure)
                return
            }
            run.index++
            run.store.write(run.report.snapshot("running"))
            if (publish(run, "已处理 ${run.index} / 37\nrunId: ${run.report.runId}\n精确匹配仅是诊断，不代表翻译或真机验收。", true)) {
                handler.post { next(run) }
            }
        } catch (_: Throwable) {
            finish(run, "failed", "completion_or_report_write_failed")
        }
    }

    private fun disposeFlight(run: Run, flight: Flight): Boolean {
        var clean = true
        try { flight.bitmap.close() } catch (_: Throwable) { clean = false }
        if (run.flight === flight) {
            run.flight = null
            gate.endTask(run.token)
        }
        return clean
    }

    private fun closeRecognizer(run: Run): Boolean {
        val recognizer = run.recognizer
        run.recognizer = null
        return try { recognizer?.close(); true } catch (_: Throwable) { false }
    }

    private fun cleanupCancelled(run: Run) {
        if (!gate.owns(run.token) || run.flight != null) return
        // No observer or report publication from late callbacks, even when close fails.
        closeRecognizer(run)
        release(run)
    }

    private fun release(run: Run) {
        run.observer = null
        gate.cancel(run.token)
        if (gate.release(run.token) && current === run) current = null
    }

    private fun finish(run: Run, status: String, error: String? = null) {
        if (!gate.canPublish(run.token)) {
            cleanupCancelled(run)
            return
        }
        if (run.flight != null) {
            abortPending(run, error ?: "unexpected_pending_task")
            return
        }
        val clean = closeRecognizer(run)
        val finalStatus = if (clean) status else "failed"
        val finalError = if (clean) error else "recognizer_close_failed"
        val saved = try {
            run.store.write(run.report.snapshot(finalStatus, finalError))
            true
        } catch (_: Throwable) { false }
        val message = when {
            !saved -> "报告保存失败；本轮不能视为成功。"
            finalStatus == "completed" -> "37 项合成 OCR 运行结束，报告已保存。\n请逐项检查原文、识别结果和精确匹配诊断；没有准确率通过门槛。"
            else -> "实验未完成：${finalError ?: "unknown_failure"}。报告标为失败。"
        } + "\nrunId: ${run.report.runId}"
        // The observer is called only under current publication ownership.
        try {
            run.observer?.invoke(message, false)
        } catch (_: Throwable) {
            run.observer = null
            try { run.store.write(run.report.snapshot("failed", "observer_callback_failed")) }
            catch (_: Throwable) { /* No successful publication or text/stack logs. */ }
        }
        release(run)
    }

    private fun publish(run: Run, message: String, busy: Boolean): Boolean {
        if (!gate.canPublish(run.token)) return false
        return try {
            run.observer?.invoke(message, busy)
            true
        } catch (_: Throwable) {
            run.observer = null
            finish(run, "failed", "observer_callback_failed")
            false
        }
    }

    private fun abortPending(run: Run, error: String) {
        if (!gate.canPublish(run.token)) return
        val observer = run.observer
        run.observer = null
        gate.cancel(run.token)
        val saved = try {
            run.store.write(run.report.snapshot("failed", error, cleanupPending = true))
            true
        } catch (_: Throwable) { false }
        try {
            observer?.invoke(if (saved) "实验失败：$error；等待 SDK 清理后才能重试。"
                else "实验失败且报告写入失败；等待 SDK 清理后才能重试。", false)
        } catch (_: Throwable) { /* Detached and invalidated already. */ }
    }

    /** Used only if listener registration itself throws after process() accepted the input. */
    private fun awaitCleanup(run: Run, flight: Flight, task: Task<Text>) {
        if (flight.consumed) return
        val finished = try { task.isComplete } catch (_: Throwable) { false }
        if (finished) complete(run, flight, task)
        else handler.postDelayed({ awaitCleanup(run, flight, task) }, 100L)
    }

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper())
}
