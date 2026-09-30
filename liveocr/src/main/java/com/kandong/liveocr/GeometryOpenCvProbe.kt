package com.kandong.liveocr

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc

internal class GeometryImage(val width: Int, val height: Int, val bgr: ByteArray)

/** Curated DEFAULT crop path only; no diagnostic routes, fixtures, reports or reference pixels. */
internal class GeometryOpenCvProbe(private val cleanup: GeometryCleanup) {
    class CropResult(val width: Int, val height: Int, val rotated: Boolean, val bgr: ByteArray) : AutoCloseable {
        override fun close() { bgr.fill(0) }
    }

    fun crop(source: GeometryImage, row: GeometryProbeContract.Row, ready: () -> Unit): CropResult {
        val count = GeometryProbeContract.pixels(source.width, source.height)
        require(source.bgr.size.toLong() == count.toLong() * 3)
        val plan = GeometryProbeContract.cropPlan(source.width, source.height, row.quad)
        require(row.plan == plan && row.originalIndex in 0 until 1000 && row.readingOrder in 0 until 1000 &&
            row.detectorScore.isFinite() && row.detectorScore in 0.0..1.0)
        GeometryProbeContract.homography(row.quad, plan)
        GeometryProbeContract.inverse(row.referenceMatrix.toDoubleArray())
        var pixels: ByteArray? = null
        try {
            return OwnedMats(cleanup).use { mats ->
                ready()
                val q = GeometryProbeContract.floatQuad(row.quad)
                val points = mats.own("sourceQuad", MatOfPoint2f(*q.map { Point(it.x, it.y) }.toTypedArray()))
                val target = mats.own("targetQuad", MatOfPoint2f(*GeometryProbeContract.target(plan)
                    .map { Point(it.x, it.y) }.toTypedArray()))
                val matrix = mats.own("homography", Geometry.getPerspectiveTransform(points, target))
                ready()
                check(matrix.rows() == 3 && matrix.cols() == 3 && matrix.type() == CvType.CV_64FC1)
                val values = DoubleArray(9)
                try {
                    check(matrix.get(0, 0, values) == 9 * 8)
                    val normalized = GeometryProbeContract.normalize(values)
                    try {
                        val inverse = GeometryProbeContract.inverse(normalized)
                        try {
                            q.forEach { GeometryProbeContract.project(normalized, it) }
                            GeometryProbeContract.target(plan).forEach { GeometryProbeContract.project(inverse, it) }
                        } finally { inverse.fill(0.0) }
                    } finally { normalized.fill(0.0) }
                } finally { values.fill(0.0) }
                val input = mats.own("cropSource", Mat(source.height, source.width, CvType.CV_8UC3))
                check(input.put(0, 0, source.bgr) == source.bgr.size)
                val destination = mats.own("warpDestination", Mat())
                ready()
                Imgproc.warpPerspective(input, destination, matrix, Size(plan.width.toDouble(), plan.height.toDouble()),
                    Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                ready()
                check(destination.rows() == plan.height && destination.cols() == plan.width && destination.type() == CvType.CV_8UC3)
                val result = if (plan.rotate) {
                    val rotated = mats.own("rotationDestination", Mat())
                    Core.rotate(destination, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                    ready()
                    rotated
                } else destination
                check(result.rows() == plan.outputHeight && result.cols() == plan.outputWidth && result.type() == CvType.CV_8UC3)
                val raw = ByteArray(GeometryProbeContract.pixels(plan.outputWidth, plan.outputHeight) * 3)
                pixels = raw
                check(result.get(0, 0, raw) == raw.size)
                ready()
                CropResult(result.cols(), result.rows(), plan.rotate, raw)
            }
        } catch (e: Throwable) { pixels?.fill(0); throw e }
    }
}
