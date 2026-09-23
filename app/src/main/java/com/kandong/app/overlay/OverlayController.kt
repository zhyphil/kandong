package com.kandong.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.kandong.app.domain.Box

internal class OverlayController(
    private val service: AccessibilityService,
    private val capture: () -> Unit,
    private val previous: () -> Unit,
    private val next: () -> Unit,
    private val stop: () -> Unit,
    private val moved: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var message: TextView? = null
    private var previousButton: Button? = null
    private var nextButton: Button? = null
    private var highlight: HighlightView? = null
    private var atTop = true
    private var panelParams: WindowManager.LayoutParams? = null
    private val retiredIds = ArrayDeque<Int>()
    val hasHighlight: Boolean get() = highlight?.isAttachedToWindow == true

    fun ownWindowIds(): Set<Int> = listOfNotNull(panel, highlight)
        .map(::ownId).filter { it >= 0 }.toSet()

    fun isOwnEvent(windowId: Int, packageName: String?, removalOnly: Boolean): Boolean = windowId >= 0 &&
        (windowId in ownWindowIds() || (removalOnly && windowId in retiredIds)) &&
        (packageName == null || packageName == service.packageName)

    fun panelBounds(): Box? {
        val view = panel ?: return null
        if (!view.isLaidOut) return null
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        return Box(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }

    fun showPanel() {
        if (panel != null) return
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 250, 246)); cornerRadius = dp(14).toFloat()
                setStroke(dp(2), Color.rgb(7, 94, 84))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        message = TextView(service).apply {
            textSize = 18f
            setTextColor(Color.BLACK)
            setLines(4)
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "切换到其他应用后，点“读取页面”。"
        }.also { root.addView(it) }
        fun row(): LinearLayout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
        }.also { root.addView(it) }
        fun button(row: LinearLayout, label: String, action: () -> Unit): Button = Button(service).apply {
            text = label; textSize = 16f; minHeight = dp(48)
            setTextColor(Color.rgb(7, 70, 63))
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f)) }
        val first = row()
        button(first, "读取页面", capture)
        button(first, "移到下方") {
            atTop = !atTop
            hideHighlight()
            moved()
            (first.getChildAt(1) as Button).text = if (atTop) "移到下方" else "移到上方"
            panelParams?.let {
                it.gravity = Gravity.CENTER_HORIZONTAL or if (atTop) Gravity.TOP else Gravity.BOTTOM
                wm.updateViewLayout(root, it)
            }
        }
        val second = row()
        previousButton = button(second, "上一项", previous).apply { isEnabled = false }
        nextButton = button(second, "下一项", next).apply { isEnabled = false }
        button(second, "停止", stop)
        val width = minOf(dp(360), wm.currentWindowMetrics.bounds.width() - dp(16))
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            title = "看懂控制面板"
        }
        panel = root
        panelParams = params
        wm.addView(root, params)
    }

    fun update(text: String, canPrevious: Boolean = false, canNext: Boolean = false) {
        message?.text = text
        previousButton?.isEnabled = canPrevious
        nextButton?.isEnabled = canNext
    }

    /** The caller validates identity immediately before attach and again at layout. */
    fun showHighlight(bounds: Box, mayDraw: () -> Boolean, validateAfterLayout: () -> Boolean) {
        hideHighlight()
        val view = HighlightView(service).apply { target = bounds; this.mayDraw = mayDraw }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { title = "看懂高亮边框" }
        highlight = view
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this)
                if (highlight !== view) return
                if (!validateAfterLayout()) hideHighlight() else view.invalidate()
            }
        })
        wm.addView(view, params)
    }

    fun hideHighlight() {
        highlight?.let { view ->
            view.target = null
            view.mayDraw = { false }
            rememberRemoval(view)
            if (view.isAttachedToWindow) wm.removeViewImmediate(view)
        }
        highlight = null
    }

    fun close() {
        hideHighlight()
        panel?.let { rememberRemoval(it); if (it.isAttachedToWindow) wm.removeViewImmediate(it) }
        message?.text = ""
        panel = null; message = null; panelParams = null
        previousButton = null; nextButton = null; atTop = true
    }
    private fun rememberRemoval(view: View) {
        val id = ownId(view)
        if (id >= 0) {
            retiredIds.addLast(id)
            if (retiredIds.size > 32) retiredIds.removeFirst()
        }
    }
    private fun ownId(view: View): Int =
        if (view.isAttachedToWindow) view.createAccessibilityNodeInfo().windowId else -1
    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}
