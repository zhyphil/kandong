package com.kandong.liveocr


/** Curated packing math; consumes resized opaque pixels. */
internal object DetectorPacking {
    const val MAX_SOURCE_DIMENSION = 4096
    const val MAX_RESIZED_DIMENSION = 2048
    const val MAX_PIXELS = 1_048_576
    data class Plan(val width: Int, val height: Int, val effectiveLimit: Int) {
        val inputShape get() = longArrayOf(1, 3, height.toLong(), width.toLong())
        val outputShape get() = longArrayOf(1, 1, height.toLong(), width.toLong())
    }

    fun plan(sourceWidth: Int, sourceHeight: Int): Plan {
        require(sourceWidth in 1..MAX_SOURCE_DIMENSION && sourceHeight in 1..MAX_SOURCE_DIMENSION)
        val longest = maxOf(sourceWidth, sourceHeight)
        val limit = when { longest < 960 -> 960; longest < 1500 -> 1500; else -> 2000 }
        val ratio = if (longest > limit) limit.toDouble() / longest else 1.0
        // Pinned upstream: truncate FIRST, then Python round (ties to even), independently per axis.
        fun axis(value: Int) = (Math.rint((value * ratio).toInt() / 32.0) * 32).toInt()
        val width = axis(sourceWidth); val height = axis(sourceHeight)
        pixels(width, height)
        return Plan(width, height, limit)
    }

    fun pixels(width: Int, height: Int): Int {
        require(width in 1..MAX_RESIZED_DIMENSION && height in 1..MAX_RESIZED_DIMENSION)
        require(width % 32 == 0 && height % 32 == 0)
        val count = width.toLong() * height
        require(count in 1..MAX_PIXELS.toLong()) { "Detector diagnostic pixel budget exceeded" }
        return count.toInt()
    }

    fun elements(shape: LongArray, channels: Int): Int {
        require(channels == 1 || channels == 3)
        require(shape.size == 4 && shape[0] == 1L && shape[1] == channels.toLong())
        require(shape[2] in 1..MAX_RESIZED_DIMENSION.toLong() && shape[3] in 1..MAX_RESIZED_DIMENSION.toLong())
        return pixels(shape[3].toInt(), shape[2].toInt()) * channels
    }

    fun pack(width: Int, height: Int, argb: IntArray): FloatArray {
        val plane = pixels(width, height)
        require(argb.size == plane)
        require(argb.all { it ushr 24 == 255 }) { "Opaque precomposited pixels required" }
        // All dimensions, products, length and alpha are checked before allocating the tensor.
        val result = FloatArray(plane * 3)
        val scale = (1.0 / 255.0).toFloat()
        for (i in argb.indices) for (channel in 0..2) {
            val value = (argb[i] ushr (channel * 8)) and 255 // BGR planes, row-major within each.
            val q: Float = value.toFloat() * scale
            result[channel * plane + i] = ((q.toDouble() - 0.5) / 0.5).toFloat()
        }
        return result
    }
}
