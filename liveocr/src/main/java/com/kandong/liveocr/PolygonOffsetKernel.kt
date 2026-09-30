package com.kandong.liveocr

import de.lighti.clipper.Clipper
import de.lighti.clipper.ClipperOffset
import de.lighti.clipper.Path
import de.lighti.clipper.Paths
import de.lighti.clipper.Point.LongPoint
import java.util.Collections
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot

/** Bounded positive ROUND/CLOSED_POLYGON adapter, curated from the experiment. No general Boolean API.
 * The preflight bounds offset-generated vertices, NOT all UNION internal allocations.
 * Output limits are postconditions; this wrapper cannot hard-interrupt vendor execution. */
internal object PolygonOffsetKernel {
    const val MAX_COORDINATE = 8192.0
    const val MAX_DISTANCE = 128.0
    const val MAX_OUTPUT_PATHS = 8
    const val MAX_OUTPUT_VERTICES = 512
    const val MAX_GENERATED_VERTICES = 512
    const val DISTANCE_ATOL = 1e-9
    const val DISTANCE_RTOL = 1e-12
    data class Point(val x: Double, val y: Double)
    data class IntPoint(val x: Long, val y: Long)
    enum class Status { COMPLETE, INVALID_INPUT, BUDGET_EXCEEDED, EMPTY_OUTPUT, LIBRARY_ERROR }
    data class Result(val status: Status, val paths: List<List<IntPoint>> = emptyList(),
        val reason: String = "", val generatedVertexBound: Int = 0)

    /** A lazy view permits size validation BEFORE copying vendor output. Also the failure-injection seam. */
    internal interface Output {
        val pathCount: Int
        fun vertexCount(path: Int): Int
        fun point(path: Int, vertex: Int): IntPoint
    }
    internal fun interface Library {
        fun execute(paths: List<List<IntPoint>>, distance: Double): Output
    }
    private val vendor = Library { paths, distance ->
        val offset = ClipperOffset(2.0, 0.25)
        paths.forEach { vertices ->
            val path = Path(vertices.size)
            vertices.forEach { path.add(LongPoint(it.x, it.y)) }
            // Preserve relative winding; the pinned vendor applies its own orientation rules.
            offset.addPath(path, Clipper.JoinType.ROUND, Clipper.EndType.CLOSED_POLYGON)
        }
        val output = Paths()
        offset.execute(output, distance)
        object : Output {
            override val pathCount get() = output.size
            override fun vertexCount(path: Int) = output[path].size
            override fun point(path: Int, vertex: Int) = output[path][vertex].let { IntPoint(it.x, it.y) }
        }
    }

    internal fun <T> immutable(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    internal fun <T> immutablePaths(paths: List<List<T>>): List<List<T>> = immutable(paths.map { immutable(it) })

    private fun validateQuad(q: List<Point>) {
        require(q.size == 4) { "NON_QUAD" }
        require(q.all { it.x.isFinite() && it.y.isFinite() && abs(it.x) <= MAX_COORDINATE && abs(it.y) <= MAX_COORDINATE }) { "COORDINATE" }
        require(q.distinct().size == 4) { "REPEATED_VERTEX" }
        var sign = 0
        for (i in 0..3) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            require(cross.isFinite() && cross != 0.0) { "COLLINEAR" }
            val next = if (cross > 0.0) 1 else -1
            require(sign == 0 || next == sign) { "NON_CONVEX_ORDER" }
            sign = next
        }
    }

    /** All three domains must retain strict convexity. Cross-path duplicates are valid. */
    internal fun validateInput(paths: List<List<Point>>): List<List<IntPoint>> {
        require(paths.size in 1..2) { "PATH_COUNT" }
        return immutablePaths(paths.map { q ->
            validateQuad(q)
            val floats = q.map { Point(it.x.toFloat().toDouble(), it.y.toFloat().toDouble()) }
            validateQuad(floats)
            val integers = floats.map { IntPoint(it.x.toLong(), it.y.toLong()) }
            validateQuad(integers.map { Point(it.x.toDouble(), it.y.toDouble()) })
            integers
        })
    }

    internal fun validateDistance(distance: Double) {
        require(distance.isFinite() && distance > 0.0 && distance <= MAX_DISTANCE) { "DISTANCE" }
    }

    /** Per corner atan2 has |angle| <= PI. Round steps <= ceil(steps/2 + .5),
     * plus its endpoint; concave/relative-hole corners emit at most three vertices.
     * The vendor's strict nearZero branch bypasses arc math and returns input paths. */
    internal fun generatedVertexBound(pathCount: Int, distance: Double): Int {
        require(pathCount in 1..2)
        validateDistance(distance)
        if (distance < 1e-20) return pathCount * 4
        val tolerance = minOf(0.25, distance * 0.25)
        val steps = Math.PI / acos(1.0 - tolerance / distance)
        require(steps.isFinite() && steps > 0.0) { "ARC_BOUND" }
        return pathCount * 4 * maxOf(3, ceil(steps / 2.0 + 0.5).toInt() + 1)
    }

    fun offset(paths: List<List<Point>>, distance: Double, library: Library = vendor,
        generatedVertexLimit: Int = MAX_GENERATED_VERTICES): Result {
        val integerPaths: List<List<IntPoint>>
        val bound: Int
        try {
            integerPaths = validateInput(paths)
            validateDistance(distance)
            require(generatedVertexLimit in 1..MAX_GENERATED_VERTICES) { "GENERATED_LIMIT" }
            bound = generatedVertexBound(integerPaths.size, distance)
        } catch (e: IllegalArgumentException) {
            return Result(Status.INVALID_INPUT, reason = e.message ?: "INPUT")
        }
        if (bound > generatedVertexLimit) return Result(Status.BUDGET_EXCEEDED, reason = "GENERATED_VERTICES", generatedVertexBound = bound)
        return try {
            validateOutput(library.execute(integerPaths, distance)).copy(generatedVertexBound = bound)
        } catch (_: Exception) {
            Result(Status.LIBRARY_ERROR, reason = "VENDOR_EXCEPTION", generatedVertexBound = bound)
        }
    }

    /** Counts are checked before reading even one coordinate; no truncated success. */
    internal fun validateOutput(output: Output): Result {
        val count = output.pathCount
        if (count == 0) return Result(Status.EMPTY_OUTPUT, reason = "EMPTY")
        if (count < 0) return Result(Status.LIBRARY_ERROR, reason = "OUTPUT_PATH_COUNT")
        if (count > MAX_OUTPUT_PATHS) return Result(Status.BUDGET_EXCEEDED, reason = "OUTPUT_PATHS")
        val sizes = IntArray(count)
        var total = 0L
        for (p in 0 until count) {
            val size = output.vertexCount(p)
            if (size < 3) return Result(Status.LIBRARY_ERROR, reason = "OUTPUT_DEGENERATE")
            total += size.toLong()
            if (total > MAX_OUTPUT_VERTICES) return Result(Status.BUDGET_EXCEEDED, reason = "OUTPUT_VERTICES")
            sizes[p] = size
        }
        val copy = List(count) { p -> List(sizes[p]) { v -> output.point(p, v).copy() } }
        // Pinned positive offset cannot leave this conservative envelope.
        if (copy.any { q -> q.any { it.x !in -8321L..8321L || it.y !in -8321L..8321L } })
            return Result(Status.LIBRARY_ERROR, reason = "OUTPUT_COORDINATE")
        return Result(Status.COMPLETE, immutablePaths(copy))
    }

    fun computedDistance(paths: List<List<Point>>): Double {
        require(paths.size == 1) { "SINGLE_QUAD_REQUIRED" }
        validateInput(paths)
        val q = paths.single().map { Point(it.x.toFloat().toDouble(), it.y.toFloat().toDouble()) }
        var twiceArea = 0.0
        var perimeter = 0.0
        // Triangulating relative to the first vertex avoids cancellation after translation.
        for (i in 1..2) twiceArea += (q[i].x - q[0].x) * (q[i + 1].y - q[0].y) -
            (q[i].y - q[0].y) * (q[i + 1].x - q[0].x)
        for (i in 0..3) perimeter += hypot(q[(i + 1) % 4].x - q[i].x, q[(i + 1) % 4].y - q[i].y)
        val distance = abs(twiceArea) * 0.5 * 1.6 / perimeter
        validateDistance(distance)
        return distance
    }

}
