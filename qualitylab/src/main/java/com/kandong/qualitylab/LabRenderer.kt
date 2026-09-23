package com.kandong.qualitylab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File

internal object LabRenderer {
    const val WIDTH = 320
    const val HEIGHT = 160
    val names = listOf("A · 当前平滑", "B · 精细平滑", "C · 平滑 + 轻度锐化")

    fun sample(): Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
        density = Bitmap.DENSITY_NONE
        val canvas = Canvas(this)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(value: String, x: Float, y: Float, size: Float, color: Int) {
            paint.textSize = size
            paint.color = color
            canvas.drawText(value, x, y, paint)
        }
        text("看清每一个字，不改变原来排版", 8f, 22f, 17f, Color.rgb(30, 35, 42))
        text("请核对：未 / 末   日 / 目   己 / 已", 8f, 47f, 14f, Color.BLACK)
        text("订单 1028.50 元 · 时间 18:36", 8f, 68f, 14f, Color.rgb(64, 64, 64))
        text("浅灰小字：查看订单详情与退款说明", 8f, 88f, 12f, Color.rgb(128, 128, 128))
        text("蓝色链接：预约详情 / 返回首页", 8f, 108f, 13f, Color.rgb(23, 91, 177))
        paint.color = Color.rgb(35, 42, 52)
        canvas.drawRect(0f, 119f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        text("深色背景  8.80  已完成", 8f, 143f, 14f, Color.WHITE)
        paint.color = Color.rgb(220, 90, 65)
        paint.strokeWidth = 1f
        canvas.drawLine(286f, 128f, 312f, 153f, paint)
    }

    fun render(source: Bitmap, pixels: IntArray, scale: Int, variant: Int): Bitmap {
        require(variant in 0..2 && scale in 1..5)
        if (variant == 0) {
            val output = Bitmap.createBitmap(WIDTH * scale, HEIGHT * scale, Bitmap.Config.ARGB_8888)
            output.density = Bitmap.DENSITY_NONE
            Canvas(output).drawBitmap(source, null, Rect(0, 0, output.width, output.height),
                Paint(Paint.FILTER_BITMAP_FLAG))
            return output
        }
        val result = PixelEnhancer.enlarge(pixels, WIDTH, HEIGHT, scale, sharpen = variant == 2)
        return Bitmap.createBitmap(result, WIDTH * scale, HEIGHT * scale, Bitmap.Config.ARGB_8888)
            .apply { density = Bitmap.DENSITY_NONE }
    }

    /** Only fixed synthetic text is exported. This app has no capture or storage permissions. */
    fun exportComparison(folder: File, source: Bitmap, images: List<Bitmap>, scale: Int) {
        val width = images.first().width
        val rowHeight = images.first().height + 48
        val sheet = Bitmap.createBitmap(width, rowHeight * 3, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f }
            images.forEachIndexed { index, bitmap ->
                val y = index * rowHeight
                canvas.drawText("${names[index]}  ${scale}×", 12f, y + 32f, paint)
                canvas.drawBitmap(bitmap, 0f, (y + 48).toFloat(), null)
            }
            File(folder, "comparison-${scale}x.png").outputStream().use {
                check(sheet.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(folder, "synthetic-source.png").outputStream().use {
                check(source.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            sheet.recycle()
        }
    }
}
