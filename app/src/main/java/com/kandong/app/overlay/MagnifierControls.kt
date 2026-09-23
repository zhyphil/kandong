package com.kandong.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small touchable controls only; the native system owns the lens and its drag/resize UI. */
internal class MagnifierControls(
    private val service: AccessibilityService,
    private val setScale: (Float) -> Unit,
    private val stop: () -> Unit,
    private val failed: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var collapsed = false
    private var top = true
    val visible get() = root?.isAttachedToWindow == true

    fun show() {
        if (root != null) return
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 250, 246)); cornerRadius = dp(12).toFloat()
                setStroke(dp(2), Color.rgb(7, 94, 84))
            }
        }
        fun row() = LinearLayout(service).also { panel.addView(it) }
        fun button(row: LinearLayout, text: String, action: () -> Unit) {
            row.addView(Button(service).apply {
                this.text = text; textSize = 18f; minWidth = 0; minimumWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                setTextColor(Color.rgb(7, 70, 63))
                setOnClickListener { try { action() } catch (_: RuntimeException) { failed() } }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        if (collapsed) button(row(), "🔍 展开") { rebuild { collapsed = false } }
        else {
            label = TextView(service).apply { text = "原文放大"; textSize = 20f; setTextColor(Color.BLACK) }
                .also { panel.addView(it) }
            val scales = row()
            listOf(2f, 3f, 4f).forEach { scale -> button(scales, "${scale.toInt()}倍") { setScale(scale) } }
            val actions = row()
            button(actions, "收起") { rebuild { collapsed = true } }
            button(actions, "移位") { rebuild { top = !top } }
            button(actions, "关闭", stop)
        }
        val width = if (collapsed) dp(132) else minOf(dp(296), wm.currentWindowMetrics.bounds.width() - dp(16))
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.END or if (top) Gravity.TOP else Gravity.BOTTOM
            title = "看懂放大镜控制"; x = dp(8); y = dp(12)
        }
        root = panel
        wm.addView(panel, params)
    }
    fun update(scale: Float) { label?.text = "原文放大 · ${scale.toInt()}倍" }
    private fun rebuild(change: () -> Unit) { close(); change(); show() }
    fun close() {
        root?.let { if (it.isAttachedToWindow) wm.removeViewImmediate(it) }
        root = null; label = null
    }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}
