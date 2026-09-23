package com.kandong.qualitylab

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Future

/** Permission-free fixed synthetic inputs only, including when launched by an external Intent. */
class GpuLabActivity : Activity() {
    private var task: Future<*>? = null
    private var generation = 0
    private var stopped = true
    private var hasImages = false
    private var launchVerification = false
    private lateinit var status: TextView
    private lateinit var cards: LinearLayout
    private lateinit var validate: Button
    private lateinit var regenerate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicit self-test action accepts no external pixels, dimensions or rendering parameters.
        launchVerification = savedInstanceState == null &&
            intent.action == "com.kandong.qualitylab.VERIFY_GPU"
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 247))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        content.addView(label("GPU C · 独立实验", 25f))
        content.addView(label("相同合成小字：CPU C 与 GPU C 的3倍结果。图片按实际像素显示，左右滑动可看完整宽度。\n不读取屏幕、不联网、无权限；尚未接入正式放大镜。", 16f))
        validate = Button(this).apply {
            text = "验证并测速"
            minHeight = dp(48)
            setOnClickListener { runExperiment(true) }
        }
        content.addView(validate)
        regenerate = Button(this).apply {
            text = "重新生成3倍对照"
            minHeight = dp(48)
            setOnClickListener { runExperiment(false) }
        }
        content.addView(regenerate)
        content.addView(label("完整验证：每张样本的每个RGB通道分别检查1/2/3/5倍，1倍必须完全一致。测速：相同文字图2/3/5倍，每条路径预热3次，交错15轮。\n离开页面立即取消；返回后可重新生成或重新验证。", 14f))
        status = label("准备3倍对照…", 15f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(cards)
        content.addView(label("报告仅保存在应用私有目录：gpu-report.json、comparison-gpu-3x.png。每次运行都有新的runId与时间；只有完整验证且ready=true才可用于本次门槛判断。\n这些毫秒数不代表帧率、耗电或持续发热；全部通过也不等于生产验收。", 14f))
        content.addView(Button(this).apply { text = "返回 A / B / C 对照"; setOnClickListener { finish() } })
        outer.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, -1))
        setContentView(outer)
        outer.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        setBusy(false)
        if (!hasImages) {
            val fullSuite = launchVerification
            launchVerification = false
            runExperiment(fullSuite)
        }
    }

    override fun onStop() {
        stopped = true
        generation++
        task?.cancel(true)
        task = null
        status.text = "处理已暂停。返回后可重新生成3倍对照，或重新验证并测速。"
        super.onStop()
    }

    override fun onDestroy() {
        // Shared process queue survives Activity recreation; the old owner closes EGL before
        // any replacement Activity can begin publishing to the shared report/image paths.
        task?.cancel(true)
        // Displayed bitmaps are GC-owned: RenderThread may still retain a removed View's bitmap.
        super.onDestroy()
    }

    private fun runExperiment(fullSuite: Boolean) {
        if (stopped || isDestroyed) return
        task?.cancel(true)
        val token = ++generation
        setBusy(true)
        val run = GpuLabRun(filesDir, fullSuite) { message ->
            runOnUiThread { if (active(token)) status.text = message }
        }
        status.text = (if (fullSuite) "正在验证并测速…" else "正在生成3倍对照…") + "\nrunId: ${run.runId}"
        task = GpuLabTasks.submit {
            try {
                val result = run.execute()
                runOnUiThread {
                    if (!active(token)) {
                        // These images have never been attached to a View.
                        result.images.forEach { it.recycle() }
                    } else {
                        cards.removeAllViews()
                        result.images.forEachIndexed { index, image ->
                            cards.addView(label(if (index == 0) "CPU C · 原参考 · 3倍" else "GPU C · 实验 · 3倍", 19f))
                            cards.addView(pixelScroll(image))
                        }
                        hasImages = true
                        status.text = format(result.report)
                        setBusy(false)
                    }
                }
            } catch (_: CancellationException) {
                // The owner thread already closed EGL and wrote a cancelled report.
            } catch (failure: Throwable) {
                runOnUiThread {
                    if (active(token)) {
                        status.text = "GPU实验失败：${failure.javaClass.simpleName}: ${failure.message}\nrunId: ${run.runId}\n详情见私有gpu-report.json。可重新生成。"
                        setBusy(false)
                    }
                }
            }
        }
    }

    private fun active(token: Int) = token == generation && !stopped && !isDestroyed
    private fun setBusy(busy: Boolean) { validate.isEnabled = !busy; regenerate.isEnabled = !busy }

    private fun format(report: JSONObject): String {
        val runId = report.getString("runId")
        if (!report.getBoolean("fullSuite")) return "3倍对照已就绪；尚未执行完整验证。\nrunId: $runId"
        val numerical = report.getBoolean("numericalPassed")
        val timing = report.getBoolean("timingGate3and5")
        val heading = if (report.getBoolean("allPassed")) "GPU验证完成"
            else "GPU实验未通过：" + listOfNotNull(if (!numerical) "数值门槛失败" else null,
                if (!timing) "3/5倍性能门槛失败" else null).joinToString("；")
        val benchmarks = report.getJSONArray("benchmarks")
        val rows = (0 until benchmarks.length()).joinToString("\n") { index ->
            val row = benchmarks.getJSONObject(index)
            fun time(key: String): String {
                val stats = row.getJSONObject(key)
                return String.format(Locale.ROOT, "%.2f / %.2f", stats.getDouble("medianMs"), stats.getDouble("p95Ms"))
            }
            "${row.getInt("scale")}倍：\nCPU生成 ${time("cpuRenderAndBitmap")} ms\n上传+GPU完成 ${time("gpuUploadAndCompletion")} ms\n读回+Bitmap ${time("gpuReadbackAndBitmap")} ms\nGPU端到端 ${time("gpuEndToEnd")} ms"
        }
        val tests = report.getJSONArray("tests")
        val failed = (0 until tests.length()).map { tests.getJSONObject(it) }.filter { !it.getBoolean("passed") }
        val failureDetails = if (failed.isEmpty()) "" else "\n失败样本：" + failed.take(6).joinToString("、") {
            "${it.getString("fixture")} ${it.getInt("scale")}倍"
        } + "（逐通道实测值见报告）"
        return "$heading\n数值：${if (numerical) "通过" else "未通过"}；3/5倍性能：${if (timing) "通过" else "未通过"}" +
            "$failureDetails\n中位数 / P95（15轮P95为最大值）\n$rows\n不是GPU纯耗时或FPS。\nrunId: $runId"
    }

    private fun pixelScroll(bitmap: Bitmap) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = true
        addView(object : View(this@GpuLabActivity) {
            init { contentDescription = "固定合成文字3倍对照，按实际像素显示" }
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                setMeasuredDimension(bitmap.width, bitmap.height)
            }
            override fun onDraw(canvas: Canvas) { canvas.drawBitmap(bitmap, 0f, 0f, null) }
        }, ViewGroup.LayoutParams(bitmap.width, bitmap.height))
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(32, 48, 43)); setPadding(0, dp(6), 0, dp(8))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
