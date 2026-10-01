package com.kandong.compat

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/** Source-pixel text layout, shared by the live mirror and native rendering tests. */
internal class SnapshotTextLayout(text: String, val width: Int, val height: Int) {
    private val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color=CompatUi.ink
        textSize=(height*.85f).coerceIn(12f,48f)
    }
    private val fill=Paint().apply { color=Color.WHITE }
    val layout: StaticLayout
    val abbreviated: Boolean
    val drawable: Boolean
    init {
        require(width>0 && height>0)
        fun build(lines: Int=Int.MAX_VALUE)=StaticLayout.Builder.obtain(text,0,text.length,paint,width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(width).build()
        var full=build()
        // Fit one line's actual font metrics, never shrink the whole paragraph to tiny text.
        while(full.getLineBottom(0)>height && paint.textSize>12f) {
            paint.textSize=(paint.textSize-1f).coerceAtLeast(12f)
            full=build()
        }
        val lines=(0 until full.lineCount).count { full.getLineBottom(it)<=height }
        drawable=lines>0
        abbreviated=lines<full.lineCount
        layout=if(drawable && abbreviated) build(lines) else full
    }
    fun draw(canvas: Canvas) {
        drawBackground(canvas); drawText(canvas)
    }
    fun drawBackground(canvas: Canvas) {
        if(!drawable) return
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),fill)
    }
    fun drawText(canvas: Canvas) {
        // Tiny source boxes retain original pixels; the complete text is available in details.
        if(!drawable) return
        canvas.save()
        canvas.clipRect(0,0,width,height)
        layout.draw(canvas)
        canvas.restore()
    }
}
