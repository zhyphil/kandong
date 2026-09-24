package com.kandong.ocrlab.context

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A bounded in-app simulation. It never creates an overlay or reads another app. */
class ProtectedTranslationLabActivity : Activity() {
    private val flow = OnDemandTranslation(ProtectedDisplayFixtures.provider)
    private lateinit var pages: List<ProtectedDisplayFixtures.Page>
    private var page = TranslationPage(1, 1, 1)
    private var pageIndex = 0
    private var roiIndex = 0
    private var captures = 0
    private var delivery: Runnable? = null
    private lateinit var status: TextView
    private lateinit var mirror: TextView
    private lateinit var notice: TextView
    private lateinit var source: TextView
    private lateinit var fullPage: TextView
    private lateinit var pageLabel: TextView
    private lateinit var translate: Button
    private lateinit var main: View
    private lateinit var bubble: View
    private lateinit var menu: View
    private var collapsed = false
    private var menuOpen = false
    private val selected get() = pages[pageIndex]
    private val expiry = object : Runnable {
        override fun run() { refresh(); status.postDelayed(this, 500) }
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun label(tag: String, size: Float) = TextView(this).apply {
        this.tag = tag; textSize = size; setTextColor(Color.rgb(25, 43, 45)); setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    private fun button(tag: String, title: String, action: () -> Unit) = Button(this).apply {
        this.tag = tag; text = title; minHeight = dp(48); minWidth = dp(48)
        setOnClickListener { action(); refresh() }
    }
    private fun row(parent: LinearLayout, vararg buttons: Button) {
        parent.addView(LinearLayout(this).apply { buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pages = ProtectedDisplayFixtures.load(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20)) }
        body.addView(label("title", 25f).apply { text = "译文展示实验" })
        body.addView(label("disclosure", 15f).apply { text = "使用已保存的合成结果，仅验证展示与清理。没有实时翻译，也不读取其他 App。" })
        pageLabel = label("page-label", 17f); body.addView(pageLabel)
        status = label("status", 16f); body.addView(status)
        mirror = label("mirror", 28f).apply { minHeight = dp(100); setBackgroundColor(Color.rgb(231, 245, 239)) }; body.addView(mirror)
        notice = label("notice", 18f).apply { setTextColor(Color.rgb(128, 64, 0)) }; body.addView(notice)
        source = label("selected-source", 16f); body.addView(source)
        translate = button("translate", "翻译") {
            val ticket = flow.clickTranslate(now())
            if (ticket != null) {
                captures++
                if (flow.captured(ticket, selected.snapshot(page, now()), selected.roi(roiIndex), now())) {
                    flow.pending?.let { request ->
                        val response = selected.response(request)
                        delivery = Runnable { flow.accept(response, now()); delivery = null; refresh() }
                        status.postDelayed(delivery!!, 350)
                    }
                }
            }
        }
        row(body, translate, button("next-region", "下一处") {
            roiIndex = (roiIndex + 1) % selected.targets.size
            flow.select(selected.roi(roiIndex), now())
        })
        row(body, button("pause", "收起") { cancel(ClearReason.PAUSE); collapsed = true },
            button("menu", "菜单") { openMenu() })
        row(body, button("next-page", "切换示例页") {
            cancel(ClearReason.PAGE_CHANGE); pageIndex = (pageIndex + 1) % pages.size; roiIndex = 0
            page = page.copy(generation = page.generation + 1); flow.observePage(page)
        }, button("page-change", "模拟页面变化") {
            cancel(ClearReason.PAGE_CHANGE); page = page.copy(revision = page.revision + 1); flow.observePage(page)
        })
        fullPage = label("full-page", 16f); body.addView(fullPage)
        main = ScrollView(this).apply { tag = "main"; addView(body) }
        bubble = LinearLayout(this).apply {
            tag = "bubble"; orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(button("resume", "恢复放大镜") { collapsed = false })
            addView(button("bubble-menu", "菜单") { openMenu() })
        }
        menu = LinearLayout(this).apply {
            tag = "menu-pane"; orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); setBackgroundColor(Color.WHITE)
            addView(label("menu-title", 28f).apply { text = "实验菜单" })
            addView(label("menu-note", 20f).apply { text = "进入菜单已清除本页结果。返回后需要再次点击翻译。" })
            addView(button("close-menu", "返回") { menuOpen = false })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE); addView(main, FrameLayout.LayoutParams(-1, -1))
            addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))
            addView(menu, FrameLayout.LayoutParams(-1, -1))
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
        flow.observePage(page); setContentView(root); root.requestApplyInsets(); refresh()
    }
    private fun openMenu() { cancel(ClearReason.MENU); menuOpen = true }
    private fun cancel(reason: ClearReason) {
        delivery?.let { status.removeCallbacks(it) }; delivery = null; flow.invalidate(reason)
    }
    private fun refresh() {
        val frame = flow.render(now()); val card = frame.cards.singleOrNull()
        val presentation = card?.presentation()
        mirror.text = presentation?.text ?: selected.source(roiIndex).text
        notice.text = presentation?.notice.orEmpty(); notice.visibility = if (notice.text.isEmpty()) View.GONE else View.VISIBLE
        source.text = "原文：${selected.source(roiIndex).text}"
        pageLabel.text = "示例 ${pageIndex + 1}/${pages.size} · 选区 ${roiIndex + 1}/${selected.targets.size}"
        val busy = flow.phase == OnDemandTranslation.Phase.CAPTURING || flow.phase == OnDemandTranslation.Phase.PROCESSING
        translate.isEnabled = !busy
        val state = when (flow.phase) {
            OnDemandTranslation.Phase.PROCESSING, OnDemandTranslation.Phase.CAPTURING -> "正在准备本页示例…"
            OnDemandTranslation.Phase.DISPLAYING -> "已显示本页结果，可移动到下一处。"
            OnDemandTranslation.Phase.NEEDS_REFRESH -> "页面已变化或过期，请再次点击翻译。"
            OnDemandTranslation.Phase.FAILED -> "本次未完成，请再次点击翻译。"
            OnDemandTranslation.Phase.ORIGINAL -> "显示原文；点击翻译后才处理本页。"
        }
        status.text = "$state\n实验处理次数：$captures"
        fullPage.text = "整页原文（合成）\n" + selected.template.blocks.sortedBy { it.order }.joinToString("\n") { it.text }
        main.visibility = if (!collapsed && !menuOpen) View.VISIBLE else View.GONE
        bubble.visibility = if (collapsed && !menuOpen) View.VISIBLE else View.GONE
        menu.visibility = if (menuOpen) View.VISIBLE else View.GONE
    }
    override fun onStart() { super.onStart(); status.post(expiry) }
    override fun onStop() { status.removeCallbacks(expiry); cancel(ClearReason.STOP); refresh(); super.onStop() }
}
