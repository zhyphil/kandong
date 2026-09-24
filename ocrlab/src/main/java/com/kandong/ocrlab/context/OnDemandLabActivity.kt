package com.kandong.ocrlab.context

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Synthetic UI only. No intent input, screen reader, projection, OCR or translation provider. */
class OnDemandLabActivity : Activity() {
    private val flow = OnDemandTranslation()
    private var page = TranslationPage(1, 1, 1)
    private var roiIndex = 0
    private var captures = 0
    private var delivery: Runnable? = null
    private lateinit var status: TextView
    private lateinit var mirror: TextView
    private lateinit var translate: Button
    private val expiry = object : Runnable {
        override fun run() { refresh(); status.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)); setBackgroundColor(Color.WHITE) }
        fun label(text: String, size: Float) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.rgb(25, 43, 45)); setPadding(0, dp(8), 0, dp(8))
        }
        fun button(text: String, action: () -> Unit): Button = Button(this).apply {
            this.text = text; minHeight = dp(48); minWidth = dp(48)
            setOnClickListener { action(); refresh() }; body.addView(this)
        }
        body.addView(label("点击翻译 · 交互实验", 26f))
        body.addView(label("原文和中文均为随包人工示例。没有运行翻译模型，没有读取真实屏幕。", 17f))
        body.addView(label("当前页面（合成）\n" + OnDemandFixtures.source.joinToString("\n"), 18f))
        status = label("", 17f); body.addView(status)
        translate = button("翻译（人工演示）") {
            val ticket = flow.clickTranslate(now())
            if (ticket != null) {
                captures++
                if (flow.captured(ticket, OnDemandFixtures.page(page, now()), OnDemandFixtures.roi(roiIndex), now())) {
                    flow.pending?.let { request ->
                        val response = OnDemandFixtures.response(request)
                        delivery = Runnable { flow.accept(response, now()); delivery = null; refresh() }
                        status.postDelayed(delivery!!, 350)
                    }
                }
            }
        }
        button("显示原文") { cancel(ClearReason.PAUSE) }
        button("移动取景框（演示）") {
            roiIndex = (roiIndex + 1) % OnDemandFixtures.source.size
            flow.select(OnDemandFixtures.roi(roiIndex), now())
        }
        button("模拟页面变化") {
            cancel(ClearReason.PAGE_CHANGE); page = page.copy(revision = page.revision + 1); flow.observePage(page)
        }
        body.addView(label("放大镜展示区（演示）", 20f))
        mirror = label("", 27f).apply { setPadding(dp(14), dp(18), dp(14), dp(18)); setBackgroundColor(Color.rgb(232, 244, 241)) }
        body.addView(mirror)
        val scroll = ScrollView(this).apply {
            addView(body)
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
        flow.observePage(page); setContentView(scroll); scroll.requestApplyInsets(); refresh()
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun cancel(reason: ClearReason) {
        delivery?.let { status.removeCallbacks(it) }; delivery = null; flow.invalidate(reason)
    }
    private fun refresh() {
        val frame = flow.render(now())
        val busy = flow.phase == OnDemandTranslation.Phase.CAPTURING || flow.phase == OnDemandTranslation.Phase.PROCESSING
        translate.isEnabled = !busy
        val state = when (flow.phase) {
            OnDemandTranslation.Phase.ORIGINAL -> "显示原文；点击翻译后才处理当前页面。"
            OnDemandTranslation.Phase.CAPTURING, OnDemandTranslation.Phase.PROCESSING -> "正在准备本页的人工演示结果…"
            OnDemandTranslation.Phase.DISPLAYING -> "当前页结果有效；移动取景框可查看对应内容。"
            OnDemandTranslation.Phase.NEEDS_REFRESH -> "页面已变化或结果过期，请再次点击翻译。"
            OnDemandTranslation.Phase.FAILED -> "本次未完成，请重新点击翻译。"
        }
        status.text = "$state\n实验处理次数：$captures"
        mirror.text = frame.cards.singleOrNull()?.chinese ?: OnDemandFixtures.source[roiIndex]
    }
    override fun onStart() { super.onStart(); status.post(expiry) }
    override fun onStop() {
        status.removeCallbacks(expiry); cancel(ClearReason.STOP); refresh(); super.onStop()
    }
}
