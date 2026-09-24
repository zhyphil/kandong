package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import java.nio.ByteBuffer
import java.util.Collections

/** Test-only exclusive, synchronous lease; an Android Image adapter is not implemented here.
 * Metadata, plane contents and its layout must remain unchanged until close. Transfer ownership
 * exactly once to process(). The caller must authenticate source/current-frame/privacy separately.
 */
internal interface RgbaFrameLease : AutoCloseable {
    val metadata: RgbaFrameMetadata
    fun plane(): RgbaPlane
}
/** Acquisition time uses the checkpoint's monotonic clock, NOT Image.timestamp or wall time.
 * A fresh acquisition time alone does not establish that the pixels are a current page.
 */
internal data class RgbaFrameMetadata(val width: Int, val height: Int, val version: CaptureVersion,
    val acquiredAtMillis: Long, val ttlMillis: Long)
internal class RgbaPlane(val bytes: ByteBuffer, val rowStride: Int, val pixelStride: Int)

internal object SingleFrameStripInput {
    const val MAX_PLANE_BYTES = 32 * 1024 * 1024
    enum class Rejection { INVALID_INPUT, CANCELLED_OR_STALE, READ_FAILED, CONSUMER_FAILED, CLOSE_FAILED }
    class Result<T> internal constructor(val version: CaptureVersion?, val rejection: Rejection?, values: List<T>) {
        val values: List<T> = Collections.unmodifiableList(ArrayList(values))
        override fun toString() = "FrameStripResult(rejection=$rejection, count=${values.size})"
    }
    private class Rejected(val reason: Rejection) : RuntimeException(reason.name)
    private fun requireInput(ok: Boolean) { if (!ok) throw Rejected(Rejection.INVALID_INPUT) }

    /** Only one non-wiped BGR strip is exposed at a time; JVM reclamation is not a heap bound.
     * consume is synchronous: it must neither retain
     * pixels nor publish partial results, and it owns cleanup of its model/native resources.
     * BGR is wiped on return/throw; returned results only commit after the whole frame and close.
     * No privacy, consent, source authenticity or semantic completeness is inferred here.
     */
    fun <T> process(frame: RgbaFrameLease, checkpoint: () -> CaptureCheckpoint,
        consume: (FullPageStripPlanner.Strip, ByteArray) -> T): Result<T> {
        var version: CaptureVersion? = null
        var current: (() -> Boolean)? = null
        var closeFailed = false
        val result: Result<T> = try {
            val m = frame.metadata
            version = m.version
            requireInput(listOf(m.version.session, m.version.snapshot, m.version.page, m.version.revision).all { it >= 0 } &&
                m.version.window >= 0 && m.version.display >= 0 && m.acquiredAtMillis >= 0 && m.ttlMillis in 1..60_000)
            val plan = try { FullPageStripPlanner.plan(m.width, m.height) }
                catch (_: IllegalArgumentException) { throw Rejected(Rejection.INVALID_INPUT) }
            var lastNow = m.acquiredAtMillis
            current = {
                try {
                    val c = checkpoint()
                    if (!c.active || c.version != m.version || c.nowMillis < lastNow ||
                        c.nowMillis - m.acquiredAtMillis >= m.ttlMillis) false
                    else { lastNow = c.nowMillis; true }
                } catch (_: RuntimeException) { false }
            }
            fun checkCurrent() { if (!current()) throw Rejected(Rejection.CANCELLED_OR_STALE) }
            checkCurrent()
            val plane = frame.plane()
            checkCurrent()
            val bytes = plane.bytes.asReadOnlyBuffer().slice() // Preserve caller position/limit/order.
            val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
            requireInput(pixelStride in 4..16)
            val rowBytes = (m.width - 1L) * pixelStride + 4
            val span = (m.height - 1L) * rowStride + rowBytes
            requireInput(rowStride.toLong() >= rowBytes && span in 1..MAX_PLANE_BYTES.toLong() &&
                bytes.remaining() <= MAX_PLANE_BYTES && span <= bytes.remaining())
            val values = ArrayList<T>()
            for (strip in plan.strips) {
                checkCurrent()
                val bgr = ByteArray(strip.read.width * strip.read.height * 3)
                try {
                    var dst = 0
                    for (y in strip.read.top until strip.read.bottom) {
                        checkCurrent()
                        for (x in 0 until m.width) {
                            // Validated span <=32MiB, so all products and offsets fit Int.
                            val src = y * rowStride + x * pixelStride
                            requireInput(bytes.get(src + 3).toInt() and 255 == 255)
                            bgr[dst++] = bytes.get(src + 2)
                            bgr[dst++] = bytes.get(src + 1)
                            bgr[dst++] = bytes.get(src)
                        }
                    }
                    checkCurrent()
                    val value = try { consume(strip, bgr) }
                        catch (_: RuntimeException) { throw Rejected(Rejection.CONSUMER_FAILED) }
                    checkCurrent()
                    values += value
                } finally { bgr.fill(0) }
            }
            checkCurrent()
            Result(version, null, values)
        } catch (e: Rejected) {
            Result(version, e.reason, emptyList())
        } catch (_: RuntimeException) {
            Result(version, Rejection.READ_FAILED, emptyList())
        } finally {
            try { frame.close() } catch (_: RuntimeException) { closeFailed = true }
        }
        if (closeFailed) return Result(version, Rejection.CLOSE_FAILED, emptyList())
        if (result.rejection == null && current?.invoke() != true)
            return Result(version, Rejection.CANCELLED_OR_STALE, emptyList())
        return result
    }
}
