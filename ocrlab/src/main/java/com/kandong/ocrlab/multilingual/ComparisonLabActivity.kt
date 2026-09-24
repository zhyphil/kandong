package com.kandong.ocrlab.multilingual

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kandong.ocrlab.OcrRunner

/** Internal explicit-button lab. Never consumes intents, files, URIs or restored task requests. */
class ComparisonLabActivity : Activity() {
    private val owner = Any()
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var cancel: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        content.addView(label("三语 OCR 对照实验", 26f))
        content.addView(label("仅使用 18 个随包合成输入：英语、法语、简体中文、繁体中文、混排及空白。每个输入分别用设备原生绘制与固定 PNG、原图与精确 2 倍像素复制、Latin 与 Chinese 两个模型识别，共 144 项。", 18f))
        content.addView(label("不会读取真实屏幕、截图、照片、相机或外部文字；没有翻译、自动点击、下载模型或上传。测试语言标签仅用于评分，不代表 SDK 自动判断出英语或法语。两个模型均处理全部输入，不合并或挑选答案。", 17f))
        start = Button(this).apply {
            text = "开始 144 项三语对照"; minHeight = dp(48); minWidth = dp(48)
            setOnClickListener {
                val accepted = ComparisonRunner.start(applicationContext, owner) { message, busy ->
                    status.text = message; start.isEnabled = !busy; cancel.isEnabled = busy
                }
                if (!accepted) status.text = "原实验或三语实验仍在处理或清理。请稍后手动重试；不会排队或自动开始。"
            }
        }
        cancel = Button(this).apply {
            text = "取消本次对照"; minHeight = dp(48); minWidth = dp(48); isEnabled = false
            setOnClickListener {
                status.text = ComparisonRunner.cancel(owner) ?: "当前没有可取消的实验。"
                start.isEnabled = true; isEnabled = false
            }
        }
        content.addView(start); content.addView(cancel)
        status = label("尚未运行。", 17f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        content.addView(label("离开、锁屏或旋转会取消并丢弃迟到结果；在途图片由 SDK 完成后释放，清理结束前两个 OCR 实验均不能重开。返回不会自动运行。", 16f))
        content.addView(label("报告只写应用私有文件 ocr-comparison-report.json，与原 37 项报告分开。包含合成原文、SDK 原始文字与层级位置、像素哈希、耗时和设备型号/API/fingerprint（无序列号）。报告超过 10 MiB 会失败，不截断证据。", 16f))
        content.addView(label("使用随包 Latin / Chinese OCR 16.0.1；无新增权限。运行完成与识别正确分别计数；此实验不证明真实页面质量、翻译完成或华为兼容性。", 16f))
        outer.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, -1))
        setContentView(outer); outer.requestApplyInsets()
    }
    override fun onStart() {
        super.onStart()
        start.isEnabled = true; cancel.isEnabled = false
        status.text = if (ComparisonRunner.isBusy() || OcrRunner.isBusy()) "上一轮正在处理或清理。结束后可手动开始；不会自动运行。"
        else "尚未开始本轮。已有私有报告可能属于之前的运行。"
    }
    override fun onStop() { ComparisonRunner.cancel(owner); super.onStop() }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(25, 40, 34)); setPadding(0, dp(8), 0, dp(12))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
