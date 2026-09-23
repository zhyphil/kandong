package com.kandong.fixture

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Synthetic local fixture. Never a dependency of the production application. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isFocusableInTouchMode = true; setPadding(24, 0, 24, 40) }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        fun text(label: String): TextView = TextView(this).apply { text = label; textSize = 22f }.also(content::addView)
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 22f; minHeight = dp(56); setOnClickListener { action() }
        }.also(content::addView)
        text("本地合成测试页，不含真实个人信息")
        intent.getStringExtra("testSession")?.let { text(it) }
        // Leave room for the control overlay, keeping the primary target visible below it.
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(240)))
        var count = 0
        val counter = text("点击次数：0")
        val primary = button("测试按钮") { count++; counter.text = "点击次数：$count" }
        primary.id = android.view.View.generateViewId()
        var moved = false
        button("移动测试按钮") { moved = !moved; primary.translationX = if (moved) dp(28).toFloat() else 0f }
        button("打开弹窗") {
            AlertDialog.Builder(this).setTitle("测试弹窗").setMessage("旧高亮应该立即清除。")
                .setPositiveButton("关闭", null).show()
        }
        text("以下输入区域应被看懂跳过")
        content.addView(EditText(this).apply {
            hint = "合成输入框"; setText("EDITABLE_SECRET_TEST"); inputType = InputType.TYPE_CLASS_TEXT
        })
        content.addView(EditText(this).apply {
            hint = "合成密码框"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("PASSWORD_SECRET_TEST")
        })
        text("SENSITIVE_STATIC_TEST").apply {
            if (Build.VERSION.SDK_INT >= 34) setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        repeat(18) { text("滚动测试标签 ${it + 1}").setPadding(0, dp(16), 0, dp(16)) }
        setContentView(scroll)
        content.requestFocus()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
