package com.kandong.liveocr

/** Curated RGBA -> BGR loop from SingleFrameStripInput; caller retains and wipes the RGBA. */
internal object SingleFrameStripInput {
    fun validate(rgba: ByteArray, width: Int, height: Int): FullPageStripPlanner.Plan {
        requireOcr(width in 32..2048 && height in 32..4096 &&
            width.toLong() * height <= FullPageStripPlanner.MAX_FRAME_PIXELS &&
            width.toLong() * height * 4 == rgba.size.toLong(), OcrFailure.INVALID_INPUT)
        return try { FullPageStripPlanner.plan(width, height) }
        catch (_: IllegalArgumentException) { throw LiveOcrException(OcrFailure.INVALID_INPUT) }
    }

    fun <T> process(rgba: ByteArray, width: Int, height: Int, current: OcrCurrent,
        consume: (FullPageStripPlanner.Strip, ByteArray) -> T): List<T> {
        current.check()
        val plan = validate(rgba, width, height)
        val results = ArrayList<T>()
        for (strip in plan.strips) {
            current.check()
            val bgr = ByteArray(strip.read.width * strip.read.height * 3)
            try {
                var dst = 0
                for (y in strip.read.top until strip.read.bottom) {
                    current.check()
                    for (x in 0 until width) {
                        val src = (y * width + x) * 4
                        requireOcr(rgba[src + 3].toInt() and 255 == 255, OcrFailure.INVALID_INPUT)
                        bgr[dst++] = rgba[src + 2]
                        bgr[dst++] = rgba[src + 1]
                        bgr[dst++] = rgba[src]
                    }
                }
                current.check()
                val result = consume(strip, bgr)
                current.check()
                results += result
            } finally { bgr.fill(0) }
        }
        current.check()
        return results
    }
}
