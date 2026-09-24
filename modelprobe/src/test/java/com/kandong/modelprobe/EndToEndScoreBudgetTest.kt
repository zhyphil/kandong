package com.kandong.modelprobe

import java.nio.FloatBuffer
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class EndToEndScoreBudgetTest {
    private fun metrics(actual: FloatArray, reference: FloatArray) = DetectorComparison.compare(
        FloatBuffer.wrap(actual), reference, ByteArray(reference.size) { if (reference[it] > 0.3f) 1 else 0 })

    @Test fun identicalProbabilityInputKeepsTheFrozenArithmeticGate() {
        val values = floatArrayOf(0f, 0.3f, 0.8f, 1f)
        assertEquals(1e-7, EndToEndScoreBudget.fromDetector(metrics(values, values)), 0.0)
    }

    @Test fun acceptedInputErrorPropagatesToEveryUnchangedMeanMask() {
        val reference = floatArrayOf(0.79589534f, 0.8009912f, 0.91f, 0.73f)
        val actual = reference.map { Math.nextDown(Math.nextDown(Math.nextDown(it))) }.toFloatArray()
        val observed = metrics(actual, reference)
        assertTrue(observed.passed)
        val budget = EndToEndScoreBudget.fromDetector(observed)
        // Independent means for every nonempty subset, including sparse and single-pixel masks.
        for (mask in 1 until (1 shl reference.size)) {
            val selected = reference.indices.filter { mask and (1 shl it) != 0 }
            val difference = abs(selected.map { actual[it].toDouble() }.average() - selected.map { reference[it].toDouble() }.average())
            assertTrue(difference > EndToEndScoreBudget.SAME_INPUT_ATOL)
            assertTrue(difference <= budget)
        }
        assertEquals(EndToEndScoreBudget.SAME_INPUT_ATOL + observed.maxAbsolute, budget, 0.0)
    }

    @Test fun badDetectorOutputCannotAcquireALargerScoreBudget() {
        val rejected = listOf(
            metrics(floatArrayOf(Math.nextUp(0.3f)), floatArrayOf(0.3f)), // Numeric-close threshold flip.
            metrics(floatArrayOf(0.000002f), floatArrayOf(0f)), // Fails independent mean gate.
            metrics(floatArrayOf(Float.NaN), floatArrayOf(0f)),
            metrics(floatArrayOf(0.6f), floatArrayOf(0.8f)))
        rejected.forEach { result ->
            assertFalse(result.passed)
            assertThrows(IllegalArgumentException::class.java) { EndToEndScoreBudget.fromDetector(result) }
        }
    }
    @Test fun nearEqualFloatingQuadsCannotHideChangedScoreMasks() {
        fun quad(left: Double, right: Double) = listOf(
            GeometryProbeContract.Point(left, 1.2), GeometryProbeContract.Point(right, 1.2),
            GeometryProbeContract.Point(right, 3.2), GeometryProbeContract.Point(left, 3.2))
        // A sub-1e-4 change can cross the ROI floor boundary; it must NOT be accepted as the same mask.
        assertNotEquals(EndToEndScoreBudget.maskFootprint(quad(1.0, 3.2), 8, 8),
            EndToEndScoreBudget.maskFootprint(quad(0.99999, 3.2), 8, 8))
        assertEquals(EndToEndScoreBudget.maskFootprint(quad(1.2, 3.2), 8, 8),
            EndToEndScoreBudget.maskFootprint(quad(1.3, 3.3), 8, 8))
    }

}
