package com.kandong.ocrlab.multilingual

/** Integer center-aligned bilinear candidate; source text never enters this operation. */
internal object SmoothPixels {
    fun resize(pixels: IntArray, width: Int, height: Int, scale: Int): IntArray {
        require(scale == 2 || scale == 3)
        ComparisonPlan.dimensions(width, height)
        ComparisonPlan.dimensions(width * scale, height * scale)
        require(pixels.size == width * height && pixels.all { it ushr 24 == 255 })
        val denominator = 2 * scale
        val divisor = denominator * denominator
        val outputWidth = width * scale
        val output = IntArray(outputWidth * height * scale)
        val channels = intArrayOf(16, 8, 0)
        for (y in 0 until height * scale) {
            val numeratorY = (2 * y + 1 - scale).coerceIn(0, (height - 1) * denominator)
            val y0 = numeratorY / denominator
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val weightY = numeratorY % denominator
            for (x in 0 until outputWidth) {
                val numeratorX = (2 * x + 1 - scale).coerceIn(0, (width - 1) * denominator)
                val x0 = numeratorX / denominator
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val weightX = numeratorX % denominator
                val a = pixels[y0 * width + x0]
                val b = pixels[y0 * width + x1]
                val c = pixels[y1 * width + x0]
                val d = pixels[y1 * width + x1]
                var color = 255 shl 24
                for (shift in channels) {
                    val top = ((a ushr shift) and 255) * (denominator - weightX) +
                        ((b ushr shift) and 255) * weightX
                    val bottom = ((c ushr shift) and 255) * (denominator - weightX) +
                        ((d ushr shift) and 255) * weightX
                    val value = (top * (denominator - weightY) + bottom * weightY + divisor / 2) / divisor
                    color = color or (value shl shift)
                }
                output[y * outputWidth + x] = color
            }
        }
        return output
    }
}
