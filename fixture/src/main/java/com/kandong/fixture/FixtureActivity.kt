package com.kandong.fixture

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest

/** Own-process layout evidence. No personal information, network or product dependency. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(column) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        fun label(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.BLACK)
        }.also(column::addView)
        label("看懂 · 放大镜合成测试页", 20f)
        intent.getStringExtra("testSession")?.let { label(it, 10f) }
        val diagnostic = label("布局指纹待生成", 10f).apply {
            maxLines = 1
            layoutParams.height = dp(20)
        }
        val paragraph = label("Correspondance à Bordeaux\n12 minutes\nNon échangeable et non remboursable\nFlight information • Cabin bag included", 14f)
        val grid = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawColor(Color.WHITE)
                val cell = dp(24).toFloat()
                paint.strokeWidth = dp(1).toFloat()
                paint.color = Color.rgb(160, 160, 160)
                var x = 0f
                while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), paint); x += cell }
                var y = 0f
                while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += cell }
                paint.color = Color.rgb(220, 30, 30)
                canvas.drawRect(cell, cell, cell * 2, cell * 2, paint)
                paint.color = Color.rgb(20, 80, 220)
                canvas.drawRect(cell * 4, cell * 2, cell * 5, cell * 3, paint)
                paint.color = Color.BLACK
                paint.textSize = dp(12).toFloat()
                canvas.drawText("A 24dp   B 24dp", cell, cell * 5, paint)
            }
        }.also { column.addView(it, LinearLayout.LayoutParams(-1, dp(144))) }
        var count = 0
        val counter = label("点击次数：0", 18f)
        val button = Button(this).apply {
            text = "测试按钮"; textSize = 18f
            setOnClickListener { count++; counter.text = "点击次数：$count" }
        }.also(column::addView)
        label("放大不应改变原文换行、网格间距或按钮位置。计数只在亲自点击时增加。", 14f)
        repeat(12) { label("Ligne ${it + 1} — Small original text / 原始小字", 14f).setPadding(0, dp(12), 0, dp(12)) }
        setContentView(scroll)
        // Compute from actual local View layout; accessibility screen bounds may be magnified.
        column.viewTreeObserver.addOnGlobalLayoutListener {
            val raw = buildString {
                append(resources.configuration.fontScale).append('|').append(resources.displayMetrics.densityDpi)
                append('|').append(column.width).append('|').append(column.height)
                for (view in listOf(paragraph, grid, button)) {
                    append('|').append(view.left).append(',').append(view.top).append(',').append(view.width).append(',').append(view.height)
                }
                paragraph.layout?.let { layout ->
                    append('|').append(layout.lineCount)
                    repeat(layout.lineCount) { append(',').append(layout.getLineEnd(it)) }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            val value = "KANDONG_LAYOUT:$digest"
            if (diagnostic.text.toString() != value) diagnostic.text = value
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
