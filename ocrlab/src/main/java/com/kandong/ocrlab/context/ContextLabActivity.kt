package com.kandong.ocrlab.context

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Internal synthetic-only demo; never consumes Intent/extras, URI, clipboard or saved page content. */
class ContextLabActivity : Activity() {
    private val engine = ContextEngine()
    private var scene = 0
    private var roiPreset = 0
    private var session = 0L
    private var submitted: FixtureRequest? = null
    private var displayedFrame: ContextRender? = null
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var drawing: View
    private lateinit var zoom: SeekBar
    private var message = "尚未载入；点击载入合成页。"
    private val expiry = object : Runnable {
        override fun run() {
            // Refresh text and drawing together, including the transition to an empty page.
            refresh()
            status.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val e = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(e.left, e.top, e.right, e.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        // Disclosure is outside the scrolling area and remains visible.
        outer.addView(label("合成上下文与映射实验\n仅测试合成上下文、源 ID 绑定与几何。分组和中文均为人工预置；没有运行翻译模型，没有读取真实页面。", 16f))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(16)) }
        status = label(message, 16f); body.addView(status)
        fun button(text: String, action: () -> Unit) {
            body.addView(Button(this).apply {
                this.text = text; minHeight = dp(48); minWidth = dp(48)
                setOnClickListener {
                    try { action() } catch (_: IllegalArgumentException) { message = "无效合成选区或变换；未生成结果。" }
                    refresh()
                }
            })
        }
        button("切换场景（切换后需载入）") {
            scene = (scene + 1) % ContextFixtures.scenes.size; roiPreset = 0
            reset(ClearReason.PAGE_CHANGE); message = "已选择：${ContextFixtures.scenes[scene]}；请载入。"
        }
        button("载入 / 重新载入合成页") {
            submitted = null
            val now = now()
            engine.activateSnapshot(ContextFixtures.page(scene, ++session, now), now)
            roiPreset = 0; engine.select(ContextFixtures.presets(scene)[0].roi, now)
            zoom.progress = 25
            message = "已载入合成页；有效期 60 秒为实验参数。"
        }
        button("切换 ROI 预设（复用整页）") {
            roiPreset = (roiPreset + 1) % ContextFixtures.presets(scene).size
            engine.select(ContextFixtures.presets(scene)[roiPreset].roi, now())
            message = "ROI：${ContextFixtures.presets(scene)[roiPreset].name}"
        }
        body.addView(label("镜面倍率 1～5；平移仅改变锚点几何", 16f))
        zoom = SeekBar(this).apply {
            max = 100; progress = 25; minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    engine.transform?.let { engine.setTransform(it.copy(scale = 1.0 + value / 25.0), now()) }
                    refresh()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        body.addView(zoom)
        button("镜面平移：起点 / 最大溢出") {
            engine.transform?.let { t -> engine.setTransform(t.copy(panX = if (t.panX == 0.0) 10_000.0 else 0.0, panY = if (t.panY == 0.0) 10_000.0 else 0.0), now()) }
        }
        button("提交演示请求") {
            if (submitted == null) {
                submitted = engine.requestMissing(now())
                message = if (submitted == null) "无可提交目标：未载入、缺失证据、已缓存或已在途。" else "请求已冻结；可移框 / 调倍率后交付。"
            } else message = "演示保留一个待交付请求；请交付或撤销。"
        }
        button("交付人工预置结果") {
            val request = submitted
            val response = request?.let(ContextFixtures::response)
            message = if (response == null) {
                request?.let { engine.cancelRequest(it.id) }
                "此选区没有预置响应；没有运行模型。"
            } else if (engine.acceptResponse(response, now())) "预置响应结构已接收；不代表语义验证。" else "已拒绝失效或不匹配的响应。"
            submitted = null
        }
        button("撤销待交付请求") { submitted?.let { engine.cancelRequest(it.id) }; submitted = null; message = "请求已撤销。" }
        button("清空：模拟收起 / 暂停") { reset(ClearReason.PAUSE) }
        button("清空：模拟菜单") { reset(ClearReason.MENU) }
        button("清空：模拟停止") { reset(ClearReason.STOP) }
        details = label("", 16f); body.addView(details)
        drawing = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                setMeasuredDimension(w, (w * 790f / 420f).toInt())
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val page = engine.snapshot ?: return
                val factor = width / 420f
                canvas.save(); canvas.scale(factor, factor); canvas.translate(10f, 10f)
                fun rect(r: ContextRect, color: Int, stroke: Boolean = true) {
                    paint.color = color; paint.style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL; paint.strokeWidth = 2f
                    canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), paint)
                }
                rect(ContextRect(0.0, 0.0, 400.0, 600.0), Color.GRAY)
                page.blocks.forEach { b ->
                    rect(b.visible, Color.LTGRAY)
                    paint.color = Color.DKGRAY; paint.style = Paint.Style.FILL; paint.textSize = 11f
                    canvas.drawText("${b.id}: ${b.text}", b.visible.left.toFloat(), b.visible.top.toFloat() + 17, paint)
                }
                engine.selection?.let { rect(it.roi, Color.RED) }
                canvas.translate(0f, 635f)
                paint.style = Paint.Style.FILL; paint.color = Color.BLACK; paint.textSize = 13f
                canvas.drawText("镜面：仅源锚点；中文卡片独立排版", 0f, -12f, paint)
                engine.transform?.let { t -> rect(ContextRect(0.0, 0.0, t.width, t.height), Color.GRAY) }
                // Drawing consumes the last UI frame; it must never expire or mutate the engine.
                displayedFrame?.anchors.orEmpty().forEach { anchor ->
                    rect(anchor.rect, Color.BLUE)
                    paint.style = Paint.Style.FILL; paint.textSize = 10f
                    canvas.drawText(anchor.sourceId, anchor.rect.left.toFloat(), anchor.rect.top.toFloat() + 12, paint)
                }
                canvas.restore()
            }
        }.apply { contentDescription = "完整合成页、红色 ROI 和镜面源锚点；可读内容见上方文本。" }
        body.addView(drawing, LinearLayout.LayoutParams(-1, dp(760)))
        outer.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer); outer.requestApplyInsets(); refresh()
    }
    override fun onStart() { super.onStart(); status.post(expiry) }
    override fun onStop() {
        status.removeCallbacks(expiry); reset(ClearReason.STOP); refresh(); super.onStop()
    }
    private fun reset(reason: ClearReason) {
        engine.clear(reason); submitted = null; message = "已清空 $reason；返回不会自动恢复，请重新载入。"
    }
    private fun refresh() {
        if (!::details.isInitialized || !::drawing.isInitialized) return
        val rendered = engine.render(now())
        displayedFrame = rendered
        if (engine.snapshot == null) submitted = null
        status.text = "$message\n场景：${ContextFixtures.scenes[scene]} · 状态：${engine.lastClear ?: if (engine.snapshot == null) "尚未载入" else "合成上下文有效"}\n倍率：${engine.transform?.scale ?: "—"} · 在途：${engine.pendingCount} · 缓存：${engine.cacheCount}"
        details.text = buildString {
            append("坐标仅为未缩放合成屏幕单位；没有验证 Android 坐标兼容性。\n")
            engine.selection?.targets?.forEach { t -> append("目标 ${t.id}\n源 ${t.sources.map { it.id }}\n上下文 ${t.context.map { it.id }}\n") }
            rendered.cards.forEach { c -> append("\n${c.targetId} · 原文：${c.sourceText}\n人工预置中文：${c.chinese ?: "无"}\n${c.reason ?: "仅结构匹配"}\n") }
            engine.snapshot?.let { page ->
                append("\n完整合成页（${page.blocks.size} 块；移框不重读）\n")
                page.blocks.forEach { append("${it.id}: ${it.text.ifEmpty { "[无文字图标]" }} [${it.state}]\n") }
                page.coverageReasons.forEach { append("$it\n") }
            }
        }
        drawing.invalidate()
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.BLACK); setPadding(dp(8), dp(6), dp(8), dp(6))
    }
}
