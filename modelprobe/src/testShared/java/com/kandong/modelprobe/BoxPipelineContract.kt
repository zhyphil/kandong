package com.kandong.modelprobe

import kotlin.math.sqrt

/** Test-only DB contract. Expected traces never enter these functions. */
internal object BoxPipelineContract {
    const val MAX_CONTOUR_VERTICES = 32768
    const val MAX_SINGLE_CONTOUR_VERTICES = 8192
    const val CONFIG = "DB-v1:threshold>float32(.3);dilate2x2-default;LIST/SIMPLE;side3;score.5;ROUND1.6;side5;f32-div-mul-rint;inclusive-then-W-1;norm-int>3;paired-adjacent-dy10"
    enum class Status { COMPLETE, INCOMPLETE_CONTOUR_BUDGET, INCOMPLETE_CANDIDATE_LIMIT,
        INCOMPLETE_GEOMETRY_BUDGET, INCOMPLETE_UNSUPPORTED_GEOMETRY, INCOMPLETE_OFFSET_BUDGET,
        INCOMPLETE_OFFSET_EMPTY, INCOMPLETE_OFFSET_FAILURE, INCOMPLETE_NATIVE_FAILURE }
    data class Row(val contourIndex: Int, val contour: List<PolygonOffsetKernel.IntPoint>,
        val preQuad: List<GeometryProbeContract.Point>, val minimumSide: Double,
        val disposition: String, val score: Double? = null, val distance: Double? = null,
        val integerInput: List<PolygonOffsetKernel.IntPoint>? = null,
        val expanded: List<List<PolygonOffsetKernel.IntPoint>>? = null,
        val postQuad: List<GeometryProbeContract.Point>? = null, val expandedMinimumSide: Double? = null,
        val rawBoxIndex: Int? = null, val rawBox: List<GeometryProbeContract.Point>? = null,
        val finalBoxIndex: Int? = null, val finalBox: List<GeometryProbeContract.Point>? = null)
    data class Box(val contourIndex: Int, val rawBoxIndex: Int, val finalBoxIndex: Int,
        val quad: List<GeometryProbeContract.Point>, val score: Double)
    data class Result(val status: Status, val mask: ByteArray, val dilated: ByteArray?,
        val sourceRuns: Long, val dilatedRuns: Long?, val contourCount: Int?, val rows: List<Row>,
        val boxes: List<Box>, val reason: String = "") {
        init { require(status == Status.COMPLETE || boxes.isEmpty()) { "PARTIAL_BOXES_NOT_USABLE" } }
        val processedCandidates get() = rows.size
    }
    data class Limits(val totalVertices: Int = MAX_CONTOUR_VERTICES,
        val perContour: Int = MAX_SINGLE_CONTOUR_VERTICES) {
        init { require(totalVertices in 1..MAX_CONTOUR_VERTICES && perContour in 1..MAX_SINGLE_CONTOUR_VERTICES) }
    }
    fun geometryFits(sizes: List<Long>, limits: Limits = Limits()): Boolean {
        // This runs on Mat.total(), before any toArray / coordinate copy.
        if (sizes.size > GeometryProbeContract.CANDIDATE_LIMIT) return false
        var total = 0L
        for (size in sizes) {
            if (size !in 1..limits.perContour.toLong()) return false
            total += size
            if (total > limits.totalVertices) return false
        }
        return true
    }
    fun mapCoordinate(value: Double, probabilityDimension: Int, sourceDimension: Int): Double {
        require(value.isFinite() && probabilityDimension in 1..4096 && sourceDimension in 1..4096)
        val divided: Float = value.toFloat() / probabilityDimension.toFloat()
        val multiplied: Float = divided * sourceDimension.toFloat()
        return Math.rint(multiplied.toDouble()).coerceIn(0.0, sourceDimension.toDouble())
    }
    /** Native boxPoints order -> upstream mini-box order (stable x sort, strict y comparisons). */
    fun miniOrder(points: List<GeometryProbeContract.Point>): List<GeometryProbeContract.Point> {
        require(points.size == 4 && points.all { it.x.isFinite() && it.y.isFinite() })
        val p = points.sortedBy { it.x }
        val left = if (p[1].y > p[0].y) listOf(p[0], p[1]) else listOf(p[1], p[0])
        val right = if (p[3].y > p[2].y) listOf(p[2], p[3]) else listOf(p[3], p[2])
        return PolygonOffsetKernel.immutable(listOf(left[0], right[0], right[1], left[1]))
    }
    fun finalQuad(raw: List<GeometryProbeContract.Point>, width: Int, height: Int): List<GeometryProbeContract.Point> {
        GeometryProbeContract.pixels(width, height)
        require(raw.size == 4 && raw.all { it.x.isFinite() && it.y.isFinite() })
        // Clockwise TL/TR/BR/BL, then clipping; coordinates remain float32.
        val x = raw.sortedBy { it.x }
        val left = x.take(2).sortedBy { it.y }
        val right = x.drop(2).sortedBy { it.y }
        return PolygonOffsetKernel.immutable(listOf(left[0], right[0], right[1], left[1]).map {
            GeometryProbeContract.Point(it.x.toFloat().toDouble().coerceIn(0.0, width - 1.0),
                it.y.toFloat().toDouble().coerceIn(0.0, height - 1.0))
        })
    }
    fun normInt(a: GeometryProbeContract.Point, b: GeometryProbeContract.Point): Int {
        val dx = a.x.toFloat() - b.x.toFloat(); val dy = a.y.toFloat() - b.y.toFloat()
        return sqrt(dx * dx + dy * dy).toInt()
    }
    fun finalSizeAccepted(q: List<GeometryProbeContract.Point>): Boolean {
        require(q.size == 4)
        return normInt(q[0], q[1]) > 3 && normInt(q[0], q[3]) > 3
    }
    fun readingOrder(boxes: List<Box>): List<Box> {
        require(boxes.size <= 1000 && boxes.map { it.finalBoxIndex } == boxes.indices.toList())
        val byY = boxes.sortedBy { it.quad[0].y }
        var line = 0
        val grouped = byY.mapIndexed { i, b ->
            if (i > 0 && b.quad[0].y - byY[i - 1].quad[0].y >= 10.0) line++
            line to b
        }
        return PolygonOffsetKernel.immutable(grouped.sortedWith(compareBy({ it.first }, { it.second.quad[0].x })).map { it.second })
    }
    fun cropRow(caseId: String, box: Box, rank: Int, width: Int, height: Int): GeometryProbeContract.Row {
        require(caseId.matches(Regex("[a-z0-9-]{1,64}")) && rank in 0 until 1000)
        val plan = GeometryProbeContract.cropPlan(width, height, box.quad)
        val matrix = GeometryProbeContract.homography(box.quad, plan)
        return GeometryProbeContract.Row("$caseId.box-${box.finalBoxIndex}", box.finalBoxIndex, rank,
            box.score, box.quad, plan, matrix.toList(), "")
    }
    /** Contours can contain 1 or 2 vertices. No deduplication, no polygon assumptions. */
    fun sameContour(a: List<PolygonOffsetKernel.IntPoint>, b: List<PolygonOffsetKernel.IntPoint>): Boolean {
        require(a.size <= MAX_SINGLE_CONTOUR_VERTICES && b.size <= MAX_SINGLE_CONTOUR_VERTICES)
        if (a.size != b.size) return false
        if (a.isEmpty()) return true
        for (start in b.indices) if (a[0] == b[start]) for (direction in listOf(1, -1)) {
            if (a.indices.all { a[it] == b[(start + direction * it + b.size) % b.size] }) return true
        }
        return false
    }
}
