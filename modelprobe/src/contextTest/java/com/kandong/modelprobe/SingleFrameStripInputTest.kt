package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SingleFrameStripInputTest {
    private val version = CaptureVersion(1, 2, 3, 4, 5, 0)
    private fun meta(w: Int = 32, h: Int = 33) = RgbaFrameMetadata(w, h, version, 100, 1000)
    private fun checkpoint(v: CaptureVersion = version, active: Boolean = true, now: Long = 100) =
        CaptureCheckpoint(v, active, now)
    private class Lease(override val metadata: RgbaFrameMetadata, val source: () -> RgbaPlane,
        val closing: () -> Unit = {}) : RgbaFrameLease {
        var reads = 0; var closes = 0
        override fun plane(): RgbaPlane { reads++; return source() }
        override fun close() { closes++; closing() }
    }
    private fun plane(w: Int = 32, h: Int = 33, pixelStride: Int = 4, padding: Int = 0,
        prefix: Int = 0, direct: Boolean = false): RgbaPlane {
        val stride = w * pixelStride + padding
        val end = prefix + (h - 1) * stride + (w - 1) * pixelStride + 4
        val b = (if (direct) ByteBuffer.allocateDirect(end + 9) else ByteBuffer.allocate(end + 9))
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until b.capacity()) b.put(i, 0x66)
        for (y in 0 until h) for (x in 0 until w) {
            val i = prefix + y * stride + x * pixelStride
            b.put(i, 7); b.put(i + 1, y.toByte()); b.put(i + 2, x.toByte()); b.put(i + 3, 255.toByte())
        }
        b.position(prefix); b.limit(end)
        return RgbaPlane(b, stride, pixelStride)
    }
    private fun expected(w: Int, top: Int, bottom: Int): ByteArray =
        ByteArray(w * (bottom - top) * 3) { i -> when (i % 3) {
            0 -> (i / 3 % w).toByte(); 1 -> (top + i / 3 / w).toByte(); else -> 7
        } }
    private fun rejected(reason: SingleFrameStripInput.Rejection, result: SingleFrameStripInput.Result<*>, lease: Lease) {
        assertEquals(reason, result.rejection); assertTrue(result.values.isEmpty()); assertEquals(1, lease.closes)
    }

    @Test fun paddedRgbaUsesPositionLimitAndBothStridesWithoutChangingCursors() {
        for (direct in listOf(false, true)) for (pixelStride in listOf(4, 8)) {
            val raw = plane(pixelStride = pixelStride, padding = 13, prefix = 7, direct = direct)
            val p = RgbaPlane(raw.bytes.asReadOnlyBuffer(), raw.rowStride, raw.pixelStride)
            val beforePosition = p.bytes.position(); val beforeLimit = p.bytes.limit()
            val lease = Lease(meta(), { p }); var held: ByteArray? = null
            val result = SingleFrameStripInput.process(lease, { checkpoint() }) { s, bytes ->
                held = bytes; assertArrayEquals(expected(32, 0, 33), bytes)
                assertEquals(0, s.index); "fixed synthetic result"
            }
            assertNull(result.rejection); assertEquals(listOf("fixed synthetic result"), result.values)
            assertEquals(version, result.version); assertEquals(1, lease.reads); assertEquals(1, lease.closes)
            assertEquals(beforePosition, p.bytes.position()); assertEquals(beforeLimit, p.bytes.limit())
            assertTrue(held!!.all { it == 0.toByte() })
            assertFalse(result.toString().contains("fixed synthetic result"))
            assertThrows(UnsupportedOperationException::class.java) { (result.values as MutableList).clear() }
        }
    }

    @Test fun phoneFrameUsesOnePlaneAndOneLiveStripWithEveryOriginalPixelAndHalo() {
        val lease = Lease(meta(1176, 2400), { plane(1176, 2400, padding = 32) })
        var previous: ByteArray? = null
        val result = SingleFrameStripInput.process(lease, { checkpoint() }) { s, bytes ->
            previous?.let { assertTrue(it.all { b -> b == 0.toByte() }) }
            assertEquals(0, lease.closes); assertEquals(1, lease.reads)
            assertTrue(bytes.size <= 3 * DetectorPacking.MAX_PIXELS)
            assertArrayEquals(expected(1176, s.read.top, s.read.bottom), bytes)
            previous = bytes; s.index
        }
        assertNull(result.rejection); assertEquals(listOf(0, 1, 2, 3), result.values)
        assertEquals(1, lease.reads); assertEquals(1, lease.closes)
        assertTrue(previous!!.all { it == 0.toByte() })
    }

    @Test fun inactiveTicketDoesNotReadAnyPixels() {
        val lease = Lease(meta(), { error("must not read") })
        val result = SingleFrameStripInput.process(lease, { checkpoint(active = false) }) { _, _ -> fail("consumer"); 0 }
        rejected(SingleFrameStripInput.Rejection.CANCELLED_OR_STALE, result, lease); assertEquals(0, lease.reads)
    }

    @Test fun everyVersionComponentInvalidatesTheWholeFrame() {
        for (v in listOf(version.copy(session = 9), version.copy(snapshot = 9), version.copy(page = 9),
            version.copy(revision = 9), version.copy(window = 9), version.copy(display = 9))) {
            val lease = Lease(meta(), { error("must not read") })
            rejected(SingleFrameStripInput.Rejection.CANCELLED_OR_STALE,
                SingleFrameStripInput.process(lease, { checkpoint(v) }) { _, _ -> 0 }, lease)
            assertEquals(0, lease.reads)
        }
    }

    @Test fun cancelDuringPlaneAcquisitionDoesNotCallConsumer() {
        var active = true
        val lease = Lease(meta(), { plane().also { active = false } })
        rejected(SingleFrameStripInput.Rejection.CANCELLED_OR_STALE,
            SingleFrameStripInput.process(lease, { checkpoint(active = active) }) { _, _ -> fail("consumer"); 0 }, lease)
        assertEquals(1, lease.reads)
    }

    @Test fun checkpointDuringRowsStopsBeforeConsumingIncompleteStrip() {
        var calls = 0
        val lease = Lease(meta(), { plane() })
        rejected(SingleFrameStripInput.Rejection.CANCELLED_OR_STALE,
            SingleFrameStripInput.process(lease, { checkpoint(active = ++calls < 8) }) { _, _ -> fail("consumer"); 0 }, lease)
        assertTrue(calls >= 8)
    }

    @Test fun cancellationAfterOneConsumerDropsPartialResultsAndWipesBytes() {
        var active = true; var count = 0; var held: ByteArray? = null
        val lease = Lease(meta(1176, 2400), { plane(1176, 2400) })
        val result = SingleFrameStripInput.process(lease, { checkpoint(active = active) }) { _, bytes ->
            count++; held = bytes; active = false; "must never be returned"
        }
        rejected(SingleFrameStripInput.Rejection.CANCELLED_OR_STALE, result, lease)
        assertEquals(1, count); assertTrue(held!!.all { it == 0.toByte() })
    }

    @Test fun expiredFutureOrReversedClockRejectsResults() {
        for (now in listOf(99L, 1100L, Long.MAX_VALUE)) {
            val lease = Lease(meta(), { error("must not read") })
            rejected(SingleFrameStripInput.Rejection.CANCELLED_OR_STALE,
                SingleFrameStripInput.process(lease, { checkpoint(now = now) }) { _, _ -> 0 }, lease)
        }
        for (next in listOf(199L, 1100L)) {
            var now = 200L
            val lease = Lease(meta(), { plane() })
            rejected(SingleFrameStripInput.Rejection.CANCELLED_OR_STALE,
                SingleFrameStripInput.process(lease, { checkpoint(now = now) }) { _, _ -> now = next; 0 }, lease)
        }
    }

    @Test fun consumerFailureDoesNotExposePartialResultsAndStillWipesAndCloses() {
        var held: ByteArray? = null
        val lease = Lease(meta(), { plane() })
        rejected(SingleFrameStripInput.Rejection.CONSUMER_FAILED,
            SingleFrameStripInput.process<Int>(lease, { checkpoint() }) { _, bytes ->
                held = bytes; error("private synthetic text")
            }, lease)
        assertTrue(held!!.all { it == 0.toByte() })
    }

    @Test fun readerOrCheckpointFailureClosesExactlyOnce() {
        val reader = Lease(meta(), { error("read failure") })
        rejected(SingleFrameStripInput.Rejection.READ_FAILED,
            SingleFrameStripInput.process(reader, { checkpoint() }) { _, _ -> 0 }, reader)
        val check = Lease(meta(), { plane() })
        rejected(SingleFrameStripInput.Rejection.CANCELLED_OR_STALE,
            SingleFrameStripInput.process(check, { error("checkpoint failure") }) { _, _ -> 0 }, check)
        assertEquals(0, check.reads)
    }

    @Test fun badFrameGeometryOrMetadataRejectsBeforePlaneAccess() {
        for (m in listOf(meta(2400, 1176), meta(2048, 4096), meta(Int.MAX_VALUE, Int.MAX_VALUE),
            meta().copy(acquiredAtMillis = -1), meta().copy(ttlMillis = 0), meta().copy(ttlMillis = 60_001),
            meta().copy(version = version.copy(snapshot = -1)))) {
            val lease = Lease(m, { error("must not read") })
            rejected(SingleFrameStripInput.Rejection.INVALID_INPUT,
                SingleFrameStripInput.process(lease, { checkpoint() }) { _, _ -> 0 }, lease)
            assertEquals(0, lease.reads)
        }
    }

    @Test fun invalidPlaneBoundsAndStrideProductsRejectWithoutConsumer() {
        val p = plane()
        val shorter = p.bytes.duplicate().apply { limit(limit() - 1) }
        for (bad in listOf(RgbaPlane(p.bytes, 128, 3), RgbaPlane(p.bytes, 128, 17),
            RgbaPlane(p.bytes, -1, 4), RgbaPlane(p.bytes, 127, 4),
            RgbaPlane(p.bytes, Int.MAX_VALUE, 4), RgbaPlane(p.bytes, 1_048_576, 4),
            RgbaPlane(shorter, p.rowStride, p.pixelStride))) {
            val lease = Lease(meta(), { bad })
            rejected(SingleFrameStripInput.Rejection.INVALID_INPUT,
                SingleFrameStripInput.process(lease, { checkpoint() }) { _, _ -> fail("consumer"); 0 }, lease)
        }
    }

    @Test fun nonOpaquePixelsRejectTheStripInsteadOfGuessingColor() {
        for (alpha in listOf(0, 128, 254)) {
            val p = plane(); p.bytes.put(p.bytes.limit() - 1, alpha.toByte())
            val lease = Lease(meta(), { p })
            rejected(SingleFrameStripInput.Rejection.INVALID_INPUT,
                SingleFrameStripInput.process(lease, { checkpoint() }) { _, _ -> fail("consumer"); 0 }, lease)
        }
    }

    @Test fun closeFailureOrCloseTimeInvalidationDoesNotPublishSuccess() {
        val failure = Lease(meta(), { plane() }, { error("close failure") })
        rejected(SingleFrameStripInput.Rejection.CLOSE_FAILED,
            SingleFrameStripInput.process(failure, { checkpoint() }) { _, _ -> 0 }, failure)
        var active = true
        val stale = Lease(meta(), { plane() }, { active = false })
        rejected(SingleFrameStripInput.Rejection.CANCELLED_OR_STALE,
            SingleFrameStripInput.process(stale, { checkpoint(active = active) }) { _, _ -> 0 }, stale)
    }
}
