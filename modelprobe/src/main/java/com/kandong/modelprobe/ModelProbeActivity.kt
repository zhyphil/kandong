package com.kandong.modelprobe

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ModelProbeActivity : Activity() {
    private var token: ProbeGate.Token? = null
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                val cutout = insets.displayCutout
                @Suppress("DEPRECATION")
                view.setPadding(maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                    maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                    maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                    maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0))
            }
            insets
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, dp(8), 0, dp(8)); body.addView(this)
        }
        text("看懂模型验证", 26f)
        text("独立合成实验：中文和 Latin 识别模型，各运行英语、法语、简体、繁体、混排五组固定张量，共 10 项。CPU 单线程顺序运行。\n不读取屏幕、照片或文件，不联网；不提供完整 OCR 或翻译。", 18f)
        text("仅比较手机与宿主机同张量输出。中文乱码与参考一致也不代表识别正确。批次按裁剪宽高比排序，不是页面阅读顺序。", 17f)
        body.addView(Button(this).apply {
            text = "开始 10 项模型验证"; minHeight = dp(48)
            setOnClickListener {
                val newToken = ProbeRunner.start(applicationContext) { status.text = it }
                if (newToken != null) token = newToken else status.text = ProbeRunner.availability()
            }
        })
        body.addView(Button(this).apply {
            text = "取消本次验证"; minHeight = dp(48)
            setOnClickListener { cancel() }
        })
        // Keep actions above variable-height progress so an in-flight update cannot move a button.
        status = text(ProbeRunner.availability(), 18f)
        text("报告：应用私有目录 model-probe-report.json（schema 1，最多 512 KiB）。含运行标识、进度/状态、设备型号/API/ABI、运行时版本、耗时、资产摘要、实际形状/类型、合成原始输出及两种参考比较；不含设备序列号。每次显式运行替换报告。\n取消或离开页面立即停止接收结果，冻结取消报告；当前 native 调用返回并清理前不能重开。不会自动继续。", 16f)
        setContentView(scroll)
        scroll.requestApplyInsets()
    }
    private fun cancel() {
        val current = token ?: return
        val saved = ProbeRunner.cancel(current)
        token = null
        if (saved == null) return // A completed run keeps its truthful final status/report.
        status.text = if (saved) "本次验证已取消，取消报告已冻结；等待当前调用安全清理。可稍后手动开始。"
            else "本次验证已取消；取消报告写入失败（REPORT_IO）。等待安全清理。"
    }
    override fun onStop() { cancel(); super.onStop() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
