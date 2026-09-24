package com.kandong.capturelab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Fixed, owned color fixture. No text, imported data, bitmap, or screenshot input. */
internal class FixtureView(context: Context) : View(context) {
    private val paint = Paint()
    var marker = Marker.forPhase(0, Phase.BASELINE)
        set(value) { field = value; invalidate() }
    private var pulse = false
    fun tick() { pulse = !pulse; invalidate() }
    fun screenGeometry(): FixtureGeometry {
        val xy = IntArray(2); getLocationOnScreen(xy)
        return FixtureGeometry.local(width, height).shift(xy[0], xy[1])
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xffeeeeee.toInt())
        val g = FixtureGeometry.local(width, height)
        draw(canvas, g.first, marker.first); draw(canvas, g.second, marker.second)
        draw(canvas, g.patch, Marker.BASE); draw(canvas, g.witness, 0xffdddddd.toInt())
        paint.color = if (pulse) 0xff777777.toInt() else 0xffaaaaaa.toInt()
        canvas.drawRect(0f, height - 5f, width.toFloat(), height.toFloat(), paint)
    }
    private fun draw(canvas: Canvas, box: Box, color: Int) {
        paint.color = color
        canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
    }
}

/** Independent nonfocusable overlay witness stays capturable during the secure Activity phase. */
internal class WitnessView(context: Context, private val marker: Marker) : View(context) {
    private val paint = Paint()
    private var pulse = false
    fun tick() { pulse = !pulse; invalidate() }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xffcccccc.toInt())
        val cells = FixtureGeometry.witnessCells(width, height)
        for ((box, color) in listOf(cells.first to marker.first, cells.second to marker.second)) {
            paint.color = color
            canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
        }
        paint.color = if (pulse) 0xff888888.toInt() else 0xffbbbbbb.toInt()
        canvas.drawRect(0f, height - 4f, width.toFloat(), height.toFloat(), paint)
    }
}
