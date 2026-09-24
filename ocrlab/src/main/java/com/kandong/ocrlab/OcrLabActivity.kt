package com.kandong.ocrlab

import android.app.Activity
import android.content.Intent
import com.kandong.ocrlab.context.ContextLabActivity
import com.kandong.ocrlab.multilingual.ComparisonLabActivity
import com.kandong.ocrlab.multilingual.ComparisonPlan
import com.kandong.ocrlab.multilingual.ComparisonRunner
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Launcher only. Intentionally never reads Intent, URI, extras, ClipData or saved input. */
class OcrLabActivity : Activity() {
    private val screenOwner = Any()
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        content.addView(label("看懂文字实验", 26f))
        content.addView(label("原 37 项实验仅用项目随包的合成法语、英语文字。12 句原文分别绘制为 16、24、32 像素字号，再加一张空白图片，共 37 项。", 18f))
        content.addView(label("不会读取真实屏幕、照片、相机或外部传入文字。没有翻译模型、账号或模型下载入口。下方另有三语 OCR 对照实验（识别随包合成中文图片）及人工预置中文的合成上下文实验。各实验仅在明确点击后运行；返回页面不会自动运行。", 17f))
        start = Button(this).apply {
            text = "开始 37 项合成文字实验"
            minHeight = dp(48)
            minWidth = dp(48)
            setOnClickListener {
                if (!ComparisonPlan.canStart(OcrRunner.isBusy(), ComparisonRunner.isBusy())) {
                    status.text = "原实验或三语实验仍在处理或清理；请稍后手动开始。"
                    return@setOnClickListener
                }
                val accepted = OcrRunner.start(applicationContext, screenOwner) { message, busy ->
                    status.text = message
                    start.isEnabled = !busy
                    cancel.isEnabled = busy
                }
                if (!accepted) status.text = "上一轮仍在处理或清理；不会排队启动。请稍后再次点击开始。"
            }
        }
        cancel = Button(this).apply {
            text = "取消本次实验"
            minHeight = dp(48)
            minWidth = dp(48)
            isEnabled = false
            setOnClickListener {
                status.text = OcrRunner.cancel(screenOwner) ?: "当前没有可取消的实验。"
                start.isEnabled = true
                isEnabled = false
            }
        }
        content.addView(start)
        content.addView(cancel)
        content.addView(Button(this).apply {
            text = "三语 OCR 对照实验"
            minHeight = dp(48)
            minWidth = dp(48)
            setOnClickListener {
                OcrRunner.cancel(screenOwner)
                startActivity(Intent(this@OcrLabActivity, ComparisonLabActivity::class.java))
            }
        })
        content.addView(Button(this).apply {
            text = "合成上下文与映射实验"
            minHeight = dp(48)
            minWidth = dp(48)
            setOnClickListener { startActivity(Intent(this@OcrLabActivity, ContextLabActivity::class.java)) }
        })
        status = label("尚未运行。点击开始后才会创建私有合成报告。", 17f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        content.addView(label("离开、锁屏或旋转页面会取消本次实验，停止后续用例。SDK 已接收的图片须等它完成后才能释放，清理结束前不能重开。", 16f))
        content.addView(label("OCR 只与原始句子比较，保留重音、大小写、数字、标点和否定。报告仅保存于应用私有目录 ocr-report.json，包含原文、识别原文、耗时与状态。空白结果和失败会如实记录。", 16f))
        content.addView(label("原 37 项使用 ML Kit 随包 Latin OCR 16.0.1；三语实验另使用 Chinese OCR 16.0.1。实验清单明确移除网络权限；SDK 仍含传递依赖，需核验最终 APK。此实验不代表翻译完成、真实页面质量或华为兼容性验证。", 16f))
        outer.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, -1))
        setContentView(outer)
        outer.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        start.isEnabled = true
        cancel.isEnabled = false
        status.text = if (OcrRunner.isBusy() || ComparisonRunner.isBusy()) "上一轮正在清理。清理结束后可点击开始；不会自动运行。"
            else "尚未开始新实验。请点击开始；已有私有报告可能属于之前的运行。"
    }

    override fun onStop() {
        OcrRunner.cancel(screenOwner)
        super.onStop()
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(25, 40, 34))
        setPadding(0, dp(8), 0, dp(12))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
