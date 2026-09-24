package com.kandong.ocrlab.context.capture

import java.util.Collections

/** Evidence is never inferred from an absent/unsupported Android API. */
enum class CaptureEvidence { YES, NO, UNKNOWN, UNAVAILABLE }
enum class CaptureWindowOwner { TARGET_APP, OWN_OVERLAY, OTHER_APP, SYSTEM, KEYBOARD, UNKNOWN }
enum class CaptureRole { TEXT, BUTTON, IMAGE, CONTAINER, UNKNOWN }
enum class CaptureLabelScope { SELF_ONLY, DESCENDANTS, UNKNOWN }
enum class CaptureOrigin { SYNTHETIC_METADATA, OWNED_ANDROID_FIXTURE }
enum class CaptureLabelSource { TEXT, DESCRIPTION, MATCHING_TEXT_AND_DESCRIPTION }
enum class CaptureCoverage { UNVERIFIED }
enum class CaptureRejection { INVALID_METADATA, TARGET_UNCONFIRMED, CANCELLED_OR_STALE }
enum class CaptureGapReason {
    WINDOW_EXCLUDED, SENSITIVE_WINDOW, WINDOW_PRIVACY_UNKNOWN,
    PASSWORD, SENSITIVE_NODE, EDITABLE, PRIVACY_UNKNOWN, NOT_VISIBLE,
    CLIPPED_OR_OFFSCREEN, OCCLUDED, TRUNCATED, ANCESTOR_EXCLUDED,
    CHILDREN_UNKNOWN, CONTAINER_LABEL, NONLOCAL_LABEL,
    READ_FAILED, EMPTY_LABEL, CONFLICTING_LABEL, INVALID_LABEL, TEXT_BUDGET,
}

/** Snapshot-local integer units; Android-backed fixtures verify their coordinate conversion separately. */
data class CaptureRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    internal fun valid() = listOf(left, top, right, bottom).all { it in -100_000..100_000 } && left < right && top < bottom
    internal fun contains(other: CaptureRect) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/** Ephemeral numeric identifiers only: never package names, resource IDs, URLs or titles. */
data class CaptureVersion(val session: Long, val snapshot: Long, val page: Long, val revision: Long, val window: Int, val display: Int)
data class CaptureWindow(
    val id: Int, val owner: CaptureWindowOwner,
    val visible: CaptureEvidence = CaptureEvidence.UNKNOWN,
    val sensitive: CaptureEvidence = CaptureEvidence.UNKNOWN,
    // A supplied classification, NOT a claimed AccessibilityWindowInfo.isSecure() API.
    val protectedContent: CaptureEvidence = CaptureEvidence.UNKNOWN,
)
data class CaptureNode(
    val id: Int, val parent: Int?, val window: Int, val order: Int,
    val original: CaptureRect, val visibleBounds: CaptureRect?, val role: CaptureRole,
    val visible: CaptureEvidence = CaptureEvidence.UNKNOWN,
    val password: CaptureEvidence = CaptureEvidence.UNKNOWN,
    val sensitive: CaptureEvidence = CaptureEvidence.UNKNOWN,
    val editable: CaptureEvidence = CaptureEvidence.UNKNOWN,
    val occluded: CaptureEvidence = CaptureEvidence.UNKNOWN,
    val truncated: CaptureEvidence = CaptureEvidence.UNKNOWN,
    val childrenComplete: CaptureEvidence = CaptureEvidence.UNKNOWN,
    val labelScope: CaptureLabelScope = CaptureLabelScope.UNKNOWN,
)
data class CaptureMetadata(
    val version: CaptureVersion, val width: Int, val height: Int,
    val capturedAtMillis: Long, val ttlMillis: Long,
    val windowsComplete: CaptureEvidence, val nodesComplete: CaptureEvidence,
    val windows: List<CaptureWindow>, val nodes: List<CaptureNode>,
    val origin: CaptureOrigin = CaptureOrigin.SYNTHETIC_METADATA,
)

/** Supplied by the owner of the explicit-click ticket, using elapsed realtime, not wall time.
 * The owner must revoke active on page/window changes, menu, pause, stop or permission loss.
 */
data class CaptureCheckpoint(val version: CaptureVersion, val active: Boolean, val nowMillis: Long)

/** Only the deferred reader may obtain label contents, after metadata filtering. */
class CaptureLabel(val text: String?, val description: String?) {
    override fun toString() = "CaptureLabel(redacted)"
}
class LocalCaptureBlock internal constructor(
    val node: Int, val parent: Int?, val window: Int, val order: Int, val role: CaptureRole,
    val original: CaptureRect, val visibleBounds: CaptureRect,
    val text: String, val labelSource: CaptureLabelSource,
) {
    override fun toString() = "LocalCaptureBlock(node=$node, text=redacted)"
}
data class CaptureGap(val node: Int, val reason: CaptureGapReason)

/** Local inspection only. Deliberately NOT a ScreenSnapshot or a translation/network request.
 * No semantic grouping, language, consent, or completeness claim is manufactured here.
 */
class LocalCaptureInspection internal constructor(
    val version: CaptureVersion,
    val rejection: CaptureRejection?,
    blocks: List<LocalCaptureBlock>, gaps: List<CaptureGap>,
    val enumeration: CaptureEvidence,
    val origin: CaptureOrigin = CaptureOrigin.SYNTHETIC_METADATA,
) {
    val blocks: List<LocalCaptureBlock> = Collections.unmodifiableList(ArrayList(blocks))
    val gaps: List<CaptureGap> = Collections.unmodifiableList(ArrayList(gaps))
    val coverage = CaptureCoverage.UNVERIFIED
    override fun toString() = "LocalCaptureInspection(rejection=$rejection, blocks=${blocks.size}, gaps=${gaps.size}, coverage=$coverage)"
}

/** Single-owner synchronous filter. No Android access, persistence, network, or retained text.
 * The adapter must bind the deferred reader to the same metadata snapshot. It must never prefetch
 * labels in metadata traversal. A revocation while a read is running discards the entire result.
 */
object LocalCapturePrivacy {
    const val MAX_NODES = 512
    const val MAX_LABEL_CHARS = 2_000
    const val MAX_TOTAL_CHARS = 16_000

    fun inspect(
        metadata: CaptureMetadata,
        checkpoint: () -> CaptureCheckpoint,
        readLabel: (Int) -> CaptureLabel,
    ): LocalCaptureInspection {
        fun rejected(reason: CaptureRejection) = LocalCaptureInspection(metadata.version, reason,
            emptyList(), emptyList(), metadata.nodesComplete, metadata.origin)
        // Freeze bounded collections BEFORE invoking caller callbacks. Nodes/windows contain no labels.
        if (metadata.nodes.size > MAX_NODES || metadata.windows.size !in 1..16) return rejected(CaptureRejection.INVALID_METADATA)
        val input = metadata.copy(nodes = metadata.nodes.toList(), windows = metadata.windows.toList())
        if (!validMetadata(input)) return rejected(CaptureRejection.INVALID_METADATA)
        if (input.windowsComplete != CaptureEvidence.YES ||
            input.windows.count { it.owner == CaptureWindowOwner.TARGET_APP } != 1 ||
            input.windows.none { it.id == input.version.window && it.owner == CaptureWindowOwner.TARGET_APP }) {
            return rejected(CaptureRejection.TARGET_UNCONFIRMED)
        }
        var lastNow = input.capturedAtMillis
        fun current(): Boolean {
            val c = try { checkpoint() } catch (_: RuntimeException) { return false }
            if (!c.active || c.version != input.version || c.nowMillis < lastNow ||
                c.nowMillis - input.capturedAtMillis >= input.ttlMillis) return false
            lastNow = c.nowMillis
            return true
        }
        if (!current()) return rejected(CaptureRejection.CANCELLED_OR_STALE)
        val windows = input.windows.associateBy { it.id }
        val nodes = input.nodes.associateBy { it.id }
        val parents = input.nodes.mapNotNull { it.parent }.toSet()
        val screen = CaptureRect(0, 0, input.width, input.height)
        val boundaries = input.nodes.associate { it.id to boundary(it, windows.getValue(it.window), screen) }
        val blocks = ArrayList<LocalCaptureBlock>()
        val gaps = ArrayList<CaptureGap>()
        var totalChars = 0
        for (node in input.nodes.sortedBy { it.order }) {
            if (!current()) return rejected(CaptureRejection.CANCELLED_OR_STALE)
            var parent = node.parent
            var excludedAncestor = false
            while (parent != null) {
                if (boundaries[parent] != null) excludedAncestor = true
                parent = nodes.getValue(parent).parent
            }
            val reason = boundaries[node.id] ?: when {
                excludedAncestor -> CaptureGapReason.ANCESTOR_EXCLUDED
                node.childrenComplete != CaptureEvidence.YES -> CaptureGapReason.CHILDREN_UNKNOWN
                node.id in parents || node.role == CaptureRole.CONTAINER -> CaptureGapReason.CONTAINER_LABEL
                node.labelScope != CaptureLabelScope.SELF_ONLY -> CaptureGapReason.NONLOCAL_LABEL
                totalChars >= MAX_TOTAL_CHARS -> CaptureGapReason.TEXT_BUDGET
                else -> null
            }
            if (reason != null) { gaps += CaptureGap(node.id, reason); continue }
            val label = try { readLabel(node.id) } catch (_: RuntimeException) { null }
            // A read can trigger cancellation/re-entrancy. Never publish even the earlier partial result.
            if (!current()) return rejected(CaptureRejection.CANCELLED_OR_STALE)
            if (label == null) { gaps += CaptureGap(node.id, CaptureGapReason.READ_FAILED); continue }
            if (!validLabel(label.text) || !validLabel(label.description)) {
                gaps += CaptureGap(node.id, CaptureGapReason.INVALID_LABEL); continue
            }
            val text = label.text?.takeUnless { it.isBlank() }
            val description = label.description?.takeUnless { it.isBlank() }
            if (text != null && description != null && text != description) {
                gaps += CaptureGap(node.id, CaptureGapReason.CONFLICTING_LABEL); continue
            }
            val value = text ?: description
            if (value == null) { gaps += CaptureGap(node.id, CaptureGapReason.EMPTY_LABEL); continue }
            if (value.length > MAX_TOTAL_CHARS - totalChars) {
                gaps += CaptureGap(node.id, CaptureGapReason.TEXT_BUDGET); continue
            }
            val source = when {
                text != null && description != null -> CaptureLabelSource.MATCHING_TEXT_AND_DESCRIPTION
                text != null -> CaptureLabelSource.TEXT
                else -> CaptureLabelSource.DESCRIPTION
            }
            blocks += LocalCaptureBlock(node.id, node.parent, node.window, node.order, node.role,
                node.original, node.visibleBounds!!, value, source)
            totalChars += value.length
        }
        if (!current()) return rejected(CaptureRejection.CANCELLED_OR_STALE)
        return LocalCaptureInspection(input.version, null, blocks, gaps, input.nodesComplete, input.origin)
    }

    private fun validMetadata(p: CaptureMetadata): Boolean {
        val v = p.version
        if (listOf(v.session, v.snapshot, v.page, v.revision).any { it < 0 } || v.window < 0 || v.display < 0 ||
            p.width !in 1..10_000 || p.height !in 1..10_000 || p.capturedAtMillis < 0 || p.ttlMillis !in 1..60_000) return false
        val windows = p.windows.associateBy { it.id }
        val nodes = p.nodes.associateBy { it.id }
        if (windows.size != p.windows.size || windows.keys.any { it < 0 } || nodes.size != p.nodes.size ||
            p.nodes.map { it.order }.toSet().size != p.nodes.size) return false
        for (node in p.nodes) {
            if (node.id < 0 || node.order < 0 || node.window !in windows || !node.original.valid() ||
                node.visibleBounds?.let { !it.valid() || !node.original.contains(it) } == true) return false
            val visited = HashSet<Int>()
            var next: CaptureNode? = node
            while (next != null) {
                if (!visited.add(next.id) || next.window != node.window) return false
                next = next.parent?.let { nodes[it] ?: return false }
            }
        }
        return true
    }

    /** Only metadata that describes visibility/privacy propagates to descendants. Label aggregation
     * is handled separately: rejecting a container's own label must not discard safe sibling leaves.
     */
    private fun boundary(n: CaptureNode, w: CaptureWindow, screen: CaptureRect): CaptureGapReason? = when {
        w.owner != CaptureWindowOwner.TARGET_APP -> CaptureGapReason.WINDOW_EXCLUDED
        w.sensitive == CaptureEvidence.YES || w.protectedContent == CaptureEvidence.YES -> CaptureGapReason.SENSITIVE_WINDOW
        w.sensitive != CaptureEvidence.NO || w.protectedContent != CaptureEvidence.NO -> CaptureGapReason.WINDOW_PRIVACY_UNKNOWN
        w.visible != CaptureEvidence.YES -> CaptureGapReason.NOT_VISIBLE
        n.password == CaptureEvidence.YES -> CaptureGapReason.PASSWORD
        n.sensitive == CaptureEvidence.YES -> CaptureGapReason.SENSITIVE_NODE
        n.editable == CaptureEvidence.YES -> CaptureGapReason.EDITABLE
        n.password != CaptureEvidence.NO || n.sensitive != CaptureEvidence.NO || n.editable != CaptureEvidence.NO -> CaptureGapReason.PRIVACY_UNKNOWN
        n.visible != CaptureEvidence.YES -> CaptureGapReason.NOT_VISIBLE
        n.visibleBounds != n.original || !screen.contains(n.original) -> CaptureGapReason.CLIPPED_OR_OFFSCREEN
        n.occluded != CaptureEvidence.NO -> CaptureGapReason.OCCLUDED
        n.truncated != CaptureEvidence.NO -> CaptureGapReason.TRUNCATED
        else -> null
    }

    private fun validLabel(value: String?): Boolean {
        if (value == null) return true
        if (value.length > MAX_LABEL_CHARS) return false
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (c.isISOControl() && c !in "\n\r\t" || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069') return false
            if (c.isHighSurrogate()) {
                if (index >= value.length || !value[index++].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) return false
        }
        return true
    }
}
