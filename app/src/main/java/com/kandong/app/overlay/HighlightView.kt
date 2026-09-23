package com.kandong.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.kandong.app.domain.Box

internal class HighlightView(context: Context) : View(context) {
    var target: Box? = null
        set(value) { field = value; invalidate() }
    var mayDraw: () -> Boolean = { false }
    private val location = IntArray(2)
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.STROKE
    }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!mayDraw()) return
        val bounds = target ?: return
        getLocationOnScreen(location)
        val local = bounds.toOverlay(location[0], location[1], width, height) ?: return
        val stroke = minOf(6f * resources.displayMetrics.density, minOf(local.width, local.height) / 3f)
        if (stroke <= 0f) return
        val inset = stroke / 2f
        outer.strokeWidth = stroke
        inner.strokeWidth = stroke / 2f
        canvas.drawRect(local.left + inset, local.top + inset, local.right - inset, local.bottom - inset, outer)
        canvas.drawRect(local.left + inset, local.top + inset, local.right - inset, local.bottom - inset, inner)
    }
}
