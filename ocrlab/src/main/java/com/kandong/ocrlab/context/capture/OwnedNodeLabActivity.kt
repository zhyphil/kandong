package com.kandong.ocrlab.context.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.View
import android.view.WindowInsets
import android.widget.*

/** Fixed, owned, synthetic pages only. No Intent inputs, service, projection, OCR or translation. */
@SuppressLint("SetTextI18n")
class OwnedNodeLabActivity : Activity() {
    internal val fixtureViews = linkedMapOf<Int, View>()
    internal lateinit var cover: View
    internal lateinit var scroll: ScrollView
    internal var result: LocalCaptureInspection? = null; private set
    internal var observations = emptyList<OwnedNodeObservation>(); private set
    internal var lastReads = emptyList<Int>(); private set
    internal var obtained = 0; private set
    internal var released = 0; private set
    internal var captureCount = 0; private set
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val session = SystemClock.elapsedRealtimeNanos()
    private var page = 0
    private var revision = 0L
    private var snapshot = 0L
    private var pending: CaptureVersion? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }; insets
            }
        }
        outer.addView(label("本地节点实验 · 仅随包测试页", 20f))
        val buttons = LinearLayout(this)
        fun control(text: String, tagValue: String, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                this.text = text; tag = tagValue; minHeight = dp(48); textSize = 14f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        control("读取本页", "owned-read") { capture() }
        control("换页", "owned-next") { page = (page + 1) % 4; populate() }
        control("改价", "owned-update") { updateFixturePrice() }
        control("遮挡", "owned-cover") {
            clear("遮挡已改变，请重新读取")
            cover.visibility = if (cover.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        control("清除", "owned-clear") { clear("已清除；不会自动读取") }
        outer.addView(buttons)
        status = label("点击后才读取当前测试页，不进行翻译", 14f).apply { tag = "owned-status" }
        outer.addView(status, LinearLayout.LayoutParams(-1, dp(50)))
        val stage = FrameLayout(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        scroll = ScrollView(this).apply { tag = "owned-scroll"; addView(content); isFillViewport = false }
        stage.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        cover = label("遮挡测试面板", 22f).apply {
            setBackgroundColor(Color.rgb(223, 235, 229)); visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        stage.addView(cover, FrameLayout.LayoutParams(-1, dp(95)))
        outer.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        preview = label("读取结果会显示在这里；未知内容保留缺口。", 16f).apply { tag = "owned-preview" }
        outer.addView(ScrollView(this).apply { addView(preview) }, LinearLayout.LayoutParams(-1, dp(135)))
        setContentView(outer); outer.requestApplyInsets()
        scroll.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int -> clear("页面已滚动，请重新读取") }
        content.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) clear("页面布局已更新，请重新读取")
        }
        populate()
    }

    private fun populate() {
        clear("页面已切换，请点击读取")
        fixtureViews.clear(); content.removeAllViews(); cover.visibility = View.GONE
        val strings = when (page) {
            0 -> listOf("Train tickets", "Book", "£18.75 per ticket", "No refund after departure.", "Help", "Public information", "Long conditions apply to every ticket and must remain visible in full.")
            1 -> listOf("Billets de train", "Réserver", "37,80 € par billet", "Non remboursable après le départ.", "Aide", "Informations publiques", "Les conditions complètes doivent rester visibles pour chaque billet réservé.")
            2 -> listOf("列车票", "预订", "每张票37.80元", "出发后不可退款。", "帮助", "公开信息", "每张车票的完整条款都需要保持可见，不能只保留显示出来的一部分。")
            else -> listOf("列車票", "預訂", "每張票37.80元", "出發後不可退款。", "幫助", "公開資訊", "每張車票的完整條款都需要保持可見，不能只保留顯示出來的一部分。")
        }
        fun register(id: Int, view: View, height: Int = 55, parent: LinearLayout = content) {
            view.tag = "owned-node-$id"; view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_NO)
            fixtureViews[id] = view
            parent.addView(view, LinearLayout.LayoutParams(-1, dp(height)))
            view.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (l != oldL || t != oldT || r != oldR || b != oldB) clear("节点位置已更新，请重新读取")
            }
            if (view is TextView) view.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { clear("页面文字已更新，请重新读取") }
            })
        }
        register(1, label(strings[0], 22f))
        register(2, Button(this).apply { text = strings[1]; isAllCaps = false })
        register(3, label(strings[2], 18f))
        register(4, label(strings[3], 18f), 70)
        register(5, ImageView(this).apply { contentDescription = strings[4]; setImageDrawable(ColorDrawable(Color.rgb(220, 230, 225))) })
        register(6, EditText(this).apply { setText("123456"); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isFocusable = false })
        register(7, EditText(this).apply { setText(strings[5]); isFocusable = false })
        register(8, label(strings[5], 18f))
        if (Build.VERSION.SDK_INT >= 34) fixtureViews.getValue(8).setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; contentDescription = "SYNTHETIC-AGGREGATE-123456" }
        register(9, group, 60)
        register(10, label(strings[5], 18f), 55, group)
        register(11, label(strings[6], 18f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, 55)
        register(12, label(strings[3], 18f), 75)
        scroll.scrollTo(0, 0)
    }

    private fun capture() {
        if (!resumed || pending != null || !hasWindowFocus()) return
        clear("等待读取本页…")
        val ticket = CaptureVersion(session, ++snapshot, page.toLong(), revision, 1, window.decorView.display?.displayId ?: 0)
        pending = ticket
        handler.postDelayed({
            if (!current(ticket)) return@postDelayed
            captureCount++
            try {
                var expiresAt = 0L
                OwnedNodeSnapshot.obtain(this, ticket).use { frame ->
                    expiresAt = frame.metadata.capturedAtMillis + frame.metadata.ttlMillis
                    observations = frame.observations
                    result = LocalCapturePrivacy.inspect(frame.metadata,
                        { CaptureCheckpoint(ticket, current(ticket), SystemClock.elapsedRealtime()) }, frame::readLabel)
                    lastReads = frame.reads.toList()
                    obtained = frame.obtainedCount
                    frame.close(); released = frame.releasedCount
                }
                pending = null
                val output = result ?: return@postDelayed
                status.text = "已检查 ${observations.size} 个节点；可用 ${output.blocks.size}，缺口 ${output.gaps.size}。覆盖未确认。"
                preview.text = if (output.blocks.isEmpty()) "目前没有证据充分的可用文字。旧版 Android 的敏感标记可能不可用。" else output.blocks.joinToString("\n") { it.text }
                handler.postDelayed({ clear("本次结果已过期，请重新读取") }, (expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            } catch (_: RuntimeException) { clear("本次节点无法核对，请重新读取") }
        }, 150)
    }

    private fun current(ticket: CaptureVersion) = resumed && hasWindowFocus() && pending == ticket &&
        page.toLong() == ticket.page && revision == ticket.revision

    internal fun updateFixturePrice() {
        (fixtureViews.getValue(3) as TextView).text = when (page) { 0 -> "£19.75 per ticket"; 1 -> "38,80 € par billet"; 2 -> "每张票38.80元"; else -> "每張票38.80元" }
    }

    private fun clear(message: String) {
        revision++; pending = null; result = null; observations = emptyList(); lastReads = emptyList()
        handler.removeCallbacksAndMessages(null)
        if (::status.isInitialized) status.text = message
        if (::preview.isInitialized) preview.text = "尚无本次读取结果。"
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; clear("已暂停；回来后需重新读取"); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (!hasFocus) clear("窗口状态已改变，请重新读取") }
    override fun onDestroy() { clear("已结束"); super.onDestroy() }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(25, 40, 34)); setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
