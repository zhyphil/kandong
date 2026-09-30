package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import java.util.concurrent.CancellationException

/**
 * Test-only, confined to the constructing thread, including producer callbacks and handoffs.
 * Observations contain metadata only. Explicit clicks authorize work; render never reads content.
 * A synthetic stage proves internal validation, not current real pixels, privacy or consent.
 * Invalidation drops owned references; immutable snapshots already held by callers remain theirs.
 * This is neither heap erasure nor authority to reuse an archived snapshot as a current page.
 */
internal class FullPageRegionController(private val source: () -> CaptureCheckpoint) {
    class Ticket internal constructor()
    /** All rectangles are source-pixel BOX envelopes, except mirrorRect in mirror pixels.
     * conservative marks rotated/unordered quads, never a claim of exact ink intersection. */
    data class Projection(val candidateId: String, val groupId: String,
        val sourceEnvelope: ContextRect, val sourceIntersection: ContextRect,
        val mirrorRect: ContextRect, val conservative: Boolean)
    data class Frame(val metadata: RgbaFrameMetadata, val page: FullPageOcrAssociation.Result,
        val roi: ContextRect, val transform: MirrorTransform, val selectedIds: List<String>,
        val visible: List<Projection>, val contextIds: List<String>, val unsupportedIds: List<String>)
    private class Authorization(val ticket: Ticket, val version: CaptureVersion, val clickedAt: Long) {
        var pending = true
        var lease: FullPageOcrPublication.RegionLease? = null
    }
    private val owner = Thread.currentThread()
    private var observed: CaptureVersion? = null
    private var authorization: Authorization? = null
    private var epoch = 0L
    private var lastNow: Long? = null // Intentionally persists across all clear reasons.
    private var clicking = false
    private fun onOwner() = check(Thread.currentThread() === owner) { "REGION_OWNER_THREAD" }
    private fun valid(v: CaptureVersion) = v.session >= 0 && v.snapshot >= 0 && v.page >= 0 &&
        v.revision >= 0 && v.window >= 0 && v.display >= 0
    private fun revoke() {
        val old = authorization
        authorization = null
        epoch++
        old?.lease?.close()
        old?.lease = null
    }
    private fun same(e: Long, a: Authorization?, v: CaptureVersion) =
        epoch == e && authorization === a && observed == v

    /** No stale callback may advance the clock or invalidate a replacement made by reentrancy. */
    private fun tick(e: Long, a: Authorization?, v: CaptureVersion): CaptureCheckpoint? {
        if (!same(e, a, v)) return null
        val c = try { source() } catch (_: Exception) {
            if (same(e, a, v)) revoke()
            return null
        }
        if (!same(e, a, v)) return null
        if (!valid(v) || !c.active || c.version != v || c.nowMillis < 0 ||
            lastNow?.let { c.nowMillis < it } == true) {
            revoke()
            return null
        }
        lastNow = c.nowMillis
        return c
    }
    fun observePage(version: CaptureVersion) {
        onOwner()
        if (observed != version) { revoke(); observed = version }
    }
    fun click(): Ticket? {
        onOwner()
        if (clicking || authorization?.pending == true) return null
        // A new explicit attempt revokes the displayed page even if the attempt fails.
        if (authorization != null) revoke()
        val version = observed ?: return null
        val e = epoch
        clicking = true
        try {
            val c = tick(e, null, version) ?: return null
            if (!same(e, null, version)) return null
            val ticket = Ticket()
            authorization = Authorization(ticket, version, c.nowMillis)
            return ticket
        } finally { clicking = false }
    }
    fun checkpoint(ticket: Ticket): CaptureCheckpoint {
        onOwner()
        val a = authorization
        if (a == null || a.ticket !== ticket || (!a.pending && a.lease == null))
            throw CancellationException("REGION_TICKET_REVOKED")
        return tick(epoch, a, a.version) ?: throw CancellationException("REGION_TICKET_REVOKED")
    }
    fun accept(ticket: Ticket, permit: FullPageOcrPublication.RegionPermit?): Boolean {
        onOwner()
        val a = authorization
        // Including duplicate null/success callbacks for the currently displayed ticket.
        // close() only releases an unclaimed permit; transferred aliases no longer own anything.
        if (a == null || a.ticket !== ticket || !a.pending) { permit?.close(); return false }
        val e = epoch
        val lease = permit?.let(FullPageOcrPublication::takeRegion)
        if (lease == null) { permit?.close(); revoke(); return false }
        a.pending = false
        a.lease = lease
        val meta = lease.metadata
        if (meta.version != a.version || meta.acquiredAtMillis < a.clickedAt) {
            revoke(); return false
        }
        return current(e, a, lease)
    }
    /** Check both the controller source and the SAME producer Guard, including its old lastNow. */
    private fun current(e: Long, a: Authorization, lease: FullPageOcrPublication.RegionLease): Boolean {
        if (!same(e, a, a.version) || a.lease !== lease) return false
        val c = tick(e, a, a.version) ?: return false
        if (lease.metadata.acquiredAtMillis > c.nowMillis) { revoke(); return false }
        val guarded = try { lease.guard.checkpoint() } catch (_: Exception) {
            if (same(e, a, a.version) && a.lease === lease) revoke()
            return false
        }
        // The guard's callback may have paused, observed a new page, or accepted another lease.
        if (!same(e, a, a.version) || a.lease !== lease) return false
        if (guarded.nowMillis < checkNotNull(lastNow)) { revoke(); return false }
        lastNow = guarded.nowMillis
        return true
    }
    fun clear(@Suppress("UNUSED_PARAMETER") reason: ClearReason) { onOwner(); revoke() }
    fun render(roi: ContextRect, transform: MirrorTransform): Frame? {
        onOwner()
        val a = authorization ?: return null
        val lease = a.lease ?: return null
        val e = epoch
        if (!current(e, a, lease)) return null
        val result = try {
            FullPageRegionProjection.project(lease.metadata, lease.page, roi, transform) {
                if (!current(e, a, lease)) throw CancellationException("REGION_REVOKED")
            }
        } catch (_: IllegalArgumentException) { null }
          catch (_: CancellationException) { null }
        if (result == null || !current(e, a, lease)) return null
        // No callbacks or mutable evidence escape between this final identity check and return.
        return result.takeIf { same(e, a, a.version) && a.lease === lease }
    }
}
