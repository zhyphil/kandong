package com.kandong.modelprobe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

/** Process-wide executor/gate. No queued callback captures an Activity or observer. */
object ProbeRunner {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "model-probe-worker") }
    private val gate = ProbeGate()
    private var observer: ((String) -> Unit)? = null
    private var active: ProbeGate.Token? = null
    private var directory: File? = null
    private var accepted = emptyList<ProbeRecord>()
    private var started = 0L
    private var runtime = "not_initialized"
    private fun main() = check(Looper.myLooper() == Looper.getMainLooper())
    fun availability(): String {
        main()
        return when { gate.poisoned -> "清理状态不确定；请结束进程后重试。"
            gate.busy() -> "上一轮仍在运行或清理，请稍后手动开始。"
            else -> "尚未开始。仅运行随包合成张量，不读取当前屏幕。" }
    }
    fun start(context: Context, observe: (String) -> Unit): ProbeGate.Token? {
        main()
        val token = gate.begin() ?: return null
        val app = context.applicationContext
        val dir = app.filesDir
        active = token; directory = dir; accepted = emptyList(); runtime = "not_initialized"
        started = SystemClock.elapsedRealtime(); observer = observe
        // Stale stages are touched only as part of an explicit new start.
        try { ProbeReport.deleteStage(dir) } catch (_: Throwable) {
            observer = null; active = null; gate.finish(token, true); return null
        }
        observer?.invoke("准备校验固定资产，0 / 10。")
        executor.execute { work(app, dir, token) }
        return token
    }
    fun cancel(token: ProbeGate.Token): Boolean? {
        main()
        if (active !== token) return null
        observer = null // Detach before signalling the worker; no native close/interrupt here.
        if (!gate.cancel(token)) return true
        return try {
            ProbeReport.cancelFinal(checkNotNull(directory), ProbeReport.encode(token, "cancelled", accepted,
                SystemClock.elapsedRealtime() - started, runtime, "CANCELLED"))
            true
        } catch (_: Throwable) { false }
    }
    private fun work(context: Context, dir: File, token: ProbeGate.Token) {
        val cleanup = Cleanup()
        val records = ArrayList<ProbeRecord>()
        val start = SystemClock.elapsedRealtime()
        var version = "not_initialized"
        var error: String? = null
        var staged = false
        try {
            token.checkpoint()
            val inputs = ProbeInputs { context.assets.open(it) }
            val manifest = inputs.manifest()
            token.checkpoint()
            val engine = OrtProbeEngine(cleanup)
            version = engine.runtime
            val capturedVersion = version
            handler.post { if (gate.accepts(token)) runtime = capturedVersion }
            for (model in manifest.models) {
                token.checkpoint()
                val dictionary = inputs.dictionary(model)
                val modelBytes = inputs.model(model)
                token.checkpoint()
                engine.withModel(modelBytes) { session ->
                    for (task in manifest.tasks.filter { it.model == model.id }) {
                        token.checkpoint()
                        val taskStart = SystemClock.elapsedRealtime()
                        val input = inputs.tensor(task)
                        token.checkpoint()
                        val decoded = engine.infer(session, input, task.outputShape, dictionary)
                        token.checkpoint()
                        requireProbe(!cleanup.uncertain, "NATIVE_CLEANUP")
                        // Expected values become visible to comparison ONLY after inference.
                        val record = ProbeRecord(model, task, decoded, SystemClock.elapsedRealtime() - taskStart)
                        records += record
                        handler.post { accept(token, record) }
                    }
                }
                requireProbe(!cleanup.uncertain, "NATIVE_CLEANUP")
            }
        } catch (failure: ProbeFailure) { error = failure.code }
        catch (_: Throwable) { error = "PROBE_FAILED" }
        if (cleanup.uncertain) error = "NATIVE_CLEANUP"
        // All per-run native resources are closed before stage IO. Cancelled stages cannot publish.
        try {
            if (!token.cancelled.get()) {
                val status = if (error == null && records.size == 10) "completed" else "failed"
                ProbeReport.stage(dir, ProbeReport.encode(token, status, records.toList(),
                    SystemClock.elapsedRealtime() - start, version, error, cleanup))
                staged = true
            }
        } catch (_: Throwable) { error = "REPORT_IO" }
        val finalError = error
        val finalStaged = staged
        val finalCount = records.size
        handler.post { finish(token, dir, finalStaged, finalError, finalCount, cleanup.uncertain) }
    }
    private fun accept(token: ProbeGate.Token, record: ProbeRecord) {
        main()
        if (!gate.accepts(token)) return
        accepted = accepted + record
        observer?.invoke("已运行 ${accepted.size} / 10；原文一致 ${accepted.count { it.rawMatches }}；argmax 一致 ${accepted.count { it.argmaxMatches }}。")
    }
    private fun finish(token: ProbeGate.Token, dir: File, staged: Boolean, error: String?, count: Int, uncertain: Boolean) {
        main()
        var cleanupUncertain = uncertain
        var finalError = error
        try {
            if (gate.accepts(token) && staged) ProbeReport.publishStage(dir)
        } catch (_: Throwable) { finalError = "REPORT_PUBLISH" }
        finally {
            try { ProbeReport.deleteStage(dir) } catch (_: Throwable) { cleanupUncertain = true; finalError = "STAGE_CLEANUP" }
        }
        if (gate.accepts(token)) {
            observer?.invoke(if (finalError == null && count == 10 && staged)
                "10 / 10 运行完成。原文一致 ${accepted.count { it.rawMatches }} / 10，argmax 一致 ${accepted.count { it.argmaxMatches }} / 10。报告已保存；此结果不代表 OCR 正确率。"
            else "验证未完成：${finalError ?: "PROBE_FAILED"}。已运行 $count / 10。")
        }
        observer = null
        active = null
        // Neither a new Activity nor a rapid tap can reopen until stage/native cleanup ends.
        gate.finish(token, cleanupUncertain)
    }
}
