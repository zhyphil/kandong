package com.kandong.liveocr

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Curated single-row INTER_LINEAR path from CropRecognitionPipeline.resize. */
internal class CropRecognitionPipeline(private val cleanup: GeometryCleanup) {
    fun resize(crop: GeometryOpenCvProbe.CropResult, width: Int, ready: () -> Unit): RecognitionPacking.Resized {
        require(crop.width in 1..4096 && crop.height in 1..2048 &&
            crop.width.toLong() * crop.height * 3 == crop.bgr.size.toLong())
        require(crop.width.toLong() * crop.height <= GeometryProbeContract.MAX_PIXELS && width in 1..RecognitionPacking.MAX_WIDTH)
        var pixels: IntArray? = null
        try {
            return OwnedMats(cleanup).use { mats ->
                val src = mats.own("recognitionResizeSource", Mat(crop.height, crop.width, CvType.CV_8UC3))
                val dst = mats.own("recognitionResizeDestination", Mat(48, width, CvType.CV_8UC3))
                ready()
                check(src.put(0, 0, crop.bgr) == crop.bgr.size)
                Imgproc.resize(src, dst, Size(width.toDouble(), 48.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
                ready()
                check(dst.type() == CvType.CV_8UC3 && dst.rows() == 48 && dst.cols() == width)
                val bgr = ByteArray(width * 48 * 3)
                try {
                    check(dst.get(0, 0, bgr) == bgr.size)
                    val argb = IntArray(width * 48) { i -> -0x1000000 or (bgr[3 * i].toInt() and 255) or
                        ((bgr[3 * i + 1].toInt() and 255) shl 8) or ((bgr[3 * i + 2].toInt() and 255) shl 16) }
                    pixels = argb
                    require(argb.all { it ushr 24 == 255 })
                    ready()
                    RecognitionPacking.Resized(width, 48, argb)
                } finally { bgr.fill(0) }
            }
        } catch (e: Throwable) { pixels?.fill(0); throw e }
    }
}
