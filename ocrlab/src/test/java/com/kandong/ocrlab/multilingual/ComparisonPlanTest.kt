package com.kandong.ocrlab.multilingual

import com.kandong.ocrlab.RunGate
import org.junit.Assert.*
import org.junit.Test

class ComparisonPlanTest {
    @Test fun everyInputHasBothEnginesForEveryRenderingAndScale() {
        val tasks = ComparisonPlan.matrix((0 until 18).map { "input-$it" })
        assertEquals(144, tasks.size)
        assertEquals(144, tasks.map { it.taskId }.toSet().size)
        tasks.groupBy { it.inputId }.values.forEach { rows ->
            assertEquals(8, rows.size)
            assertEquals(setOf("native/1/latin", "native/1/chinese", "native/2/latin", "native/2/chinese",
                "fixed/1/latin", "fixed/1/chinese", "fixed/2/latin", "fixed/2/chinese"),
                rows.map { "${it.sourceKind}/${it.scale}/${it.engine}" }.toSet())
        }
    }
    @Test fun matrixRejectsMissingAndDuplicateInputs() {
        rejects { ComparisonPlan.matrix(listOf("one")) }
        rejects { ComparisonPlan.matrix(List(18) { "same" }) }
    }
    @Test fun replicationCopiesPixelsInTwoByTwoSquares() {
        assertArrayEquals(intArrayOf(1, 1, 2, 2, 1, 1, 2, 2, 3, 3, 4, 4, 3, 3, 4, 4),
            ComparisonPlan.doublePixels(intArrayOf(1, 2, 3, 4), 2, 2))
        rejects { ComparisonPlan.doublePixels(intArrayOf(1), 2, 1) }
        rejects { ComparisonPlan.doublePixels(IntArray(1025), 1025, 1) }
    }
    @Test fun hashUsesOpaqueRgbaByteOrderAndRejectsTampering() {
        val pixels = intArrayOf(0xff123456.toInt())
        assertEquals("e12368e83363e964ca48b48a53517f36880ec664f2a98b20371ab5b963c2ebb4",
            ComparisonPlan.pixelHash(pixels))
        rejects { ComparisonPlan.pixelHash(intArrayOf(0x00123456)) }
        rejects { ComparisonPlan.verifyHash(byteArrayOf(1), ComparisonPlan.sha256(byteArrayOf(2))) }
        ComparisonPlan.verifyHash(byteArrayOf(1), ComparisonPlan.sha256(byteArrayOf(1)))
    }
    @Test fun dimensionsAndDecodedPixelsMustMatchIndependently() {
        val hash = "12a3ae445661ce5dee78d0650d33362dec29c4f82af05e7e57fb595bbbacf0ca"
        ComparisonPlan.verifyPixels(2, 1, 2, 1, intArrayOf(-1, -1), hash)
        rejects { ComparisonPlan.verifyPixels(1, 2, 2, 1, intArrayOf(-1, -1), hash) }
        rejects { ComparisonPlan.verifyPixels(2, 1, 2, 1, intArrayOf(-1, 0xff000000.toInt()), hash) }
        rejects { ComparisonPlan.dimensions(2049, 1) }
        rejects { ComparisonPlan.dimensions(2048, 2048) }
    }
    @Test fun geometryRetainsFractionalCoordinates() {
        assertArrayEquals(floatArrayOf(0.5f, 1.5f, 4.5f, 5.5f),
            ComparisonPlan.unscale(intArrayOf(1, 3, 9, 11), 2f, 2f), 0f)
        rejects { ComparisonPlan.unscale(intArrayOf(1, 3, 9, 11), 0f, 2f) }
    }
    @Test fun cancellationDoesNotAdvanceToSecondEngineAndBusyDrainingBlocksBothEntrypoints() {
        val cursor = ComparisonCursor(ComparisonPlan.matrix((0 until 18).map { "input-$it" }))
        val gate = RunGate()
        val token = gate.acquire()!!
        assertEquals("latin", cursor.next(gate.canPublish(token))!!.engine)
        assertTrue(gate.beginTask(token))
        assertTrue(gate.cancel(token))
        assertFalse(gate.release(token)) // In-flight SDK still owns its Bitmap.
        assertNull(gate.acquire())
        assertTrue(gate.endTask(token))
        cursor.completed()
        assertNull(cursor.next(gate.canPublish(token)))
        assertTrue(gate.release(token))
        assertEquals(1, cursor.completedCount)
        assertFalse(ComparisonPlan.canStart(true, false))
        assertFalse(ComparisonPlan.canStart(false, true))
        assertFalse(ComparisonPlan.canStart(true, true))
        assertTrue(ComparisonPlan.canStart(false, false))
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected rejected input") } catch (_: IllegalArgumentException) { }
    }
}
