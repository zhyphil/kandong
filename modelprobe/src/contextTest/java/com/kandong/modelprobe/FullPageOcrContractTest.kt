package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test

class FullPageOcrContractTest {
    private val version = CaptureVersion(1, 2, 3, 4, 5, 0)
    private fun box(y: Double = 60.0) = BoxPipelineContract.Box(17, 12, 9,
        listOf(GeometryProbeContract.Point(40.0, y), GeometryProbeContract.Point(200.0, y),
            GeometryProbeContract.Point(200.0, y + 20), GeometryProbeContract.Point(40.0, y + 20)), .87)

    @Test fun auditedStrideBoundariesAndFrozenReferenceWidths() {
        val widths = listOf(320, 321, 324, 325, 327, 328, 329, 434, 452, 605, 672, 1024)
        val times = listOf(40, 40, 40, 41, 41, 41, 41, 54, 56, 76, 84, 128)
        for (model in FullPageOcrContract.models) widths.zip(times).forEach { (width, time) ->
            assertArrayEquals(longArrayOf(1, time.toLong(), model.vocabulary.toLong()),
                FullPageOcrContract.outputShape(model.id, model.sha, model.vocabulary, width))
        }
    }

    @Test fun shapeRejectsUnknownIdentityVocabularyAndWidthBeforeInference() {
        val m = FullPageOcrContract.models.first()
        for (width in listOf(Int.MIN_VALUE, 0, 319, 1025, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { FullPageOcrContract.outputShape(m.id, m.sha, m.vocabulary, width) }
        assertThrows(IllegalArgumentException::class.java) { FullPageOcrContract.outputShape("other", m.sha, m.vocabulary, 320) }
        assertThrows(IllegalArgumentException::class.java) { FullPageOcrContract.outputShape(m.id, "0".repeat(64), m.vocabulary, 320) }
        assertThrows(IllegalArgumentException::class.java) { FullPageOcrContract.outputShape(m.id, m.sha, 504, 320) }
    }

    @Test fun budgetsAcceptExactLimitsAndRejectWithoutOverflow() {
        FullPageOcrContract.boxBudget(64, 64)
        FullPageOcrContract.boxBudget(0, 128)
        assertEquals(8192, FullPageOcrContract.characterBudget(8191, 1))
        for ((strip, page) in listOf(65 to 0, 1 to 128, -1 to 0, 0 to -1, Int.MAX_VALUE to 1))
            assertThrows(IllegalArgumentException::class.java) { FullPageOcrContract.boxBudget(strip, page) }
        for ((used, added) in listOf(8192 to 1, -1 to 0, 0 to -1, 1 to Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { FullPageOcrContract.characterBudget(used, added) }
    }

    @Test fun sourceCoordinatesOnlyAddReadOffsetAndNeverRenumber() {
        val strip = FullPageStripPlanner.plan(1176, 2400).strips[1]
        val p = FullPageOcrContract.provenance(version, "en-seam", strip, box(), 4)
        assertEquals(536, p.read.top)
        assertEquals(40.0, p.pageQuad[0].x, 0.0)
        assertEquals(596.0, p.pageQuad[0].y, 0.0)
        assertEquals(17, p.contourIndex); assertEquals(12, p.rawBoxIndex); assertEquals(9, p.finalBoxIndex)
        assertEquals(4, p.stripReadingOrder); assertEquals(.87, p.detectorScore, 0.0)
        assertEquals(p.id, FullPageOcrContract.provenance(version, "en-seam", strip, box(), 0).id)
        assertNotEquals(p.id, FullPageOcrContract.provenance(version.copy(revision = 5), "en-seam", strip, box(), 4).id)
        assertThrows(UnsupportedOperationException::class.java) { (p.pageQuad as MutableList).clear() }
        val original = BoxPipelineContract.cropRow("en-seam", box(), 4, strip.read.width, strip.read.height)
        val temporary = FullPageOcrContract.numericalRow(original)
        CropRecognitionContract.validateRow(temporary)
        assertEquals("en-seam.box-9", original.id)
        assertEquals(9, original.originalIndex); assertEquals(4, original.readingOrder)
        assertEquals(original.quad, temporary.quad); assertEquals(original.plan, temporary.plan)
    }

    @Test fun overlapFragmentsAndEmptyStringsAreAllRetainedWithModelIdentity() {
        val strips = FullPageStripPlanner.plan(1176, 2400).strips
        val a = FullPageOcrContract.provenance(version, "en-seam", strips[0], box(550.0), 0)
        val b = FullPageOcrContract.provenance(version, "en-seam", strips[1], box(14.0), 0)
        val fragment = FullPageOcrContract.provenance(version, "en-seam", strips[1], box(14.0).copy(finalBoxIndex = 10), 1)
        assertEquals(a.pageQuad, b.pageQuad)
        assertTrue(a.ownsCoreCenter); assertFalse(b.ownsCoreCenter)
        val candidates = listOf(FullPageOcrContract.Candidate(a, "ch", "abc", 320, 40),
            FullPageOcrContract.Candidate(b, "ch", "abc", 320, 40),
            FullPageOcrContract.Candidate(b, "latin", "", 320, 40),
            FullPageOcrContract.Candidate(fragment, "ch", "ab", 320, 40))
        val kept = FullPageOcrContract.commit(true, candidates)
        assertEquals(candidates, kept); assertEquals(4, kept.size); assertNotEquals(a.id, b.id)
        assertTrue(FullPageOcrContract.commit(false, candidates).isEmpty())
    }

    @Test fun nativeCheckpointsRejectCancelRevisionExpiryAndClockReversal() {
        val meta = RgbaFrameMetadata(1176, 2400, version, 100, 1000)
        var cp = CaptureCheckpoint(version, true, 100)
        val guard = FullPageOcrContract.Current(meta) { cp }
        guard.check()
        cp = cp.copy(nowMillis = 1099); guard.check()
        for (bad in listOf(cp.copy(active = false), cp.copy(version = version.copy(page = 4)),
            cp.copy(nowMillis = 1100), cp.copy(nowMillis = 1098))) {
            cp = bad
            assertThrows(java.util.concurrent.CancellationException::class.java) { guard.check() }
        }
    }
}
