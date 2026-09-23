package com.kandong.qualitylab

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Separate, permission-free comparison app. Never binds to the production magnifier. */
class QualityLabActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var generation = 0
    private var stopped = true
    private var renderedScale = 0
    private var scale = 3
    private lateinit var sample: Bitmap
    private lateinit var pixels: IntArray
    private lateinit var status: TextView
    private lateinit var cards: LinearLayout
    private val zoomButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scale = savedInstanceState?.getInt("scale", 3)?.takeIf { it in listOf(2, 3, 5) } ?: 3
        sample = LabRenderer.sample()
        pixels = IntArray(LabRenderer.WIDTH * LabRenderer.HEIGHT)
        sample.getPixels(pixels, 0, LabRenderer.WIDTH, 0, 0, LabRenderer.WIDTH, LabRenderer.HEIGHT)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 247))
        }
        outer.setOnApplyWindowInsetsListener { view, insets ->
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
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }
        content.addView(label("本机画质对照", 26f))
        content.addView(label("同一张小字图片，比较边缘、笔画和白边。\n全部在手机处理，不联网，也不读取你的屏幕。", 16f))
        val controls = LinearLayout(this)
        for (value in listOf(2, 3, 5)) {
            val button = Button(this).apply {
                text = "${value}倍"
                contentDescription = "对照倍率${value}倍"
                minHeight = dp(48)
                setOnClickListener { scale = value; updateZoom(); runComparison(false) }
            }
            zoomButtons += button
            controls.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        content.addView(controls)
        content.addView(label("这些倍率只用于实验对照；原放大镜仍支持连续调节。\n图片按真实像素显示，左右滑动可看完整宽度。", 14f))
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            text = "重新生成"; setOnClickListener { runComparison(false) }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(Button(this).apply {
            text = "测处理耗时"; setOnClickListener { runComparison(true) }
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        content.addView(actions)
        status = label("准备生成…", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        content.addView(label("原始小字 · 320 × 160 像素", 16f))
        content.addView(pixelScroll(sample))
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(cards)
        content.addView(label("看清了，才有意义\n观察“未/末、日/目、己/已”和小数点；如果有笔画粘连、变淡或白边，请选更容易读的效果。\n本页只是画质实验，尚未接入正式放大镜。", 16f))
        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(outer)
        outer.requestApplyInsets()
        updateZoom()
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        if (renderedScale != scale) runComparison(false)
    }

    override fun onStop() {
        stopped = true
        generation++
        task?.cancel(true)
        status.text = "已暂停处理；需要时可重新生成或测量。"
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        // Displayed bitmaps remain GC-owned: never recycle something the render thread may hold.
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("scale", scale)
        super.onSaveInstanceState(outState)
    }

    private fun runComparison(benchmark: Boolean) {
        task?.cancel(true)
        val token = ++generation
        val selectedScale = scale
        status.text = if (benchmark) "正在测量本机处理耗时…" else "正在生成${selectedScale}倍对照…"
        task = worker.submit {
            val images = mutableListOf<Bitmap>()
            try {
                for (variant in 0..2) {
                    checkCancellation()
                    images += LabRenderer.render(sample, pixels, selectedScale, variant)
                }
                val report = if (benchmark) benchmark(selectedScale) else null
                checkCancellation()
                LabRenderer.exportComparison(filesDir, sample, images, selectedScale)
                checkCancellation()
                report?.let { File(filesDir, "benchmark-${selectedScale}x.json").writeText(it.toString(2)) }
                runOnUiThread {
                    if (token != generation || stopped || isDestroyed) {
                        images.forEach { it.recycle() }
                    } else {
                        renderedScale = selectedScale
                        cards.removeAllViews()
                        images.forEachIndexed { index, bitmap -> addCard(index, bitmap) }
                        status.text = if (report == null) "${selectedScale}倍对照已就绪 · 可向下滚动比较 A / B / C"
                            else formatReport(report)
                    }
                }
            } catch (_: CancellationException) {
                images.forEach { it.recycle() }
            } catch (error: Exception) {
                images.forEach { it.recycle() }
                runOnUiThread {
                    if (token == generation && !stopped && !isDestroyed) {
                        status.text = "本次对照未完成（${error.javaClass.simpleName}），可重新生成。"
                    }
                }
            }
        }
    }

    private fun benchmark(selectedScale: Int): JSONObject {
        val timings = Array(3) { mutableListOf<Double>() }
        // Warm all variants before measurements. Rotate order to reduce order/thermal bias.
        repeat(2) { for (variant in 0..2) {
            checkCancellation()
            LabRenderer.render(sample, pixels, selectedScale, variant).recycle()
        } }
        repeat(9) { round ->
            repeat(3) { offset ->
                checkCancellation()
                val variant = (round + offset) % 3
                val start = SystemClock.elapsedRealtimeNanos()
                val bitmap = LabRenderer.render(sample, pixels, selectedScale, variant)
                timings[variant] += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                bitmap.recycle()
            }
        }
        val variants = JSONArray()
        timings.forEachIndexed { index, values ->
            val ordered = values.sorted()
            variants.put(JSONObject().put("variant", index).put("name", LabRenderer.names[index])
                .put("samplesMs", JSONArray(values)).put("medianMs", ordered[4])
                .put("p95Ms", ordered[8]))
        }
        return JSONObject().put("model", Build.MODEL).put("api", Build.VERSION.SDK_INT)
            .put("scale", selectedScale).put("sourceWidth", LabRenderer.WIDTH)
            .put("sourceHeight", LabRenderer.HEIGHT).put("warmupsPerVariant", 2)
            .put("rounds", 9).put("variants", variants)
            .put("scope", "CPU render + output bitmap allocation only. Excludes capture, UI, PNG export. Not overlay FPS or battery.")
    }

    private fun formatReport(report: JSONObject): String {
        val rows = report.getJSONArray("variants")
        return "本机处理耗时（中位数 / P95）\n" + (0..2).joinToString("\n") {
            val row = rows.getJSONObject(it)
            "${('A'.code + it).toChar()}：" + String.format(Locale.ROOT, "%.1f / %.1f ms", row.getDouble("medianMs"), row.getDouble("p95Ms"))
        } + "\n仅CPU生成图片，不能代表实时帧率、发热或耗电。"
    }

    private fun addCard(index: Int, bitmap: Bitmap) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
        }
        card.addView(label(LabRenderer.names[index], 18f))
        card.addView(pixelScroll(bitmap))
        cards.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }

    private fun pixelScroll(bitmap: Bitmap): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = true
        addView(object : View(this@QualityLabActivity) {
            init { contentDescription = "固定合成文字的像素对照图片" }
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // HorizontalScrollView passes an unspecified width. Keep physical bitmap pixels.
                setMeasuredDimension(bitmap.width, bitmap.height)
            }
            override fun onDraw(canvas: Canvas) { canvas.drawBitmap(bitmap, 0f, 0f, null) }
        }, ViewGroup.LayoutParams(bitmap.width, bitmap.height))
    }

    private fun updateZoom() {
        zoomButtons.forEachIndexed { index, button ->
            button.isSelected = listOf(2, 3, 5)[index] == scale
            button.setTextColor(if (button.isSelected) Color.rgb(16, 108, 89) else Color.DKGRAY)
            button.isEnabled = !button.isSelected
        }
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(32, 48, 43))
        setPadding(0, dp(6), 0, dp(8))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
    }
}
