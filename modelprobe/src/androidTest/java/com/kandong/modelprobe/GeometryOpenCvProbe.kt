package com.kandong.modelprobe

import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.geometry.Geometry

/** Counts completed release() calls, not finalizer-owned native-header deletion. */
internal class GeometryCleanup {
    val acquired = linkedMapOf<String, Int>()
    val attempts = linkedMapOf<String, Int>()
    val released = linkedMapOf<String, Int>()
    var bitmapOpened = 0; var bitmapRecycleAttempts = 0; var bitmapRecycled = 0
    var findContoursCalls = 0
    val matOpened get() = acquired.values.sum()
    val balanced get() = acquired == attempts && acquired == released && bitmapOpened == bitmapRecycleAttempts && bitmapOpened == bitmapRecycled
    fun json() = JSONObject().put("matAcquired", JSONObject(acquired.toMap())).put("matReleaseAttempts", JSONObject(attempts.toMap()))
        .put("matReleased", JSONObject(released.toMap())).put("matOpened", matOpened).put("findContoursCalls", findContoursCalls)
        .put("bitmapOpened", bitmapOpened).put("bitmapRecycleAttempts", bitmapRecycleAttempts).put("bitmapRecycled", bitmapRecycled)
        .put("balanced", balanced).put("semantics", "release frees Mat buffers; native headers remain finalizer-owned")
}

internal class GeometryOpenCvProbe(private val cleanup: GeometryCleanup, private val route: Route = Route.DEFAULT) {
    // Diagnostic routes are selected once for the whole frozen corpus, never per image.
    enum class Route { DEFAULT, UNOPTIMIZED, FOUR_CHANNEL, EXPLICIT_INVERSE, REMAP_FLOAT }
    enum class Stage { CONTOURS, MATRIX, WARP, ROTATE }
    data class MaskResult(val status: GeometryProbeContract.Status, val mask: ByteArray, val dilated: ByteArray?,
        val rowRunBound: Long, val contourCount: Int?, val candidateLimit: Int = GeometryProbeContract.CANDIDATE_LIMIT)
    data class CropResult(val row: GeometryProbeContract.Row, val width: Int, val height: Int, val rotated: Boolean,
        val bgr: ByteArray, val sourceToPreRotation: DoubleArray, val preRotationToSource: DoubleArray)

    private inner class Mats : AutoCloseable {
        private val owned = mutableListOf<Pair<String, Mat>>()
        private val identities = java.util.IdentityHashMap<Mat, Boolean>()
        fun <T : Mat> own(kind: String, mat: T): T {
            if (identities.put(mat, true) == null) {
                owned.add(kind to mat)
                cleanup.acquired[kind] = (cleanup.acquired[kind] ?: 0) + 1
            }
            return mat
        }
        override fun close() {
            var failure: Throwable? = null
            for ((kind, mat) in owned.asReversed()) {
                cleanup.attempts[kind] = (cleanup.attempts[kind] ?: 0) + 1
                try {
                    mat.release(); check(mat.empty())
                    cleanup.released[kind] = (cleanup.released[kind] ?: 0) + 1
                } catch (e: Throwable) { if (failure == null) failure = e else failure.addSuppressed(e) }
            }
            owned.clear()
            identities.clear()
            failure?.let { throw it }
        }
    }

    fun masks(shape: LongArray, probability: FloatArray, after: (Stage) -> Unit = {}): MaskResult {
        val mask = GeometryProbeContract.threshold(shape, probability)
        val width = shape[3].toInt(); val height = shape[2].toInt()
        val inputRuns = GeometryProbeContract.rowRuns(width, height, mask)
        if (inputRuns > GeometryProbeContract.CONTOUR_BUDGET) return MaskResult(
            GeometryProbeContract.Status.INCOMPLETE_CONTOUR_BUDGET, mask, null, inputRuns, null)
        Mats().use { mats ->
            val source = mats.own("maskSource", Mat(height, width, CvType.CV_8UC1))
            check(source.put(0, 0, mask) == mask.size)
            val kernel = mats.own("kernel", Mat(2, 2, CvType.CV_8UC1))
            check(kernel.put(0, 0, byteArrayOf(1, 1, 1, 1)) == 4)
            val dilated = mats.own("dilated", Mat())
            // Default anchor (-1,-1), default morphology border, exactly one iteration.
            Imgproc.dilate(source, dilated, kernel)
            check(dilated.type() == CvType.CV_8UC1 && dilated.rows() == height && dilated.cols() == width)
            val raw = ByteArray(mask.size); check(dilated.get(0, 0, raw) == raw.size)
            val runs = GeometryProbeContract.rowRuns(width, height, raw)
            if (runs > GeometryProbeContract.CONTOUR_BUDGET) return MaskResult(
                GeometryProbeContract.Status.INCOMPLETE_CONTOUR_BUDGET, mask, raw, runs, null)
            val contourSource = mats.own("contourSource", Mat())
            dilated.convertTo(contourSource, CvType.CV_8UC1, 255.0)
            val hierarchy = mats.own("hierarchy", Mat())
            val contours = mutableListOf<MatOfPoint>()
            try {
                cleanup.findContoursCalls++
                Imgproc.findContours(contourSource, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            } finally {
                // Include every wrapper delivered even if findContours exits exceptionally.
                contours.forEach { mats.own("contour", it) }
            }
            after(Stage.CONTOURS)
            return MaskResult(GeometryProbeContract.contourStatus(contours.size), mask, raw, runs, contours.size)
        }
    }

    fun crop(source: GeometryImage, row: GeometryProbeContract.Row, after: (Stage) -> Unit = {}): CropResult {
        val count = GeometryProbeContract.pixels(source.width, source.height)
        require(source.bgr.size.toLong() == count.toLong() * 3)
        ProbeInputs.verifyHash(source.bgr, source.sha) // Actual consumed BGR bytes, before all Mat allocations.
        val plan = GeometryProbeContract.cropPlan(source.width, source.height, row.quad)
        require(row.plan == plan && row.originalIndex in 0 until 1000 && row.readingOrder in 0 until 1000 &&
            row.detectorScore.isFinite() && row.detectorScore in 0.0..1.0)
        GeometryProbeContract.homography(row.quad, plan)
        GeometryProbeContract.inverse(row.referenceMatrix.toDoubleArray())
        Mats().use { mats ->
            val q = GeometryProbeContract.floatQuad(row.quad)
            val points = mats.own("sourceQuad", MatOfPoint2f(*q.map { Point(it.x, it.y) }.toTypedArray()))
            val target = mats.own("targetQuad", MatOfPoint2f(*GeometryProbeContract.target(plan).map { Point(it.x, it.y) }.toTypedArray()))
            val matrix = mats.own("homography", Geometry.getPerspectiveTransform(points, target))
            after(Stage.MATRIX)
            check(matrix.rows() == 3 && matrix.cols() == 3 && matrix.type() == CvType.CV_64FC1)
            val values = DoubleArray(9); check(matrix.get(0, 0, values) == 9 * 8)
            val normalized = GeometryProbeContract.normalize(values); val inverse = GeometryProbeContract.inverse(normalized)
            q.forEach { GeometryProbeContract.project(normalized, it) }
            GeometryProbeContract.target(plan).forEach { GeometryProbeContract.project(inverse, it) }
            val input = mats.own("cropSource", Mat(source.height, source.width, CvType.CV_8UC3))
            check(input.put(0, 0, source.bgr) == source.bgr.size)
            val warpInput = if (route == Route.FOUR_CHANNEL) {
                val bgra = mats.own("diagnosticBgra", Mat())
                Imgproc.cvtColor(input, bgra, Imgproc.COLOR_BGR2BGRA)
                check(bgra.type() == CvType.CV_8UC4); bgra
            } else input
            val destination = mats.own("warpDestination", Mat())
            when (route) {
                Route.EXPLICIT_INVERSE -> {
                    val inverseMat = mats.own("diagnosticInverse", Mat(3, 3, CvType.CV_64FC1))
                    // The double[] put overload returns element count; get returns bytes.
                    check(inverseMat.put(0, 0, *inverse) == 9)
                    Imgproc.warpPerspective(warpInput, destination, inverseMat, Size(plan.width.toDouble(), plan.height.toDouble()),
                        Imgproc.INTER_CUBIC or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                }
                Route.REMAP_FLOAT -> {
                    val size = GeometryProbeContract.pixels(plan.width, plan.height)
                    val xs = FloatArray(size); val ys = FloatArray(size)
                    for (i in 0 until size) {
                        val x = (i % plan.width).toDouble(); val y = (i / plan.width).toDouble()
                        val a = inverse[6] * x; val b = inverse[7] * y; val c = inverse[8]
                        val denominator = a + b + c
                        require(denominator.isFinite() && kotlin.math.abs(denominator) > GeometryProbeContract.EPS *
                            (kotlin.math.abs(a) + kotlin.math.abs(b) + kotlin.math.abs(c)))
                        xs[i] = ((inverse[0] * x + inverse[1] * y + inverse[2]) / denominator).toFloat()
                        ys[i] = ((inverse[3] * x + inverse[4] * y + inverse[5]) / denominator).toFloat()
                        require(xs[i].isFinite() && ys[i].isFinite())
                    }
                    val mx = mats.own("diagnosticMapX", Mat(plan.height, plan.width, CvType.CV_32FC1))
                    val my = mats.own("diagnosticMapY", Mat(plan.height, plan.width, CvType.CV_32FC1))
                    check(mx.put(0, 0, xs) == size * 4 && my.put(0, 0, ys) == size * 4)
                    Imgproc.remap(warpInput, destination, mx, my, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                }
                else -> Imgproc.warpPerspective(warpInput, destination, matrix, Size(plan.width.toDouble(), plan.height.toDouble()),
                    Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            }
            after(Stage.WARP)
            val channels = if (route == Route.FOUR_CHANNEL) 4 else 3
            val expectedType = if (channels == 4) CvType.CV_8UC4 else CvType.CV_8UC3
            check(destination.rows() == plan.height && destination.cols() == plan.width && destination.type() == expectedType)
            val result = if (plan.rotate) {
                val rotated = mats.own("rotationDestination", Mat())
                Core.rotate(destination, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                after(Stage.ROTATE); rotated
            } else destination
            check(result.rows() == plan.outputHeight && result.cols() == plan.outputWidth && result.type() == expectedType)
            val pixels = GeometryProbeContract.pixels(plan.outputWidth, plan.outputHeight)
            val raw = ByteArray(pixels * channels); check(result.get(0, 0, raw) == raw.size)
            val bgr = if (channels == 3) raw else ByteArray(pixels * 3) { raw[(it / 3) * 4 + it % 3] }
            return CropResult(row, result.cols(), result.rows(), plan.rotate, bgr, normalized, inverse)
        }
    }
}
