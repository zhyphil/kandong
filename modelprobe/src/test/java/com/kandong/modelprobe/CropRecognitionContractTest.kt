package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

class CropRecognitionContractTest {
    private fun row(index: Int, rank: Int = index): GeometryProbeContract.Row {
        val q = listOf(GeometryProbeContract.Point(1.0, 1.0), GeometryProbeContract.Point(21.0, 1.0),
            GeometryProbeContract.Point(21.0, 11.0), GeometryProbeContract.Point(1.0, 11.0))
        val plan = GeometryProbeContract.cropPlan(30, 30, q)
        return GeometryProbeContract.Row("sample.box-$index", index, rank, .73 + index * .01, q, plan,
            GeometryProbeContract.homography(q, plan).toList(), "")
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    @Test fun nonidentityKeepsFullIdentityAndSortsAfterRecognition() {
        val rows = listOf(row(1, 0), row(0, 1))
        val bound = CropRecognitionContract.bind(BoxPipelineContract.Status.COMPLETE, rows, listOf(1, 0), listOf("繁體 é", "A7"))
        assertEquals(listOf("A7", "繁體 é"), bound.map { it.raw })
        assertSame(rows[0], bound[0].row); assertSame(rows[1], bound[1].row)
        assertEquals(listOf(1, 0), bound.map { it.tensorRow })
    }
    @Test fun equalRatiosStayStable() {
        val plan = RecognitionPacking.plan(listOf(RecognitionPacking.Size(20, 10), RecognitionPacking.Size(40, 20), RecognitionPacking.Size(10, 10)))
        assertEquals(listOf(2, 0, 1), plan.tensorRowToInput)
        val rows = listOf(row(0), row(1), row(2))
        assertEquals(listOf("b", "c", "a"), CropRecognitionContract.bind(BoxPipelineContract.Status.COMPLETE,
            rows, plan.tensorRowToInput, listOf("a", "b", "c")).map { it.raw })
    }
    @Test fun rejectsDuplicateNonpermutationAndOutOfRangeMappings() {
        for (order in listOf(listOf(0, 0), listOf(1), listOf(-1, 0), listOf(0, 2))) rejects {
            CropRecognitionContract.bind(BoxPipelineContract.Status.COMPLETE, listOf(row(0), row(1)), order, listOf("a", "b")) }
        rejects { CropRecognitionContract.validateRows(listOf(row(0), row(0))) }
        rejects { CropRecognitionContract.validateRows(listOf(row(0), row(1, 0))) }
    }
    @Test fun emptyAndIncompleteCannotInventOutput() {
        for (s in BoxPipelineContract.Status.entries) {
            assertTrue(CropRecognitionContract.bind(s, emptyList(), emptyList(), emptyList()).isEmpty())
            rejects { CropRecognitionContract.bind(s, emptyList(), emptyList(), listOf("invented")) }
            if (s != BoxPipelineContract.Status.COMPLETE) rejects { CropRecognitionContract.bind(s, listOf(row(0)), listOf(0), listOf("x")) }
        }
    }
    @Test fun rejectsMismatchNonfiniteAndInvalidIdentity() {
        rejects { CropRecognitionContract.bind(BoxPipelineContract.Status.COMPLETE, listOf(row(0)), listOf(0), emptyList()) }
        rejects { CropRecognitionContract.validateRows(listOf(row(0).copy(detectorScore = Double.NaN))) }
        rejects { CropRecognitionContract.validateRows(listOf(row(0).copy(quad = listOf(GeometryProbeContract.Point(Double.POSITIVE_INFINITY, 0.0))))) }
        rejects { CropRecognitionContract.validateRows(listOf(row(0).copy(id = "other.box-1"))) }
        rejects { CropRecognitionContract.validateRows(listOf(row(0).copy(referenceMatrix = List(9) { 0.0 }))) }
    }
    @Test fun validatesTensorAndCropBeforeAllocation() {
        val shape = longArrayOf(1, 3, 48, 1); val good = FloatArray(144)
        CropRecognitionContract.validateTensor(shape, good)
        rejects { CropRecognitionContract.validateTensor(shape, good.copyOf(143)) }
        rejects { CropRecognitionContract.validateTensor(shape, good.also { it[0] = Float.NaN }) }
        rejects { CropRecognitionContract.validateTensor(longArrayOf(4, 3, 48, 2048), FloatArray(0)) }
        rejects { CropRecognitionContract.validateCrop(Int.MAX_VALUE, 2, 6, 2) }
        rejects { CropRecognitionContract.validateCrop(2, 2, 11, 2) }
        rejects { CropRecognitionContract.validateCrop(2, 2, 12, 2049) }
    }
}
