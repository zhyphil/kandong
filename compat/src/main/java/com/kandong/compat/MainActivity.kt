package com.kandong.compat

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Separate Android10 compatibility experiment; no accessibility permission required. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var consent: CheckBox
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 64, 40, 48) }
        fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(0xFF123E38.toInt()); setPadding(0, 12, 0, 12) }.also(body::addView)
        fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; textSize = 20f; minHeight = 160; setOnClickListener { action() } }.also(body::addView)
        text("看懂 · 放大镜", 32f)
        text("Android 10 兼容验证", 20f)
        text("看不清时，放大一小块。原来的页面和字体大小保持不变。", 22f)
        text("本版需要您允许悬浮窗，并在每次开始时允许系统屏幕共享。运行时会持续取得屏幕画面，在手机内截出取景框区域放大；不会录制、保存、联网或上传。停止后结束共享。", 18f)
        text("取景框和放大窗要分开放，避免把放大窗再次采进去。请先用普通网页或合成测试页；密码、银行或其他私密页面请先停止。受保护内容可能显示黑屏。", 18f)
        consent = CheckBox(this).apply { text = "我已了解，同意本次屏幕共享放大"; textSize = 18f }.also(body::addView)
        consent.setOnCheckedChangeListener { _, checked ->
            if (!checked) stopService(Intent(this, ProjectionMagnifierService::class.java))
        }
        button("1. 允许悬浮窗") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("2. 打开放大镜") {
            when {
                !consent.isChecked -> status.text = "请先阅读说明，并同意本次使用。"
                !Settings.canDrawOverlays(this) -> status.text = "请先允许看懂显示悬浮窗，再返回。"
                ProjectionMagnifierService.running -> status.text = "放大镜已开启。切换到要查看的普通页面。"
                else -> {
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(
                        android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                        else manager.createScreenCaptureIntent()
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, 91)
                }
            }
        }
        button("关闭放大镜") {
            stopService(Intent(this, ProjectionMagnifierService::class.java))
            status.text = "已请求停止放大和屏幕共享。"
        }
        status = text("请先允许悬浮窗，然后开始。系统授权需要您亲自确认。", 18f)
        text("开启后：切换到其他 App，拖动“移动取景框”，用 2× / 3× / 4× 调整。放大窗挡住内容时点“换边”。想结束时点“停止”。", 18f)
        setContentView(ScrollView(this).apply { addView(body) })
    }
    @Deprecated("Platform activity result kept dependency-free for this spike")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 91) return
        if (resultCode != RESULT_OK || data == null) { status.text = "未获得屏幕共享授权，没有开始。"; return }
        if (!consent.isChecked || !Settings.canDrawOverlays(this)) return
        startForegroundService(Intent(this, ProjectionMagnifierService::class.java)
            .putExtra("resultCode", resultCode).putExtra("projectionConsent", data))
        status.text = "请切换到要查看的页面。放大窗可调倍数，取景框可以拖动。"
    }
}
