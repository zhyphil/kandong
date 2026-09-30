package com.kandong.liveocr

import kotlin.math.abs
import kotlin.math.sqrt

/** Curated numerical geometry guards from the full-page experiment. */
internal object GeometryProbeContract {
    const val MAX_DIMENSION = 4096
    const val MAX_PIXELS = 1_048_576
    const val CONTOUR_BUDGET = 8192L
    const val CANDIDATE_LIMIT = 1000
    const val EPS = 1e-12
    const val MATRIX_ATOL = 1e-9
    const val MATRIX_RTOL = 1e-9
    const val POINT_ATOL = 1e-6
    const val POINT_RTOL = 1e-9
    enum class Status { COMPLETE, INCOMPLETE_CONTOUR_BUDGET, INCOMPLETE_CANDIDATE_LIMIT }
    data class Point(val x: Double, val y: Double)
    data class CropPlan(val width: Int, val height: Int) {
        val rotate get() = height.toDouble() / width >= 1.5
        val outputWidth get() = if (rotate) height else width
        val outputHeight get() = if (rotate) width else height
    }
    /** This entire frozen row travels with the crop. Never sort or recompute its score. */
    data class Row(val id: String, val originalIndex: Int, val readingOrder: Int,
        val detectorScore: Double, val quad: List<Point>, val plan: CropPlan,
        val referenceMatrix: List<Double>, val crop: String)

    fun pixels(width: Int, height: Int): Int {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "GEOMETRY_DIMENSIONS" }
        val count = width.toLong() * height.toLong()
        require(count in 1..MAX_PIXELS.toLong()) { "GEOMETRY_PIXEL_CAP" }
        return count.toInt()
    }
    fun probabilityPixels(shape: LongArray): Int {
        require(shape.size == 4 && shape[0] == 1L && shape[1] == 1L &&
            shape[2] in 1..MAX_DIMENSION.toLong() && shape[3] in 1..MAX_DIMENSION.toLong()) { "GEOMETRY_N1HW" }
        return pixels(shape[3].toInt(), shape[2].toInt())
    }
    fun validateProbability(shape: LongArray, values: FloatArray) {
        require(values.size == probabilityPixels(shape)) { "GEOMETRY_PROBABILITY_LENGTH" }
        require(values.all { it.isFinite() && it in 0f..1f }) { "GEOMETRY_PROBABILITY_VALUE" }
    }
    fun threshold(shape: LongArray, values: FloatArray): ByteArray {
        validateProbability(shape, values)
        return ByteArray(values.size) { if (values[it] > 0.3f) 1 else 0 }
    }
    fun bgr(width: Int, height: Int, argb: IntArray): ByteArray {
        require(argb.size == pixels(width, height) && argb.all { it ushr 24 == 255 }) { "GEOMETRY_OPAQUE_RGB" }
        return ByteArray((argb.size.toLong() * 3).toInt()).also { bytes ->
            argb.forEachIndexed { i, value -> for (c in 0..2) bytes[i * 3 + c] = (value ushr (c * 8)).toByte() }
        }
    }
    /** Conservative upper bound: one run per row plus every horizontal bit transition.
     * Applied BEFORE findContours; unlike perimeter/foreground counts this also bounds holes. */
    fun rowRuns(width: Int, height: Int, binary: ByteArray): Long {
        require(binary.size == pixels(width, height) && binary.all { it == 0.toByte() || it == 1.toByte() })
        var runs = height.toLong()
        for (y in 0 until height) for (x in 1 until width) {
            if (binary[y * width + x] != binary[y * width + x - 1]) runs++
        }
        return runs
    }
    fun contourStatus(count: Int): Status {
        require(count >= 0)
        return if (count > CANDIDATE_LIMIT) Status.INCOMPLETE_CANDIDATE_LIMIT else Status.COMPLETE
    }
    private fun validateQuad(width: Int, height: Int, q: List<Point>) {
        pixels(width, height)
        require(q.size == 4 && q.distinct().size == 4) { "GEOMETRY_QUAD_FOUR_UNIQUE" }
        require(q.all { it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.x <= width - 1.0 &&
            it.y >= 0 && it.y <= height - 1.0 }) { "GEOMETRY_QUAD_BOUNDS" }
        var sign = 0
        for (i in 0..3) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val u = (b.x - a.x) * (c.y - b.y); val v = (b.y - a.y) * (c.x - b.x)
            val cross = u - v
            require(cross.isFinite() && abs(cross) > EPS * maxOf(abs(u), abs(v))) { "GEOMETRY_QUAD_COLLINEAR" }
            val next = if (cross > 0) 1 else -1
            require(sign == 0 || sign == next) { "GEOMETRY_QUAD_NOT_CONVEX_ORDER" }; sign = next
        }
    }
    fun floatQuad(quad: List<Point>) = quad.map { Point(it.x.toFloat().toDouble(), it.y.toFloat().toDouble()) }
    fun cropPlan(width: Int, height: Int, quad: List<Point>): CropPlan {
        validateQuad(width, height, quad)
        val q = floatQuad(quad); validateQuad(width, height, q)
        // Upstream uses float32 ndarray subtraction, square, sum and norm, THEN int truncation.
        fun norm(a: Point, b: Point): Float {
            val dx = a.x.toFloat() - b.x.toFloat(); val dy = a.y.toFloat() - b.y.toFloat()
            return sqrt(dx * dx + dy * dy)
        }
        val w = maxOf(norm(q[0], q[1]), norm(q[2], q[3])).toInt()
        val h = maxOf(norm(q[0], q[3]), norm(q[1], q[2])).toInt()
        pixels(w, h)
        return CropPlan(w, h)
    }
    fun target(plan: CropPlan): List<Point> {
        pixels(plan.width, plan.height)
        return listOf(Point(0.0, 0.0), Point(plan.width.toDouble(), 0.0),
            Point(plan.width.toDouble(), plan.height.toDouble()), Point(0.0, plan.height.toDouble()))
    }
    fun normalize(matrix: DoubleArray): DoubleArray {
        require(matrix.size == 9 && matrix.all { it.isFinite() }) { "GEOMETRY_MATRIX_FINITE" }
        val scale = matrix.maxOf { abs(it) }
        require(scale > 0 && abs(matrix[8]) > EPS * scale) { "GEOMETRY_MATRIX_NORMALIZATION" }
        return DoubleArray(9) { matrix[it] / matrix[8] }.also { require(it.all { v -> v.isFinite() }) }
    }
    private fun solve(coefficients: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        require(n in 1..8 && coefficients.size == n && coefficients.all { row -> row.size == n && row.all { it.isFinite() } } && rhs.all { it.isFinite() })
        val a = Array(n) { coefficients[it].copyOf() }; val b = rhs.copyOf()
        val scales = DoubleArray(n) { a[it].maxOf { v -> abs(v) } }
        require(scales.all { it > 0 }) { "GEOMETRY_SINGULAR" }
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { abs(a[it][col]) / scales[it] }!!
            require(abs(a[pivot][col]) / scales[pivot] > EPS) { "GEOMETRY_RELATIVE_PIVOT" }
            val row = a[col]; a[col] = a[pivot]; a[pivot] = row
            val bv = b[col]; b[col] = b[pivot]; b[pivot] = bv
            val sv = scales[col]; scales[col] = scales[pivot]; scales[pivot] = sv
            val divisor = a[col][col]
            for (j in col until n) a[col][j] /= divisor
            b[col] /= divisor
            for (i in 0 until n) if (i != col) {
                val factor = a[i][col]
                for (j in col until n) a[i][j] -= factor * a[col][j]
                b[i] -= factor * b[col]
            }
        }
        require(b.all { it.isFinite() }) { "GEOMETRY_MATRIX_FINITE" }
        return b
    }
    fun inverse(matrix: DoubleArray): DoubleArray {
        val m = normalize(matrix)
        val a = Array(3) { r -> DoubleArray(3) { c -> m[r * 3 + c] } }
        val out = DoubleArray(9)
        for (c in 0..2) {
            val column = solve(a, DoubleArray(3) { if (it == c) 1.0 else 0.0 })
            for (r in 0..2) out[r * 3 + c] = column[r]
        }
        return normalize(out)
    }
    fun project(matrix: DoubleArray, p: Point): Point {
        require(p.x.isFinite() && p.y.isFinite())
        val m = normalize(matrix)
        val terms = doubleArrayOf(m[6] * p.x, m[7] * p.y, m[8])
        val denominator = terms.sum(); val scale = terms.sumOf { abs(it) }
        require(denominator.isFinite() && abs(denominator) > EPS * scale) { "GEOMETRY_PROJECTION_DENOMINATOR" }
        return Point((m[0] * p.x + m[1] * p.y + m[2]) / denominator,
            (m[3] * p.x + m[4] * p.y + m[5]) / denominator).also { require(it.x.isFinite() && it.y.isFinite()) }
    }
    /** Preflight solve and inverse before ANY native Mat allocation. OpenCV still computes its own matrix. */
    fun homography(quad: List<Point>, plan: CropPlan): DoubleArray {
        require(quad.size == 4)
        val q = floatQuad(quad); val targets = target(plan)
        val a = Array(8) { DoubleArray(8) }; val b = DoubleArray(8)
        for (i in 0..3) {
            val (x, y) = q[i]; val (u, v) = targets[i]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y); b[2 * i] = u
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y); b[2 * i + 1] = v
        }
        val m = normalize(solve(a, b) + 1.0); val inv = inverse(m)
        q.forEach { project(m, it) }; targets.forEach { project(inv, it) }
        return m
    }
    // Continuous extension of the PIXEL-index convention, not edge coordinates W/H.
    fun rotatePixel(p: Point, plan: CropPlan) = if (plan.rotate) Point(p.y, plan.width - 1.0 - p.x) else p
    fun unrotatePixel(p: Point, plan: CropPlan) = if (plan.rotate) Point(plan.width - 1.0 - p.y, p.x) else p
}
