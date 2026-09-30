package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import com.kandong.modelprobe.FullPageOcrPublication.Guard
import com.kandong.modelprobe.FullPageOcrPublication.Input
import com.kandong.modelprobe.FullPageOcrPublication.Outcome
import com.kandong.modelprobe.FullPageOcrPublication.Strip
import com.kandong.modelprobe.GeometryProbeContract.Point
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class FullPageOcrPublicationTest {
    private val version = CaptureVersion(1, 2, 3, 4, 5, 0)
    private val meta = RgbaFrameMetadata(1176, 2400, version, 100, 1000)
    private val plan = FullPageStripPlanner.plan(meta.width, meta.height)
    private val model = FullPageOcrContract.models.first()
    private val detector = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
    private val dictionary = "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af"
    private fun guard(source: () -> CaptureCheckpoint = { CaptureCheckpoint(version, true, 101) }) = Guard(meta, source)
    private fun candidate(id: String, strip: Int, text: String, owns: Boolean = true): FullPageOcrContract.Candidate {
        val s = plan.strips[strip]
        val quad = listOf(Point(100.0, 580.0), Point(300.0, 580.0), Point(300.0, 620.0), Point(100.0, 620.0))
        return FullPageOcrContract.Candidate(FullPageOcrContract.Provenance(version, "wire-page", strip,
            s.read, s.core, 0, 0, 0, 0, quad.map { Point(it.x, it.y - s.read.top) }, quad,
            .85, owns, id), model.id, text, 320, 40)
    }
    private fun input(cs: List<FullPageOcrContract.Candidate> = emptyList()) = Input(true, null, cs,
        plan.strips.map { Strip(it.index, it.read, it.core, it.detector.width, it.detector.height,
            "COMPLETE", cs.count { c -> c.provenance.stripIndex == it.index }) }, plan.strips.size, cs.size, 1, 1, null)
    private fun stage(i: Input = input(), g: Guard = guard(), m: FullPageOcrContract.Model = model,
        dict: String = dictionary, page: String = "wire-page", metadata: RgbaFrameMetadata = meta) =
        FullPageOcrPublication.stage("wire-run", page, metadata, m, detector, dict, i, g)
    private fun rejected(out: Outcome) { assertFalse(out.published); assertNotNull(out.reason); assertNull(out.association) }

    @Test fun completeBlankUsesCurrentMetadataAndRealZeroReceipts() {
        val out = stage().publish(true, true)
        assertTrue(out.published); assertNull(out.reason)
        assertEquals(version, out.association!!.identity!!.version)
        assertTrue(out.association.rawCandidates.isEmpty()); assertTrue(out.association.groups.isEmpty())
    }

    @Test fun conflictsEmptyAndNoncoreSurviveWithImmutableSnapshot() {
        val cs = arrayListOf(candidate("a", 0, "稅"), candidate("b", 1, "税", false), candidate("c", 2, ""))
        // Move the third singleton into its own actual read range.
        val old = cs[2].provenance; val s = plan.strips[2]
        val q = listOf(Point(500.0, 1500.0), Point(600.0, 1500.0), Point(600.0, 1520.0), Point(500.0, 1520.0))
        cs[2] = cs[2].copy(provenance = FullPageOcrContract.Provenance(version, "wire-page", 2, s.read, s.core,
            0, 0, 0, 0, q.map { Point(it.x, it.y - s.read.top) }, q, old.detectorScore, false, "c"))
        val expected = cs.toList(); val pending = stage(input(cs)); cs.clear()
        val out = pending.publish(true, true); assertTrue(out.published)
        val result = out.association!!
        assertEquals(expected.map { it.rawText }, result.rawCandidates.map { it.rawText })
        assertEquals(2, result.rawCandidates.count { !it.provenance.ownsCoreCenter })
        assertEquals(listOf("a", "b", "c"), result.groups.flatMap { it.memberIds }.sorted())
        assertEquals(FullPageOcrAssociation.Text.DIFFERENT_RAW, result.groups.first { "a" in it.memberIds }.text)
        assertTrue(result.groups.all { it.agreedRaw == null })
        assertThrows(UnsupportedOperationException::class.java) { (result.groups as MutableList<*>).clear() }
    }

    @Test fun completedDetectionDoesNotPublishRecognitionFailureOrPartialPage() {
        val cs = listOf(candidate("a", 0, "partial")); val i = input(cs)
        for (bad in listOf(i.copy(complete = false, reason = "RECOGNIZER_FAILED"),
            i.copy(reason = "LATE_FAILURE"), i.copy(recognitionInvocations = 2),
            i.copy(frameClosed = 0), i.copy(frameCloseAttempts = 2), i.copy(transportRejection = "CLOSE_FAILED"))) {
            rejected(stage(bad).publish(true, true))
        }
    }

    @Test fun missingDuplicateFailedOrFalseCountsCannotAuthenticateBlank() {
        val i = input(); val a = i.strips.first()
        for (strips in listOf(i.strips.dropLast(1), listOf(a, a) + i.strips.drop(2),
            listOf(a.copy(status = "INCOMPLETE")) + i.strips.drop(1),
            listOf(a.copy(boxes = 1)) + i.strips.drop(1),
            listOf(a.copy(detectorWidth = a.detectorWidth + 1)) + i.strips.drop(1))) {
            rejected(stage(i.copy(strips = strips)).publish(true, true))
        }
        rejected(stage(i.copy(detectorInvocations = 0)).publish(true, true))
    }

    @Test fun invalidModelDictionaryPageOrGuardMetadataRejects() {
        rejected(stage(m = model.copy(sha = "bad")).publish(true, true))
        rejected(stage(dict = "bad").publish(true, true))
        rejected(stage(input(listOf(candidate("a", 0, "raw"))), page = "other-page").publish(true, true))
        rejected(stage(metadata = meta.copy(version = version.copy(snapshot = 9))).publish(true, true))
    }

    @Test fun monotonicTimeIsContinuousFromNativeReadToAssociationAndPublish() {
        var now = 150L; val g = guard { CaptureCheckpoint(version, true, now) }
        g.checkpoint(); now = 149
        rejected(stage(g = g).publish(true, true))
        now = 151; assertFalse(g.isCurrent()) // A cancelled page cannot be revived.
        val fresh = guard { CaptureCheckpoint(version, true, now) }
        val pending = stage(g = fresh); now = 150
        rejected(pending.publish(true, true))
    }

    @Test fun expiryAtBoundaryAndEveryVersionFieldAreSticky() {
        val changes = listOf(version.copy(session = 7), version.copy(snapshot = 7), version.copy(page = 7),
            version.copy(revision = 7), version.copy(window = 7), version.copy(display = 7))
        val bad = changes.map { CaptureCheckpoint(it, true, 101) } +
            listOf(CaptureCheckpoint(version, true, 1100), CaptureCheckpoint(version, false, 101))
        for (invalid in bad) {
            var cp = CaptureCheckpoint(version, true, 101); val g = guard { cp }
            val pending = stage(g = g); cp = invalid
            rejected(pending.publish(true, true)); cp = CaptureCheckpoint(version, true, 102)
            assertFalse(g.isCurrent()); assertThrows(CancellationException::class.java) { g.checkpoint() }
        }
    }

    @Test fun callbackFailureDuringStagingOrAfterStagingPublishesNothing() {
        var fail = false; val g = guard { if (fail) error("CHECKPOINT_FAILED"); CaptureCheckpoint(version, true, 101) }
        val pending = stage(g = g); fail = true
        rejected(pending.publish(true, true)); fail = false; assertFalse(g.isCurrent())
        var calls = 0
        rejected(stage(g = guard { if (++calls == 3) error("MID_ASSOCIATION"); CaptureCheckpoint(version, true, 101) }).publish(true, true))
    }

    @Test fun balancedCleanupAloneCannotPublishFailedScope() {
        rejected(stage().publish(false, true))
        rejected(stage().publish(true, false))
        rejected(stage().publish(false, false))
    }

    @Test fun publicationIsSingleUseAndExplicitDiscardClearsStagedEvidence() {
        val once = stage(); assertTrue(once.publish(true, true).published); rejected(once.publish(true, true))
        val discarded = stage(input(listOf(candidate("a", 0, "private"))))
        discarded.discard(); rejected(discarded.publish(true, true))
        val badScope = stage(); rejected(badScope.publish(false, true)); rejected(badScope.publish(true, true))
    }

    @Test fun malformedMetadataIsLazyStickyAndNeverSkipsFrameClose() {
        val invalid = listOf(meta.copy(width = 0), meta.copy(height = 0), meta.copy(acquiredAtMillis = -1),
            meta.copy(ttlMillis = 0), meta.copy(ttlMillis = 60_001),
            meta.copy(acquiredAtMillis = Long.MAX_VALUE, ttlMillis = 1),
            meta.copy(version = version.copy(session = -1)), meta.copy(version = version.copy(snapshot = -1)),
            meta.copy(version = version.copy(page = -1)), meta.copy(version = version.copy(revision = -1)),
            meta.copy(version = version.copy(window = -1)), meta.copy(version = version.copy(display = -1)))
        for (metadata in invalid) {
            var callbacks = 0; var closed = 0
            val g = Guard(metadata) { callbacks++; CaptureCheckpoint(metadata.version, true, 101) }
            assertEquals(0, callbacks)
            val frameMetadata = metadata
            val frame = object : RgbaFrameLease {
                override val metadata = frameMetadata
                override fun plane(): RgbaPlane = error("INVALID_FRAME_MUST_NOT_BE_READ")
                override fun close() { closed++ }
            }
            val result = SingleFrameStripInput.process(frame, g::checkpoint) { _, _ -> Unit }
            assertNotNull(result.rejection); assertEquals(1, closed); assertEquals(0, callbacks)
            rejected(stage(g = g, metadata = metadata).publish(true, true))
            assertFalse(g.isCurrent()); assertEquals(0, callbacks)
        }
    }

    @Test fun receiptGeometryAndInvocationCountsAreCheckedWithoutDependingOnOrder() {
        val i = input(); val first = i.strips.first()
        assertTrue(stage(i.copy(strips = i.strips.reversed())).publish(true, true).published)
        for (bad in listOf(first.copy(read = first.read.copy(bottom = first.read.bottom - 1)),
            first.copy(core = first.core.copy(bottom = first.core.bottom - 1)),
            first.copy(detectorHeight = first.detectorHeight + 1), first.copy(boxes = -1))) {
            rejected(stage(i.copy(strips = listOf(bad) + i.strips.drop(1))).publish(true, true))
        }
        rejected(stage(i.copy(frameCloseAttempts = 0)).publish(true, true))
        rejected(stage(i.copy(frameClosed = 2)).publish(true, true))
        rejected(stage(i.copy(recognitionInvocations = -1)).publish(true, true))
        rejected(FullPageOcrPublication.stage("wire-run", "wire-page", meta, model, "bad", dictionary, i, guard())
            .publish(true, true))
    }

    @Test fun exceptionAtLastAssociationCheckpointStillRejectsAndDoesNotRetainCallback() {
        var baselineCalls = 0
        val baseline = stage(g = guard { baselineCalls++; CaptureCheckpoint(version, true, 101) })
        baseline.discard()
        var calls = 0
        val g = guard {
            if (++calls == baselineCalls) throw Exception("LATE_ASSOCIATION_CHECKPOINT")
            CaptureCheckpoint(version, true, 101)
        }
        rejected(stage(g = g).publish(true, true))
        assertEquals(baselineCalls, calls); assertFalse(g.isCurrent()); assertEquals(baselineCalls, calls)
        var publishedCalls = 0
        val publishedGuard = guard { publishedCalls++; CaptureCheckpoint(version, true, 101) }
        assertTrue(stage(g = publishedGuard).publish(true, true).published)
        val finishedCalls = publishedCalls
        assertFalse(publishedGuard.isCurrent()); assertEquals(finishedCalls, publishedCalls)
    }
}
