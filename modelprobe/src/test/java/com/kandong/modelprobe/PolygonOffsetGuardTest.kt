package com.kandong.modelprobe

import com.kandong.modelprobe.PolygonOffsetKernel.IntPoint
import com.kandong.modelprobe.PolygonOffsetKernel.Point
import com.kandong.modelprobe.PolygonOffsetKernel.Status
import org.junit.Assert.*
import org.junit.Test

class PolygonOffsetGuardTest {
    private fun rectangle(x: Double = 0.0, y: Double = 0.0, w: Double = 10.0, h: Double = 10.0) =
        listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
    private fun output(paths: List<List<IntPoint>>) = object : PolygonOffsetKernel.Output {
        override val pathCount get() = paths.size
        override fun vertexCount(path: Int) = paths[path].size
        override fun point(path: Int, vertex: Int) = paths[path][vertex]
    }
    private val triangle = listOf(IntPoint(0, 0), IntPoint(1, 0), IntPoint(0, 1))

    @Test fun negativeHalfTiesMatchFrozenPyclipperVertices() {
        // Frozen half-1-0-0, independent literal golden; Math.round loses the -2 vertices.
        val expected = listOf(listOf(IntPoint(5, 0), IntPoint(5, 3), IntPoint(3, 5), IntPoint(0, 5),
            IntPoint(-2, 3), IntPoint(-2, 0), IntPoint(0, -2), IntPoint(3, -2)))
        val actual = PolygonOffsetKernel.offset(listOf(rectangle(w = 3.75, h = 3.75)), 1.5)
        assertEquals(Status.COMPLETE, actual.status)
        val comparison = PolygonOffsetKernel.compare(actual.paths, expected)
        assertTrue(comparison.toString(), comparison.passed)
        assertEquals(8, comparison.comparedVertices)
    }

    @Test fun invalidOriginalFloatAndIntegerPolygonsNeverReachLibrary() {
        val q = rectangle()
        val invalid = listOf(emptyList(), listOf(q, q, q), listOf(q.take(3)), listOf(q + q[0]),
            listOf(listOf(q[0], q[2], q[1], q[3])), listOf(listOf(q[0], q[1], q[1], q[3])),
            listOf(listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(2.0, 0.0), Point(0.0, 2.0))),
            listOf(listOf(Point(0.0, 0.0), Point(4.0, 0.0), Point(1.0, 1.0), Point(0.0, 4.0))),
            listOf(rectangle(w = 0.0)), listOf(rectangle(w = Double.NaN)),
            listOf(rectangle(w = Double.POSITIVE_INFINITY)), listOf(rectangle(y = Double.NEGATIVE_INFINITY)),
            listOf(rectangle(x = 8192.0)), listOf(rectangle(x = -8192.1)),
            listOf(rectangle(x = 8191.0, w = 0.00001)), // unique in Double, repeats in float32
            listOf(rectangle(w = 0.9)), // valid float32, repeats after integer truncation
            listOf(listOf(Point(0.0, 0.0), Point(1.0, -0.1), Point(2.0, 0.0), Point(2.0, 2.0))))
        var calls = 0
        val spy = PolygonOffsetKernel.Library { _, _ -> calls++; output(listOf(triangle)) }
        invalid.forEachIndexed { i, paths ->
            assertEquals("invalid input $i", Status.INVALID_INPUT, PolygonOffsetKernel.offset(paths, 1.0, spy).status)
        }
        assertEquals(17, invalid.size)
        assertEquals(0, calls)
    }

    @Test fun distanceAndGeneratedWorkAreCheckedBeforeLibrary() {
        var calls = 0
        val spy = PolygonOffsetKernel.Library { _, _ -> calls++; output(listOf(triangle)) }
        for (d in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Math.nextUp(128.0))) {
            assertEquals(Status.INVALID_INPUT, PolygonOffsetKernel.offset(listOf(rectangle()), d, spy).status)
        }
        val preflight = PolygonOffsetKernel.offset(listOf(rectangle()), 128.0, spy, generatedVertexLimit = 7)
        assertEquals(Status.BUDGET_EXCEEDED, preflight.status)
        assertTrue(preflight.generatedVertexBound > 7)
        assertEquals(0, calls)
        assertEquals(8, PolygonOffsetKernel.generatedVertexBound(2, Double.MIN_VALUE))
        assertTrue(PolygonOffsetKernel.generatedVertexBound(2, 1e-20) >= 8)
        assertTrue(PolygonOffsetKernel.generatedVertexBound(2, 128.0) <= 512)
        assertEquals(Status.COMPLETE, PolygonOffsetKernel.offset(listOf(rectangle()), Double.MIN_VALUE).status)
        assertEquals(Status.COMPLETE, PolygonOffsetKernel.offset(listOf(rectangle()), 128.0, spy).status)
        assertEquals(1, calls)
    }

    @Test fun duplicatePathsAndRelativeWindingSurviveInputConversion() {
        val q = rectangle(x = -10.75, y = -10.75, w = 3.75, h = 3.75)
        val q2 = q.reversed()
        var received: List<List<IntPoint>>? = null
        val spy = PolygonOffsetKernel.Library { paths, _ -> received = paths; output(listOf(triangle)) }
        assertEquals(Status.COMPLETE, PolygonOffsetKernel.offset(listOf(q, q2), 1.0, spy).status)
        val expected = listOf(IntPoint(-10, -10), IntPoint(-7, -10), IntPoint(-7, -7), IntPoint(-10, -7))
        assertEquals(listOf(expected, expected.reversed()), received)
        assertEquals(Status.COMPLETE, PolygonOffsetKernel.offset(listOf(q, q), 1.0, spy).status)
        assertEquals(listOf(expected, expected), received)
        assertEquals(Status.COMPLETE, PolygonOffsetKernel.offset(listOf(rectangle(x = 8182.0)), 1.0, spy).status)
    }

    @Test fun outputCapsPrecedeAnyCoordinateCopies() {
        var reads = 0
        fun counted(count: Int, size: Int) = object : PolygonOffsetKernel.Output {
            override val pathCount = count
            override fun vertexCount(path: Int) = size
            override fun point(path: Int, vertex: Int): IntPoint { reads++; return IntPoint(0, 0) }
        }
        assertEquals(Status.BUDGET_EXCEEDED, PolygonOffsetKernel.validateOutput(counted(9, 3)).status)
        assertEquals(Status.BUDGET_EXCEEDED, PolygonOffsetKernel.validateOutput(counted(1, 513)).status)
        assertEquals(Status.BUDGET_EXCEEDED, PolygonOffsetKernel.validateOutput(counted(2, 257)).status)
        assertEquals(Status.LIBRARY_ERROR, PolygonOffsetKernel.validateOutput(counted(1, 2)).status)
        assertEquals(Status.EMPTY_OUTPUT, PolygonOffsetKernel.validateOutput(counted(0, 0)).status)
        assertEquals(0, reads)
        assertEquals(Status.COMPLETE, PolygonOffsetKernel.validateOutput(counted(8, 64)).status)
        assertEquals(512, reads)
    }

    @Test fun libraryFailureAndEmptyOutputCannotMasqueradeAsComplete() {
        var called = false
        val failing = PolygonOffsetKernel.Library { _, _ -> called = true; throw IllegalStateException("injected") }
        val result = PolygonOffsetKernel.offset(listOf(rectangle()), 1.0, failing)
        assertTrue(called); assertEquals(Status.LIBRARY_ERROR, result.status); assertTrue(result.paths.isEmpty())
        val empty = PolygonOffsetKernel.offset(listOf(rectangle()), 1.0, PolygonOffsetKernel.Library { _, _ -> output(emptyList()) })
        assertEquals(Status.EMPTY_OUTPUT, empty.status)
        val over = PolygonOffsetKernel.offset(listOf(rectangle()), 1.0,
            PolygonOffsetKernel.Library { _, _ -> output(List(9) { triangle }) })
        assertEquals(Status.BUDGET_EXCEEDED, over.status); assertTrue(over.paths.isEmpty())
    }

    @Test fun resultsAreDeepImmutableAndDetachedFromLibraryLists() {
        val source = mutableListOf(triangle.toMutableList())
        val result = PolygonOffsetKernel.offset(listOf(rectangle()), 1.0, PolygonOffsetKernel.Library { _, _ -> output(source) })
        assertEquals(Status.COMPLETE, result.status)
        source[0][0] = IntPoint(99, 99); source.clear()
        assertEquals(listOf(triangle), result.paths)
        assertThrows(UnsupportedOperationException::class.java) { (result.paths as MutableList<List<IntPoint>>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.paths[0] as MutableList<IntPoint>)[0] = IntPoint(2, 2) }
        val canonical = PolygonOffsetKernel.canonical(result.paths)
        assertThrows(UnsupportedOperationException::class.java) { (canonical[0] as MutableList<IntPoint>).clear() }
    }

    @Test fun exactComparisonPreservesCollinearVerticesAndDuplicatePaths() {
        val a = listOf(IntPoint(0, 0), IntPoint(1, 0), IntPoint(2, 0), IntPoint(2, 2), IntPoint(0, 2))
        val b = a.map { IntPoint(it.x + 10, it.y) }
        val equivalent = listOf(b.reversed(), a.drop(2) + a.take(2), a.reversed())
        val expected = listOf(a, a, b)
        val match = PolygonOffsetKernel.compare(equivalent, expected)
        assertTrue(match.passed); assertEquals(15, match.comparedVertices); assertEquals(match.expectedHash, match.actualHash)
        val removedVertex = PolygonOffsetKernel.compare(listOf(a.filterIndexed { i, _ -> i != 1 }, a, b), expected)
        assertFalse(removedVertex.passed); assertTrue(removedVertex.unmatchedVertices > 0)
        assertFalse(PolygonOffsetKernel.compare(listOf(a, b), expected).passed)
        val shifted = a.toMutableList().also { it[0] = IntPoint(-1, 0) }
        assertFalse(PolygonOffsetKernel.compare(listOf(shifted, a, b), expected).passed)
    }

    @Test fun derivedDistanceUsesFloat32CoordinatesAndDoubleGeometry() {
        assertEquals(6.0, PolygonOffsetKernel.computedDistance(listOf(rectangle(w = 30.0, h = 10.0))), 0.0)
        assertEquals(6.0, PolygonOffsetKernel.computedDistance(listOf(rectangle(w = 30.0 - 1e-8, h = 10.0))), 0.0)
        assertEquals(1.5, PolygonOffsetKernel.computedDistance(listOf(rectangle(w = 3.75, h = 3.75))), 0.0)
        val rotated = listOf(Point(12.25, 6.75), Point(43.75, 18.25), Point(39.25, 31.75), Point(7.75, 20.25))
        assertEquals(7.989311616701829, PolygonOffsetKernel.computedDistance(listOf(rotated)), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { PolygonOffsetKernel.computedDistance(listOf(rectangle(), rectangle())) }
    }
}
