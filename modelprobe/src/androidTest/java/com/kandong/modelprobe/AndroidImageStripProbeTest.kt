package com.kandong.modelprobe

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Image planes containing only locally generated fixed colors, never screen input. */
@RunWith(AndroidJUnit4::class)
class AndroidImageStripProbeTest {
    private val version = CaptureVersion(10, 20, 30, 40, 50, 0)
    private fun checkpoint(active: Boolean = true) = CaptureCheckpoint(version, active, 100)
    private fun meta(w: Int, h: Int) = RgbaFrameMetadata(w, h, version, 100, 1000)
    private class Loop(val width: Int, val height: Int, val format: Int = PixelFormat.RGBA_8888) : AutoCloseable {
        val reader = ImageReader.newInstance(width, height, format, 2)
        private val writer = try { ImageWriter.newInstance(reader.surface, 2, format) }
            catch (e: Throwable) { reader.close(); throw e }
        private var sequence = 100L
        fun next(): Image {
            val input = writer.dequeueInputImage()
            var queued = false
            try {
                if (format == PixelFormat.RGBA_8888) {
                    val p = input.planes.single(); val b = p.buffer; val base = b.position()
                    require(p.pixelStride >= 4)
                    for (y in 0 until height) for (x in 0 until width) {
                        val i = base + y * p.rowStride + x * p.pixelStride
                        b.put(i, 231.toByte()); b.put(i + 1, y.toByte()); b.put(i + 2, x.toByte()); b.put(i + 3, 255.toByte())
                    }
                }
                input.timestamp = ++sequence // Fixture-local identity only, not a freshness clock.
                writer.queueInputImage(input); queued = true
            } finally { if (!queued) input.close() }
            val until = SystemClock.elapsedRealtime() + 3000
            while (SystemClock.elapsedRealtime() < until) {
                val output = reader.acquireNextImage()
                if (output != null) {
                    try { assertEquals(sequence, output.timestamp); return output }
                    catch (e: Throwable) { output.close(); throw e }
                }
                SystemClock.sleep(5)
            }
            error("Synthetic ImageWriter/Reader timeout")
        }
        override fun close() { try { writer.close() } finally { reader.close() } }
    }
    private fun closed(image: Image) { assertThrows(IllegalStateException::class.java) { image.width } }
    private fun verifyBytes(w: Int, top: Int, bottom: Int, bgr: ByteArray) {
        assertEquals(w * (bottom - top) * 3, bgr.size)
        val expected = ByteArray(bgr.size) { i -> when (i % 3) {
            0 -> (i / 3 % w).toByte(); 1 -> (top + i / 3 / w).toByte(); else -> 231.toByte()
        } }
        assertArrayEquals(expected, bgr)
    }
    private fun success(loop: Loop, expectedStrips: Int) {
        val image = loop.next(); val lease = AndroidRgbaFrameLease(image, meta(loop.width, loop.height))
        val output = SingleFrameStripInput.process(lease, { checkpoint() }) { strip, bgr ->
            verifyBytes(loop.width, strip.read.top, strip.read.bottom, bgr); strip.index
        }
        assertNull(output.rejection); assertEquals((0 until expectedStrips).toList(), output.values)
        closed(image)
    }

    @Test fun actualPhoneSizeImageMapsEveryPixelAcrossAllFourStrips() {
        Loop(1176, 2400).use { loop -> success(loop, 4) }
    }

    @Test fun oddWidthUsesActualPlanePaddingAndReaderSlotsAreReusable() {
        Loop(33, 35).use { loop -> repeat(3) { success(loop, 1) } }
    }

    @Test fun actualImageClosesOnEarlyCancelLateCancelAndConsumerFailure() {
        Loop(32, 33).use { loop ->
            for (mode in 0..2) {
                val image = loop.next(); val lease = AndroidRgbaFrameLease(image, meta(32, 33))
                var active = mode != 0; var consumed = 0; var held: ByteArray? = null
                val output = SingleFrameStripInput.process<Int>(lease, { checkpoint(active) }) { _, bgr ->
                    consumed++; held = bgr
                    if (mode == 2) error("synthetic consumer failure")
                    active = false; 1
                }
                assertEquals(if (mode == 2) SingleFrameStripInput.Rejection.CONSUMER_FAILED else
                    SingleFrameStripInput.Rejection.CANCELLED_OR_STALE, output.rejection)
                assertTrue(output.values.isEmpty()); assertEquals(if (mode == 0) 0 else 1, consumed)
                held?.let { assertTrue(it.all { byte -> byte == 0.toByte() }) }
                closed(image)
                success(loop, 1) // Real reader/writer slots remain usable after each failure.
            }
        }
    }

    @Test fun cropMismatchAndUnexpectedDimensionsRejectBeforeConsumer() {
        Loop(32, 33).use { loop ->
            for (crop in listOf(false, true)) {
                val image = loop.next()
                if (crop) image.cropRect = Rect(1, 0, 32, 33)
                val lease = AndroidRgbaFrameLease(image, meta(if (crop) 32 else 33, 33))
                val output = SingleFrameStripInput.process(lease, { checkpoint() }) { _, _ -> fail("consumer"); 0 }
                assertEquals(SingleFrameStripInput.Rejection.READ_FAILED, output.rejection)
                assertTrue(output.values.isEmpty()); closed(image)
            }
            success(loop, 1)
        }
    }

    @Test fun yuvFormatIsNotSilentlyTreatedAsRgba() {
        Loop(32, 32, ImageFormat.YUV_420_888).use { loop ->
            val image = loop.next(); val lease = AndroidRgbaFrameLease(image, meta(32, 32))
            val output = SingleFrameStripInput.process(lease, { checkpoint() }) { _, _ -> fail("consumer"); 0 }
            assertEquals(SingleFrameStripInput.Rejection.READ_FAILED, output.rejection)
            assertTrue(output.values.isEmpty()); closed(image)
        }
    }

    @Test fun consumedLeaseCannotReadAgainAndRepeatedCloseIsSafe() {
        Loop(32, 33).use { loop ->
            val image = loop.next(); val lease = AndroidRgbaFrameLease(image, meta(32, 33))
            assertNull(SingleFrameStripInput.process(lease, { checkpoint() }) { _, _ -> 1 }.rejection)
            closed(image); lease.close()
            assertThrows(IllegalStateException::class.java) { lease.plane() }
            assertEquals(SingleFrameStripInput.Rejection.READ_FAILED,
                SingleFrameStripInput.process(lease, { checkpoint() }) { _, _ -> fail("consumer"); 0 }.rejection)
            success(loop, 1)
        }
    }
}
