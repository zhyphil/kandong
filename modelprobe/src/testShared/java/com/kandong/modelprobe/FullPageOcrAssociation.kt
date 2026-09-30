package com.kandong.modelprobe

import com.kandong.ocrlab.context.capture.CaptureVersion
import java.util.Collections
import kotlin.math.abs

/**
 * Test-only association of caller-supplied OCR evidence; no recognition or text correction.
 * The pinned envelope checks consistency, not retrospective proof of a candidate's model origin.
 * isCurrent must come from the future caller's lifecycle guard: this code authenticates neither
 * live capture privacy nor TTL. It checks the guard through the final publication boundary only.
 * Thresholds are frozen diagnostic choices, not calibrated OCR quality acceptance criteria.
 */
internal object FullPageOcrAssociation {
    enum class UpstreamState { COMPLETE, INCOMPLETE, CANCELLED, STALE, FAILED }
    enum class Rejection { UPSTREAM_INCOMPLETE, UPSTREAM_CANCELLED, UPSTREAM_STALE, UPSTREAM_FAILED,
        NOT_CURRENT, INVALID_IDENTITY, INVALID_PLAN, INVALID_RECEIPTS, BUDGET_EXCEEDED,
        IDENTITY_MISMATCH, DUPLICATE_ID, INVALID_METADATA, INVALID_GEOMETRY }
    enum class EdgeKind { STRONG, POSSIBLE_CLIPPED }
    enum class Text { SINGLE, IDENTICAL_NONEMPTY, ALL_EMPTY, DIFFERENT_RAW }
    enum class Geometry { ISOLATED, UNAMBIGUOUS_PAIR, UNCERTAIN }
    enum class Reason { UNSUPPORTED_GEOMETRY, NON_AXIS_ALIGNED, POSSIBLE_CLIP, SAME_STRIP_OVERLAP,
        MULTIPLE_MATCHES, SAME_STRIP_MULTIPLE, TRANSITIVE_CHAIN, HAS_EMPTY }
    data class PageIdentity(val sourceBatch: String, val version: CaptureVersion, val pageFixtureId: String,
        val model: FullPageOcrContract.Model, val detectorSha: String, val dictionarySha: String)
    data class StripReceipt(val index: Int, val read: FullPageStripPlanner.Rect,
        val core: FullPageStripPlanner.Rect, val complete: Boolean, val candidateCount: Int)
    data class Edge(val leftId: String, val rightId: String, val kind: EdgeKind)
    data class Group(val id: String, val memberIds: List<String>, val text: Text,
        val geometry: Geometry, val reasons: List<Reason>, val agreedRaw: String?)
    data class Result(val published: Boolean, val rejection: Rejection?, val identity: PageIdentity?,
        val rawCandidates: List<FullPageOcrContract.Candidate>, val edges: List<Edge>, val groups: List<Group>)

    private const val COORDINATE_TOLERANCE = 1e-6
    private const val STRONG_FRACTION = .80
    private const val CLIPPED_FRACTION = .50
    private const val EDGE_DISTANCE = 2.0
    private const val DETECTOR_SHA = "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
    private val dictionaryShas = mapOf(
        "ch" to "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af",
        "latin" to "d80d308e9759b3ea3aaf6e57fa51b39ef0a2eeb3145a9b23df819f0f0279af44")
    private val pagePattern = Regex("[a-z0-9-]{1,64}")

    private class Abort(val rejection: Rejection) : RuntimeException()
    private fun requireInput(valid: Boolean, reason: Rejection) {
        if (!valid) throw Abort(reason)
    }
    private fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private fun rejected(reason: Rejection) = Result(false, reason, null,
        frozen(emptyList()), frozen(emptyList()), frozen(emptyList()))

    fun associate(identity: PageIdentity, plan: FullPageStripPlanner.Plan, upstreamState: UpstreamState,
        stripReceipts: List<StripReceipt>, candidates: List<FullPageOcrContract.Candidate>,
        isCurrent: () -> Boolean): Result {
        fun current() {
            val valid = try { isCurrent() } catch (_: Exception) { false }
            requireInput(valid, Rejection.NOT_CURRENT)
        }
        return try {
            current()
            when (upstreamState) {
                UpstreamState.COMPLETE -> Unit
                UpstreamState.INCOMPLETE -> throw Abort(Rejection.UPSTREAM_INCOMPLETE)
                UpstreamState.CANCELLED -> throw Abort(Rejection.UPSTREAM_CANCELLED)
                UpstreamState.STALE -> throw Abort(Rejection.UPSTREAM_STALE)
                UpstreamState.FAILED -> throw Abort(Rejection.UPSTREAM_FAILED)
            }
            requireInput(identity.sourceBatch.isNotEmpty() && identity.sourceBatch.length <= 160 &&
                pagePattern.matches(identity.pageFixtureId) && identity.model in FullPageOcrContract.models &&
                identity.detectorSha == DETECTOR_SHA && dictionaryShas[identity.model.id] == identity.dictionarySha,
                Rejection.INVALID_IDENTITY)

            // Rebuild from the frozen planner; use this private plan after matching every field.
            val expectedPlan = try { FullPageStripPlanner.plan(plan.sourceWidth, plan.sourceHeight) }
                catch (_: IllegalArgumentException) { throw Abort(Rejection.INVALID_PLAN) }
            requireInput(plan.strips.size == expectedPlan.strips.size &&
                plan.strips.zip(expectedPlan.strips).all { (a, b) ->
                    a.index == b.index && a.read == b.read && a.core == b.core && a.detector == b.detector
                }, Rejection.INVALID_PLAN)
            current()
            requireInput(stripReceipts.size == expectedPlan.strips.size, Rejection.INVALID_RECEIPTS)
            val receipts = stripReceipts.toList()
            requireInput(receipts.map { it.index }.toSet().size == receipts.size && receipts.all { r ->
                val strip = expectedPlan.strips.getOrNull(r.index)
                strip != null && r.read == strip.read && r.core == strip.core && r.complete && r.candidateCount >= 0
            }, Rejection.INVALID_RECEIPTS)

            requireInput(candidates.size <= FullPageOcrContract.MAX_BOXES_PER_PAGE, Rejection.BUDGET_EXCEEDED)
            val input = candidates.toList()
            val histogram = input.groupingBy { it.provenance.stripIndex }.eachCount()
            requireInput(histogram.values.all { it <= FullPageOcrContract.MAX_BOXES_PER_STRIP } &&
                input.sumOf { it.rawText.length.toLong() } <= FullPageOcrContract.MAX_RAW_CHARS,
                Rejection.BUDGET_EXCEEDED)

            // Validate entire categories in a fixed order, never select the first bad candidate.
            requireInput(input.all { c -> c.modelId == identity.model.id &&
                c.provenance.version == identity.version && c.provenance.pageFixtureId == identity.pageFixtureId },
                Rejection.IDENTITY_MISMATCH)
            requireInput(input.map { it.provenance.id }.toSet().size == input.size, Rejection.DUPLICATE_ID)
            requireInput(input.all { validMetadata(it, expectedPlan) }, Rejection.INVALID_METADATA)
            requireInput(input.all(::validGeometry), Rejection.INVALID_GEOMETRY)
            requireInput(receipts.all { it.candidateCount == (histogram[it.index] ?: 0) }, Rejection.INVALID_RECEIPTS)

            // Reconstruct provenance, including BOTH quads; never expose caller-owned collections.
            // Do not invoke caller callbacks between validation and copying those mutable input quads.
            val raw = frozen(input.map(::snapshot).sortedBy { it.provenance.id })
            val envelope = identity.copy(version = identity.version.copy(), model = identity.model.copy())
            current()
            val result = associateSnapshots(envelope, expectedPlan, raw, ::current)
            current()
            result
        } catch (failure: Abort) {
            rejected(failure.rejection)
        }
    }

    /** Revalidate/copy an already published structural value. No acquisition receipts, current
     * callback or publication capability is created here; the delivery owner must remain live.
     * Reuses the original spatial rules so downstream adapters cannot silently diverge. */
    fun validatedCopy(input: Result, width: Int, height: Int): Result? {
        val identity = input.identity ?: return null
        if (!input.published || input.rejection != null || identity.sourceBatch.isEmpty() ||
            identity.sourceBatch.length > 160 || !pagePattern.matches(identity.pageFixtureId) ||
            identity.model !in FullPageOcrContract.models || identity.detectorSha != DETECTOR_SHA ||
            dictionaryShas[identity.model.id] != identity.dictionarySha) return null
        val plan = try { FullPageStripPlanner.plan(width, height) } catch (_: IllegalArgumentException) { return null }
        val candidates = input.rawCandidates
        if (candidates.size > FullPageOcrContract.MAX_BOXES_PER_PAGE ||
            candidates.sumOf { it.rawText.length.toLong() } > FullPageOcrContract.MAX_RAW_CHARS ||
            candidates.groupingBy { it.provenance.stripIndex }.eachCount().values.any { it > FullPageOcrContract.MAX_BOXES_PER_STRIP } ||
            candidates.map { it.provenance.id }.distinct().size != candidates.size ||
            candidates.any { it.modelId != identity.model.id || it.provenance.version != identity.version ||
                it.provenance.pageFixtureId != identity.pageFixtureId || !validMetadata(it, plan) || !validGeometry(it) }) return null
        val raw = frozen(candidates.map(::snapshot).sortedBy { it.provenance.id })
        val copy = associateSnapshots(identity.copy(version = identity.version.copy(), model = identity.model.copy()),
            plan, raw) { /* Pure structural calculation, never a live-page authority. */ }
        return copy.takeIf { it.edges == input.edges && it.groups == input.groups }
    }

    private fun validMetadata(c: FullPageOcrContract.Candidate, plan: FullPageStripPlanner.Plan): Boolean {
        val p = c.provenance
        val strip = plan.strips.getOrNull(p.stripIndex) ?: return false
        return p.id.isNotEmpty() && p.read == strip.read && p.core == strip.core &&
            p.contourIndex in 0 until 1000 && p.rawBoxIndex in 0 until 1000 && p.finalBoxIndex in 0 until 1000 &&
            p.stripReadingOrder in 0 until FullPageOcrContract.MAX_BOXES_PER_STRIP &&
            p.detectorScore.isFinite() && p.detectorScore in 0.0..1.0 &&
            c.recognitionWidth in 320..FullPageOcrContract.MAX_WIDTH &&
            c.recognitionTime.toLong() == (c.recognitionWidth + 3L) / 8L
    }

    private fun validGeometry(c: FullPageOcrContract.Candidate): Boolean {
        val p = c.provenance
        if (p.localQuad.size != 4 || p.pageQuad.size != 4) return false
        return p.localQuad.zip(p.pageQuad).all { (local, page) ->
            local.x.isFinite() && local.y.isFinite() && page.x.isFinite() && page.y.isFinite() &&
                local.x in 0.0..(p.read.width - 1.0) && local.y in 0.0..(p.read.height - 1.0) &&
                page.x in p.read.left.toDouble()..(p.read.right - 1.0) &&
                page.y in p.read.top.toDouble()..(p.read.bottom - 1.0) &&
                abs(page.x - (local.x + p.read.left)) <= COORDINATE_TOLERANCE &&
                abs(page.y - (local.y + p.read.top)) <= COORDINATE_TOLERANCE
        }
    }

    private fun snapshot(c: FullPageOcrContract.Candidate): FullPageOcrContract.Candidate {
        val p = c.provenance
        return c.copy(provenance = FullPageOcrContract.Provenance(p.version.copy(), p.pageFixtureId,
            p.stripIndex, p.read.copy(), p.core.copy(), p.contourIndex, p.rawBoxIndex, p.finalBoxIndex,
            p.stripReadingOrder, frozen(p.localQuad.map { it.copy() }), frozen(p.pageQuad.map { it.copy() }),
            p.detectorScore, p.ownsCoreCenter, p.id))
    }

    private data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
        val supported get() = width > 0.0 && height > 0.0
    }
    private fun bounds(quad: List<GeometryProbeContract.Point>) = Bounds(
        quad.minOf { it.x }, quad.minOf { it.y }, quad.maxOf { it.x }, quad.maxOf { it.y })

    private fun axisAligned(quad: List<GeometryProbeContract.Point>, b: Bounds): Boolean {
        val corners = listOf(GeometryProbeContract.Point(b.left, b.top), GeometryProbeContract.Point(b.right, b.top),
            GeometryProbeContract.Point(b.right, b.bottom), GeometryProbeContract.Point(b.left, b.bottom))
        // Both windings and every starting corner are allowed, but crossing/unordered vertices are not.
        return (0..3).any { start -> listOf(1, -1).any { direction ->
            quad.indices.all { i ->
                val expected = corners[(start + direction * i + 4) % 4]
                abs(quad[i].x - expected.x) <= COORDINATE_TOLERANCE &&
                    abs(quad[i].y - expected.y) <= COORDINATE_TOLERANCE
            }
        } }
    }

    private fun near(a: Double, b: Double) = abs(a - b) <= EDGE_DISTANCE
    private fun internalReadContact(b: Bounds, read: FullPageStripPlanner.Rect, pageHeight: Int): Boolean =
        (read.top > 0 && near(b.top, read.top.toDouble())) ||
            (read.bottom < pageHeight && near(b.bottom, read.bottom - 1.0))

    private fun possibleClip(b: Bounds, read: FullPageStripPlanner.Rect, plan: FullPageStripPlanner.Plan): Boolean =
        near(b.left, read.left.toDouble()) || near(b.right, read.right - 1.0) ||
            near(b.top, read.top.toDouble()) || near(b.bottom, read.bottom - 1.0) ||
            near(b.left, 0.0) || near(b.right, plan.sourceWidth - 1.0) ||
            near(b.top, 0.0) || near(b.bottom, plan.sourceHeight - 1.0)

    private fun strong(a: Bounds, b: Bounds, ix: Double, iy: Double) =
        ix / maxOf(a.width, b.width) >= STRONG_FRACTION && iy / maxOf(a.height, b.height) >= STRONG_FRACTION

    private fun associateSnapshots(identity: PageIdentity, plan: FullPageStripPlanner.Plan,
        raw: List<FullPageOcrContract.Candidate>, current: () -> Unit): Result {
        val boxes = raw.map { bounds(it.provenance.pageQuad) }
        val reasons = List(raw.size) { linkedSetOf<Reason>() }
        val adjacent = List(raw.size) { arrayListOf<Int>() }
        val edges = arrayListOf<Edge>()
        for (i in raw.indices) {
            current()
            val c = raw[i]
            val b = boxes[i]
            if (!b.supported) reasons[i] += Reason.UNSUPPORTED_GEOMETRY
            else if (!axisAligned(c.provenance.pageQuad, b)) reasons[i] += Reason.NON_AXIS_ALIGNED
            if (possibleClip(b, c.provenance.read, plan)) reasons[i] += Reason.POSSIBLE_CLIP
            if (c.rawText.isEmpty()) reasons[i] += Reason.HAS_EMPTY
        }
        for (i in raw.indices) for (j in i + 1 until raw.size) {
            current()
            val a = boxes[i]
            val b = boxes[j]
            if (!a.supported || !b.supported) continue
            val pa = raw[i].provenance
            val pb = raw[j].provenance
            val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (ix <= 0.0 || iy <= 0.0) continue
            if (pa.stripIndex == pb.stripIndex) {
                if (strong(a, b, ix, iy)) {
                    reasons[i] += Reason.SAME_STRIP_OVERLAP
                    reasons[j] += Reason.SAME_STRIP_OVERLAP
                }
                continue // No same-strip edge, even if the AABBs coincide.
            }
            if (abs(pa.stripIndex - pb.stripIndex) != 1) continue
            val sharedLeft = maxOf(pa.read.left, pb.read.left).toDouble()
            val sharedTop = maxOf(pa.read.top, pb.read.top).toDouble()
            val sharedRight = minOf(pa.read.right, pb.read.right).toDouble()
            val sharedBottom = minOf(pa.read.bottom, pb.read.bottom).toDouble()
            if (sharedRight <= sharedLeft || sharedBottom <= sharedTop) continue
            val sharedIx = minOf(a.right, b.right, sharedRight) - maxOf(a.left, b.left, sharedLeft)
            val sharedIy = minOf(a.bottom, b.bottom, sharedBottom) - maxOf(a.top, b.top, sharedTop)
            if (sharedIx <= 0.0 || sharedIy <= 0.0) continue
            val kind = when {
                strong(a, b, sharedIx, sharedIy) -> EdgeKind.STRONG
                sharedIx / maxOf(a.width, b.width) >= STRONG_FRACTION &&
                    sharedIy / minOf(a.height, b.height) >= CLIPPED_FRACTION &&
                    (internalReadContact(a, pa.read, plan.sourceHeight) ||
                        internalReadContact(b, pb.read, plan.sourceHeight)) -> EdgeKind.POSSIBLE_CLIPPED
                else -> continue
            }
            edges += Edge(pa.id, pb.id, kind)
            adjacent[i] += j
            adjacent[j] += i
        }

        val visited = BooleanArray(raw.size)
        val groups = arrayListOf<Group>()
        // raw is ID-sorted, so component seeds (minimum member IDs) and edge endpoints are sorted.
        for (seed in raw.indices) {
            current()
            if (visited[seed]) continue
            val members = arrayListOf(seed)
            visited[seed] = true
            var cursor = 0
            while (cursor < members.size) {
                current()
                for (next in adjacent[members[cursor++]]) if (!visited[next]) {
                    visited[next] = true
                    members += next
                }
            }
            members.sort()
            val memberIds = frozen(members.map { raw[it].provenance.id })
            val groupReasons = members.flatMap { reasons[it] }.toMutableSet()
            if (members.any { adjacent[it].size > 1 }) groupReasons += Reason.MULTIPLE_MATCHES
            if (members.map { raw[it].provenance.stripIndex }.toSet().size < members.size)
                groupReasons += Reason.SAME_STRIP_MULTIPLE
            val directCount = members.sumOf { adjacent[it].size } / 2
            if (directCount < members.size * (members.size - 1) / 2) groupReasons += Reason.TRANSITIVE_CHAIN
            val texts = members.map { raw[it].rawText }
            val text = when {
                members.size == 1 -> Text.SINGLE
                texts.all { it.isEmpty() } -> Text.ALL_EMPTY
                texts.first().isNotEmpty() && texts.all { it == texts.first() } -> Text.IDENTICAL_NONEMPTY
                else -> Text.DIFFERENT_RAW
            }
            val uncertain = groupReasons.any { it != Reason.HAS_EMPTY }
            val geometry = when {
                members.size == 1 && (!boxes[seed].supported || !uncertain) -> Geometry.ISOLATED
                members.size == 2 && !uncertain && edges.any {
                    it.leftId == memberIds[0] && it.rightId == memberIds[1] && it.kind == EdgeKind.STRONG
                } -> Geometry.UNAMBIGUOUS_PAIR
                else -> Geometry.UNCERTAIN
            }
            // Length framing makes sourceBatch/model/minimum ID an unambiguous key, not a representative.
            val key = listOf(identity.sourceBatch, identity.model.id, memberIds.first()).joinToString("|") { "${it.length}:$it" }
            groups += Group(key, memberIds, text, geometry, frozen(groupReasons.sortedBy { it.ordinal }),
                if (text == Text.IDENTICAL_NONEMPTY && geometry == Geometry.UNAMBIGUOUS_PAIR) texts.first() else null)
        }
        return Result(true, null, identity, raw, frozen(edges), frozen(groups))
    }
}
