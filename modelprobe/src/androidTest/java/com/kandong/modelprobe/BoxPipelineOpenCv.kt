package com.kandong.modelprobe

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor

/** One genuine native pipeline, with no reference data or per-image backend selection. */
internal class BoxPipelineOpenCv(private val cleanup: GeometryCleanup) {
    enum class Stage { ALLOCATED, DILATED, CONTOURS, MIN_RECT, SCORE, OFFSET, EXPANDED_RECT }
    private class Unsupported(val status: BoxPipelineContract.Status, val code: String) : RuntimeException()
    private inner class Mats : AutoCloseable {
        private val owned = mutableListOf<Pair<String, Mat>>()
        private val identities = java.util.IdentityHashMap<Mat, Boolean>()
        fun <T : Mat> own(kind: String, value: T): T {
            if (identities.put(value, true) == null) {
                owned.add(kind to value); cleanup.acquired[kind] = (cleanup.acquired[kind] ?: 0) + 1
            }
            return value
        }
        override fun close() {
            var error: Throwable? = null
            owned.asReversed().forEach { (kind, mat) ->
                cleanup.attempts[kind] = (cleanup.attempts[kind] ?: 0) + 1
                try {
                    mat.release(); check(mat.empty()); cleanup.released[kind] = (cleanup.released[kind] ?: 0) + 1
                } catch (e: Throwable) { if (error == null) error = e else error!!.addSuppressed(e) }
            }
            owned.clear(); identities.clear(); error?.let { throw it }
        }
    }
    private fun mini(points: Array<Point>, mats: Mats): Pair<List<GeometryProbeContract.Point>, Double> {
        val input = mats.own("boxRectInput", MatOfPoint2f(*points))
        val rect = Geometry.minAreaRect(input)
        val output = mats.own("boxRectPoints", Mat())
        Geometry.boxPoints(rect, output) // Native float32 calculation; never RotatedRect.points().
        check(output.rows() == 4 && output.cols() == 2 && output.type() == CvType.CV_32FC1)
        val coordinates = FloatArray(8); check(output.get(0, 0, coordinates) == 32)
        val quad = BoxPipelineContract.miniOrder(List(4) { GeometryProbeContract.Point(
            coordinates[it * 2].toDouble(), coordinates[it * 2 + 1].toDouble()) })
        val side = minOf(rect.size.width, rect.size.height)
        check(side.isFinite() && side >= 0)
        return quad to side
    }
    private fun score(probability: Mat, quad: List<GeometryProbeContract.Point>): Double = Mats().use { mats ->
        val xmin = floor(quad.minOf { it.x }).toInt().coerceIn(0, probability.cols() - 1)
        val xmax = ceil(quad.maxOf { it.x }).toInt().coerceIn(0, probability.cols() - 1)
        val ymin = floor(quad.minOf { it.y }).toInt().coerceIn(0, probability.rows() - 1)
        val ymax = ceil(quad.maxOf { it.y }).toInt().coerceIn(0, probability.rows() - 1)
        val mask = mats.own("boxScoreMask", Mat(ymax - ymin + 1, xmax - xmin + 1, CvType.CV_8UC1, Scalar(0.0)))
        val points = mats.own("boxScorePolygon", MatOfPoint(*quad.map {
            Point((it.x.toFloat() - xmin.toFloat()).toInt().toDouble(),
                (it.y.toFloat() - ymin.toFloat()).toInt().toDouble())
        }.toTypedArray()))
        Imgproc.fillPoly(mask, listOf(points), Scalar(1.0))
        val roi = mats.own("boxScoreRoi", probability.submat(ymin, ymax + 1, xmin, xmax + 1))
        Core.mean(roi, mask).`val`[0].also { check(it.isFinite() && it in 0.0..1.0) }
    }
    fun run(shape: LongArray, values: FloatArray, sourceWidth: Int, sourceHeight: Int,
        limits: BoxPipelineContract.Limits = BoxPipelineContract.Limits(),
        library: PolygonOffsetKernel.Library? = null, after: (Stage) -> Unit = {}): BoxPipelineContract.Result {
        GeometryProbeContract.pixels(sourceWidth, sourceHeight)
        val mask = GeometryProbeContract.threshold(shape, values)
        val width = shape[3].toInt(); val height = shape[2].toInt()
        val sourceRuns = GeometryProbeContract.rowRuns(width, height, mask)
        var dilatedBytes: ByteArray? = null; var dilatedRuns: Long? = null; var count: Int? = null
        val rows = mutableListOf<BoxPipelineContract.Row>(); val boxes = mutableListOf<BoxPipelineContract.Box>()
        fun result(status: BoxPipelineContract.Status, reason: String = "") = BoxPipelineContract.Result(status,
            mask, dilatedBytes, sourceRuns, dilatedRuns, count, PolygonOffsetKernel.immutable(rows),
            if (status == BoxPipelineContract.Status.COMPLETE) BoxPipelineContract.readingOrder(boxes) else emptyList(), reason)
        if (sourceRuns > GeometryProbeContract.CONTOUR_BUDGET)
            return result(BoxPipelineContract.Status.INCOMPLETE_CONTOUR_BUDGET, "SOURCE_RUNS")
        try {
            Mats().use { mats ->
                val source = mats.own("boxMask", Mat(height, width, CvType.CV_8UC1))
                check(source.put(0, 0, mask) == mask.size); after(Stage.ALLOCATED)
                val kernel = mats.own("boxKernel", Mat(2, 2, CvType.CV_8UC1))
                check(kernel.put(0, 0, byteArrayOf(1, 1, 1, 1)) == 4)
                val dilated = mats.own("boxDilated", Mat())
                Imgproc.dilate(source, dilated, kernel); after(Stage.DILATED)
                check(dilated.rows() == height && dilated.cols() == width && dilated.type() == CvType.CV_8UC1)
                val raw = ByteArray(mask.size); check(dilated.get(0, 0, raw) == raw.size)
                dilatedBytes = raw; dilatedRuns = GeometryProbeContract.rowRuns(width, height, raw)
                if (dilatedRuns!! > GeometryProbeContract.CONTOUR_BUDGET)
                    return result(BoxPipelineContract.Status.INCOMPLETE_CONTOUR_BUDGET, "DILATED_RUNS")
                val contourSource = mats.own("boxContourSource", Mat())
                dilated.convertTo(contourSource, CvType.CV_8UC1, 255.0)
                val hierarchy = mats.own("boxHierarchy", Mat())
                val contours = mutableListOf<MatOfPoint>()
                try {
                    cleanup.findContoursCalls++
                    Imgproc.findContours(contourSource, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                } finally { contours.forEach { mats.own("boxContour", it) } }
                count = contours.size; after(Stage.CONTOURS)
                if (contours.size > GeometryProbeContract.CANDIDATE_LIMIT)
                    return result(BoxPipelineContract.Status.INCOMPLETE_CANDIDATE_LIMIT)
                if (!BoxPipelineContract.geometryFits(contours.map { it.total() }, limits))
                    return result(BoxPipelineContract.Status.INCOMPLETE_GEOMETRY_BUDGET)
                val probability = mats.own("boxProbability", Mat(height, width, CvType.CV_32FC1))
                check(probability.put(0, 0, values) == values.size * 4)
                var rawIndex = 0
                contours.forEachIndexed { index, contour ->
                    Mats().use { candidate ->
                        val points = contour.toArray() // all size guards above precede copies
                        val vertices = PolygonOffsetKernel.immutable(points.map {
                            PolygonOffsetKernel.IntPoint(it.x.toLong(), it.y.toLong()) })
                        val (quad, side) = mini(points, candidate)
                        var row = BoxPipelineContract.Row(index, vertices, quad, side, "minimum-side")
                        rows.add(row); after(Stage.MIN_RECT)
                        fun update(next: BoxPipelineContract.Row) { row = next; rows[rows.lastIndex] = next }
                        if (side < 3.0) return@forEachIndexed
                        val score = score(probability, quad)
                        update(row.copy(score = score, disposition = "box-score")); after(Stage.SCORE)
                        if (score < 0.5) return@forEachIndexed
                        val paths = listOf(quad.map { PolygonOffsetKernel.Point(it.x, it.y) })
                        val integers: List<PolygonOffsetKernel.IntPoint>; val distance: Double
                        try {
                            integers = PolygonOffsetKernel.validateInput(paths).single()
                            distance = PolygonOffsetKernel.computedDistance(paths)
                        } catch (_: IllegalArgumentException) {
                            update(row.copy(disposition = "unsupported-unclip"))
                            throw Unsupported(BoxPipelineContract.Status.INCOMPLETE_UNSUPPORTED_GEOMETRY, "UNCLIP_INPUT")
                        }
                        update(row.copy(integerInput = integers, distance = distance, disposition = "unclip"))
                        val offset = if (library == null) PolygonOffsetKernel.offset(paths, distance)
                            else PolygonOffsetKernel.offset(paths, distance, library)
                        after(Stage.OFFSET)
                        if (offset.status != PolygonOffsetKernel.Status.COMPLETE) {
                            val status = when (offset.status) {
                                PolygonOffsetKernel.Status.INVALID_INPUT -> BoxPipelineContract.Status.INCOMPLETE_UNSUPPORTED_GEOMETRY
                                PolygonOffsetKernel.Status.BUDGET_EXCEEDED -> BoxPipelineContract.Status.INCOMPLETE_OFFSET_BUDGET
                                PolygonOffsetKernel.Status.EMPTY_OUTPUT -> BoxPipelineContract.Status.INCOMPLETE_OFFSET_EMPTY
                                else -> BoxPipelineContract.Status.INCOMPLETE_OFFSET_FAILURE
                            }
                            update(row.copy(disposition = "unsupported-unclip")); throw Unsupported(status, offset.reason)
                        }
                        update(row.copy(expanded = offset.paths, disposition = "expanded-minimum-side"))
                        val expandedPoints = offset.paths.flatten().map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
                        val (expandedQuad, expandedSide) = mini(expandedPoints, candidate)
                        update(row.copy(postQuad = expandedQuad, expandedMinimumSide = expandedSide)); after(Stage.EXPANDED_RECT)
                        if (expandedSide < 5.0) return@forEachIndexed
                        val rawBox = PolygonOffsetKernel.immutable(expandedQuad.map { GeometryProbeContract.Point(
                            BoxPipelineContract.mapCoordinate(it.x, width, sourceWidth),
                            BoxPipelineContract.mapCoordinate(it.y, height, sourceHeight)) })
                        val finalQuad = BoxPipelineContract.finalQuad(rawBox, sourceWidth, sourceHeight)
                        update(row.copy(rawBoxIndex = rawIndex++, rawBox = rawBox, disposition = "final-size"))
                        if (!BoxPipelineContract.finalSizeAccepted(finalQuad)) return@forEachIndexed
                        val finalIndex = boxes.size
                        update(row.copy(finalBox = finalQuad, finalBoxIndex = finalIndex, disposition = "accepted"))
                        boxes.add(BoxPipelineContract.Box(index, row.rawBoxIndex!!, finalIndex, finalQuad, score))
                    }
                }
            }
            return result(BoxPipelineContract.Status.COMPLETE)
        } catch (e: Unsupported) { return result(e.status, e.code) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: RuntimeException) { return result(BoxPipelineContract.Status.INCOMPLETE_NATIVE_FAILURE, e.javaClass.simpleName) }
    }
}
