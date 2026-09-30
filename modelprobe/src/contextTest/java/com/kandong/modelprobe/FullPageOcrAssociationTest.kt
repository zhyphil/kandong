package com.kandong.modelprobe

import com.kandong.modelprobe.FullPageOcrAssociation.EdgeKind
import com.kandong.modelprobe.FullPageOcrAssociation.Geometry
import com.kandong.modelprobe.FullPageOcrAssociation.Reason
import com.kandong.modelprobe.FullPageOcrAssociation.Rejection
import com.kandong.modelprobe.FullPageOcrAssociation.Text
import com.kandong.modelprobe.FullPageOcrAssociation.UpstreamState
import com.kandong.modelprobe.FullPageOcrContract.Candidate
import com.kandong.modelprobe.FullPageOcrContract.Provenance
import com.kandong.modelprobe.GeometryProbeContract.Point
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Literal counterexamples frozen before implementation; no model or expected OCR transcript. */
class FullPageOcrAssociationTest {
    private val version = CaptureVersion(1, 2, 3, 4, 5, 0)
    private val plan = FullPageStripPlanner.plan(1176, 2400)
    private val identity = FullPageOcrAssociation.PageIdentity("audit-batch", version, "audit-page",
        FullPageOcrContract.models.first(),
        "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
        "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af")

    private fun rect(l: Double = 100.0, t: Double = 580.0, r: Double = 300.0, b: Double = 620.0) =
        listOf(Point(l, t), Point(r, t), Point(r, b), Point(l, b))

    private fun candidate(id: String, strip: Int = 0, text: String = "label",
        quad: List<Point> = rect(), score: Double = .87, core: Boolean = true): Candidate {
        val s = plan.strips[strip]
        val local = quad.map { Point(it.x - s.read.left, it.y - s.read.top) }
        return Candidate(Provenance(version, identity.pageFixtureId, strip, s.read, s.core,
            17, 12, 9, 0, local, quad, score, core, id), "ch", text, 320, 40)
    }

    private fun provenance(c: Candidate, version: CaptureVersion = c.provenance.version,
        page: String = c.provenance.pageFixtureId, strip: Int = c.provenance.stripIndex,
        read: FullPageStripPlanner.Rect = c.provenance.read, core: FullPageStripPlanner.Rect = c.provenance.core,
        contour: Int = c.provenance.contourIndex, raw: Int = c.provenance.rawBoxIndex,
        final: Int = c.provenance.finalBoxIndex, rank: Int = c.provenance.stripReadingOrder,
        local: List<Point> = c.provenance.localQuad, quad: List<Point> = c.provenance.pageQuad,
        score: Double = c.provenance.detectorScore, owns: Boolean = c.provenance.ownsCoreCenter,
        id: String = c.provenance.id) = c.copy(provenance = Provenance(version, page, strip, read, core,
        contour, raw, final, rank, local, quad, score, owns, id))

    private fun receipts(cs: List<Candidate>) = plan.strips.map { s ->
        FullPageOcrAssociation.StripReceipt(s.index, s.read, s.core, true,
            cs.count { it.provenance.stripIndex == s.index })
    }

    private fun run(cs: List<Candidate>, envelope: FullPageOcrAssociation.PageIdentity = identity,
        suppliedPlan: FullPageStripPlanner.Plan = plan, state: UpstreamState = UpstreamState.COMPLETE,
        rs: List<FullPageOcrAssociation.StripReceipt> = receipts(cs), current: () -> Boolean = { true }) =
        FullPageOcrAssociation.associate(envelope, suppliedPlan, state, rs, cs, current)

    private fun published(cs: List<Candidate>): FullPageOcrAssociation.Result = run(cs).also {
        assertTrue("Unexpected rejection: ${it.rejection}", it.published)
        assertNull(it.rejection)
        assertEquals(identity, it.identity)
        assertEquals(cs.map(::fields).sortedBy { row -> row[0] as String }, it.rawCandidates.map(::fields))
        assertEquals(cs.map { c -> c.provenance.id }.sorted(), it.groups.flatMap { g -> g.memberIds }.sorted())
    }

    private fun rejected(result: FullPageOcrAssociation.Result, reason: Rejection? = null) {
        assertFalse(result.published)
        assertNotNull(result.rejection)
        if (reason != null) assertEquals(reason, result.rejection)
        assertNull(result.identity)
        assertTrue(result.rawCandidates.isEmpty())
        assertTrue(result.edges.isEmpty())
        assertTrue(result.groups.isEmpty())
    }

    // Provenance intentionally has reference equality. Compare every field, including both quads.
    private fun fields(c: Candidate): List<Any> = c.provenance.let { p -> listOf(p.id,
        p.version, p.pageFixtureId, p.stripIndex, p.read, p.core, p.contourIndex, p.rawBoxIndex,
        p.finalBoxIndex, p.stripReadingOrder, p.localQuad.toList(), p.pageQuad.toList(),
        p.detectorScore.toRawBits(), p.ownsCoreCenter, c.modelId, c.rawText, c.recognitionWidth, c.recognitionTime) }

    private fun canonical(r: FullPageOcrAssociation.Result): List<Any?> = listOf(r.published,
        r.rejection, r.identity, r.rawCandidates.map(::fields), r.edges, r.groups)

    @Test fun case01_identicalNonemptyStrongPairHasOnlyExactAgreement() {
        val r = published(listOf(candidate("B", 1), candidate("A")))
        assertEquals(listOf("A", "B"), r.groups.single().memberIds)
        assertEquals(Geometry.UNAMBIGUOUS_PAIR, r.groups.single().geometry)
        assertEquals(Text.IDENTICAL_NONEMPTY, r.groups.single().text)
        assertEquals("label", r.groups.single().agreedRaw)
        assertEquals(emptyList<Reason>(), r.groups.single().reasons)
        assertEquals(listOf(FullPageOcrAssociation.Edge("A", "B", EdgeKind.STRONG)), r.edges)
        assertTrue(r.groups.single().id.contains(identity.sourceBatch))
        assertTrue(r.groups.single().id.contains(identity.model.id))
        assertNotEquals(r.groups.single().id, run(r.rawCandidates,
            envelope = identity.copy(sourceBatch = "another-batch")).groups.single().id)
    }

    @Test fun case02_conflictsRemainExactWithoutNormalizationOrWinner() {
        for ((a, b) in listOf("稅" to "税", "label" to "label ", "é" to "e\u0301")) {
            val g = published(listOf(candidate("A", text = a), candidate("B", 1, b))).groups.single()
            assertEquals(Text.DIFFERENT_RAW, g.text)
            assertEquals(Geometry.UNAMBIGUOUS_PAIR, g.geometry)
            assertNull(g.agreedRaw)
        }
    }

    @Test fun case03_adjacentColumnsAndInsufficientHorizontalOverlapStaySeparate() {
        for (left in listOf(301.0, 260.0, 140.00001)) {
            val r = published(listOf(candidate("A"), candidate("B", 1,
                quad = rect(l = left, r = left + 200))))
            assertTrue(r.edges.isEmpty())
            assertEquals(listOf(listOf("A"), listOf("B")), r.groups.map { it.memberIds })
        }
    }

    @Test fun case04_repeatedLabelsAtDifferentHeightsOrNonadjacentStripsDoNotLink() {
        for (b in listOf(candidate("B", 1, quad = rect(t = 625.0, b = 650.0)),
            candidate("B", 2, quad = rect(t = 1160.0, b = 1200.0)))) {
            val r = published(listOf(candidate("A"), b))
            assertEquals(2, r.groups.size)
            assertTrue(r.edges.isEmpty())
        }
    }

    @Test fun case05_partialAtInternalReadEdgeIsOnlyPossibleClippedEvidence() {
        val r = published(listOf(candidate("A", 1, "whole label", rect(t = 1110.0, b = 1160.0)),
            candidate("B", 2, "label", rect(t = 1136.0, b = 1160.0))))
        assertEquals(EdgeKind.POSSIBLE_CLIPPED, r.edges.single().kind)
        val g = r.groups.single()
        assertEquals(Geometry.UNCERTAIN, g.geometry)
        assertEquals(Text.DIFFERENT_RAW, g.text)
        assertTrue(Reason.POSSIBLE_CLIP in g.reasons)
        assertNull(g.agreedRaw)
    }

    @Test fun case06_lineBeyondHaloCannotProduceAgreedText() {
        val r = published(listOf(candidate("A", quad = rect(t = 500.0, b = 663.0)),
            candidate("B", 1, quad = rect(t = 536.0, b = 750.0))))
        assertEquals(EdgeKind.POSSIBLE_CLIPPED, r.edges.single().kind)
        assertEquals(Geometry.UNCERTAIN, r.groups.single().geometry)
        assertNull(r.groups.single().agreedRaw)
    }

    @Test fun case07_touchingOrDisjointBoxesNeverGetEdges() {
        for (b in listOf(rect(l = 300.0, r = 500.0), rect(t = 620.0, b = 650.0),
            rect(l = 301.0, r = 500.0), rect(t = 621.0, b = 650.0))) {
            val r = published(listOf(candidate("A"), candidate("B", 1, quad = b)))
            assertTrue(r.edges.isEmpty())
            assertEquals(2, r.groups.size)
        }
    }

    @Test fun case08_coreCenterDriftAndScoresNeverSelectOrDropCandidates() {
        val base = listOf(candidate("A", quad = rect(t = 579.0, b = 619.0), core = false, score = .01),
            candidate("B", 1, quad = rect(t = 581.0, b = 621.0), core = true, score = 1.0))
        val changed = base.map { provenance(it, owns = !it.provenance.ownsCoreCenter,
            score = 1.0 - it.provenance.detectorScore) }
        val a = published(base)
        val b = published(changed)
        assertEquals(a.edges, b.edges)
        assertEquals(a.groups, b.groups)
        assertEquals(2, a.rawCandidates.size)
    }

    @Test fun case09_emptyAndNonemptyCandidatesArePreservedWithoutAgreement() {
        for ((a, b, expected) in listOf(Triple("", "", Text.ALL_EMPTY),
            Triple("", "label", Text.DIFFERENT_RAW))) {
            val g = published(listOf(candidate("A", text = a), candidate("B", 1, b))).groups.single()
            assertEquals(expected, g.text)
            assertTrue(Reason.HAS_EMPTY in g.reasons)
            assertNull(g.agreedRaw)
        }
        val singleton = published(listOf(candidate("A", text = ""))).groups.single()
        assertEquals(Text.SINGLE, singleton.text)
        assertNull(singleton.agreedRaw)
    }

    @Test fun case10_blankRequiresEverySuccessfulZeroCountReceipt() {
        val r = published(emptyList())
        assertTrue(r.groups.isEmpty())
        assertTrue(r.edges.isEmpty())
        rejected(run(emptyList(), rs = receipts(emptyList()).dropLast(1)))
        rejected(run(emptyList(), rs = receipts(emptyList()).map { it.copy(candidateCount = 1) }))
    }

    @Test fun case11_sameStripStrongOverlapFlagsBothWithoutCreatingAnEdge() {
        val r = published(listOf(candidate("A"), candidate("B")))
        assertEquals(2, r.groups.size)
        assertTrue(r.edges.isEmpty())
        r.groups.forEach {
            assertEquals(1, it.memberIds.size)
            assertTrue(Reason.SAME_STRIP_OVERLAP in it.reasons)
            assertNull(it.agreedRaw)
        }
        assertFalse(published(listOf(candidate("A"), candidate("B", quad = rect(l = 141.0, r = 341.0))))
            .groups.any { Reason.SAME_STRIP_OVERLAP in it.reasons })
    }

    @Test fun case12_multipleMatchesAndRepeatedStripRetainAllEdgesAndMembers() {
        val r = published(listOf(candidate("A"), candidate("B", 1), candidate("C", 1)))
        assertEquals(listOf("A", "B", "C"), r.groups.single().memberIds)
        assertEquals(listOf("A" to "B", "A" to "C"), r.edges.map { it.leftId to it.rightId })
        val g = r.groups.single()
        assertTrue(g.reasons.containsAll(listOf(Reason.SAME_STRIP_OVERLAP,
            Reason.MULTIPLE_MATCHES, Reason.SAME_STRIP_MULTIPLE, Reason.TRANSITIVE_CHAIN)))
        assertEquals(Geometry.UNCERTAIN, g.geometry)
        assertNull(g.agreedRaw)
    }

    @Test fun case13_transitiveChainNeverInventsMissingDirectEvidence() {
        val r = published(listOf(candidate("A", quad = rect(t = 600.0, b = 663.0)),
            candidate("B", 1, quad = rect(t = 536.0, b = 1263.0)),
            candidate("C", 2, quad = rect(t = 1136.0, b = 1200.0))))
        assertEquals(listOf("A" to "B", "B" to "C"), r.edges.map { it.leftId to it.rightId })
        assertTrue(r.edges.all { it.kind == EdgeKind.POSSIBLE_CLIPPED })
        val g = r.groups.single()
        assertTrue(g.reasons.containsAll(listOf(Reason.TRANSITIVE_CHAIN, Reason.MULTIPLE_MATCHES, Reason.POSSIBLE_CLIP)))
        assertFalse(Reason.SAME_STRIP_MULTIPLE in g.reasons)
        assertNull(g.agreedRaw)
    }

    @Test fun case14_nonAxisAlignedAndUnorderedPositiveSpansAreUncertainButZeroSpansIsolate() {
        val q = rect()
        val shapes = listOf(listOf(Point(100.0, 590.0), Point(290.0, 580.0), Point(300.0, 610.0), Point(110.0, 620.0)),
            listOf(Point(110.0, 580.0), Point(290.0, 580.0), Point(300.0, 620.0), Point(100.0, 620.0)),
            listOf(q[0], q[2], q[1], q[3]), listOf(q[0], q[1], q[2], q[2]))
        for (shape in shapes) {
            val g = published(listOf(candidate("A", quad = shape), candidate("B", 1))).groups.single()
            assertEquals(Geometry.UNCERTAIN, g.geometry)
            assertTrue(Reason.NON_AXIS_ALIGNED in g.reasons)
            assertNull(g.agreedRaw)
        }
        for (shape in listOf(rect(l = 100.0, r = 100.0), rect(t = 600.0, b = 600.0))) {
            val r = published(listOf(candidate("A", quad = shape), candidate("B", 1)))
            assertTrue(r.edges.isEmpty())
            val g = r.groups.first()
            assertEquals(Geometry.ISOLATED, g.geometry)
            assertTrue(Reason.UNSUPPORTED_GEOMETRY in g.reasons)
        }
        for (shape in listOf(q.reversed(), listOf(q[2], q[3], q[0], q[1])))
            assertEquals(Geometry.UNAMBIGUOUS_PAIR,
                published(listOf(candidate("A", quad = shape), candidate("B", 1))).groups.single().geometry)
    }

    @Test fun case15_thresholdsAreInclusiveWithoutThresholdInflation() {
        // ix/maxWidth = 160/200 = .80 exactly; no clipping fallback can rescue a smaller width.
        assertEquals(EdgeKind.STRONG, published(listOf(candidate("A"),
            candidate("B", 1, quad = rect(l = 140.0, r = 340.0)))).edges.single().kind)
        assertTrue(published(listOf(candidate("A"),
            candidate("B", 1, quad = rect(l = 140.00001, r = 340.00001)))).edges.isEmpty())
        // iy/maxHeight = 32/40 = .80 exactly, away from all edges.
        assertEquals(EdgeKind.STRONG, published(listOf(candidate("A"),
            candidate("B", 1, quad = rect(t = 588.0, b = 628.0)))).edges.single().kind)
        assertTrue(published(listOf(candidate("A"),
            candidate("B", 1, quad = rect(t = 588.00001, b = 628.00001)))).edges.isEmpty())
        // iy/minHeight = 20/40 = .50; B contacts internal read top 536.
        assertEquals(EdgeKind.POSSIBLE_CLIPPED, published(listOf(candidate("A", quad = rect(t = 556.0, b = 596.0)),
            candidate("B", 1, quad = rect(t = 536.0, b = 576.0)))).edges.single().kind)
        assertTrue(published(listOf(candidate("A", quad = rect(t = 556.00001, b = 596.00001)),
            candidate("B", 1, quad = rect(t = 536.0, b = 576.0)))).edges.isEmpty())
        // Exactly 2 pixels from bottom pixel 663, then just above the permitted distance.
        val b = candidate("B", 1, quad = rect(t = 620.0, b = 720.0))
        assertEquals(EdgeKind.POSSIBLE_CLIPPED, published(listOf(
            candidate("A", quad = rect(t = 600.0, b = 661.0)), b)).edges.single().kind)
        assertTrue(published(listOf(candidate("A", quad = rect(t = 600.0, b = 660.99999)), b)).edges.isEmpty())
        for (x in listOf(0.0, 2.0, 975.0)) {
            val g = published(listOf(candidate("A", quad = rect(l = x, r = x + 200)),
                candidate("B", 1, quad = rect(l = x, r = x + 200)))).groups.single()
            assertTrue(Reason.POSSIBLE_CLIP in g.reasons)
            assertNull(g.agreedRaw)
        }
        // Shape tolerance is independent of the association thresholds.
        for ((delta, expected) in listOf(.0000005 to Geometry.UNAMBIGUOUS_PAIR, .000002 to Geometry.UNCERTAIN)) {
            val q = rect().toMutableList().also { it[1] = it[1].copy(y = it[1].y + delta) }
            assertEquals(expected, published(listOf(candidate("A", quad = q), candidate("B", 1))).groups.single().geometry)
        }
        // At x=0 the coordinate delta itself is exactly the stored 1e-6 constant.
        val atTolerance = rect(l = 0.0, r = 200.0).toMutableList().also { it[0] = it[0].copy(x = 1e-6) }
        assertFalse(Reason.NON_AXIS_ALIGNED in published(listOf(candidate("A", quad = atTolerance),
            candidate("B", 1, quad = rect(l = 0.0, r = 200.0)))).groups.single().reasons)
    }

    @Test fun case16_upstreamReceiptAndLifecycleFailuresPublishNothingEvenWhenBlank() {
        for (cs in listOf(emptyList(), listOf(candidate("A")),
            listOf(candidate("A"), candidate("B", 1), candidate("C", 1)))) {
            for (state in UpstreamState.values().filter { it != UpstreamState.COMPLETE })
                rejected(run(cs, state = state))
            val rs = receipts(cs)
            rejected(run(cs, rs = rs.dropLast(1)))
            rejected(run(cs, rs = rs + rs.first()))
            rejected(run(cs, rs = rs.mapIndexed { i, r -> if (i == 0) r.copy(complete = false) else r }))
            rejected(run(cs, rs = rs.mapIndexed { i, r -> if (i == 0) r.copy(candidateCount = -1) else r }))
            rejected(run(cs, current = { false }), Rejection.NOT_CURRENT)
            rejected(run(cs, current = { throw IllegalStateException("guard unavailable") }), Rejection.NOT_CURRENT)
            var calls = 0
            assertTrue(run(cs, current = { calls++; true }).published)
            assertTrue("Must check before, during, and after", calls >= 3)
            for (failAt in listOf(2, calls / 2, calls)) {
                var i = 0
                rejected(run(cs, current = { ++i != failAt }), Rejection.NOT_CURRENT)
                i = 0
                rejected(run(cs, current = { if (++i == failAt) throw IllegalArgumentException() else true }), Rejection.NOT_CURRENT)
            }
        }
    }

    @Test fun case17_eachIdentityFieldAndPinnedModelTupleIsCheckedForWholePage() {
        val a = candidate("A")
        val changes = listOf(version.copy(session = 9), version.copy(snapshot = 9), version.copy(page = 9),
            version.copy(revision = 9), version.copy(window = 9), version.copy(display = 9))
        for (v in changes) rejected(run(listOf(a, provenance(candidate("B", 1), version = v))))
        rejected(run(listOf(provenance(a, page = "another-page"))))
        rejected(run(listOf(a.copy(modelId = "latin"))))
        for (model in listOf(identity.model.copy(id = "other"), identity.model.copy(sha = "0".repeat(64)),
            identity.model.copy(vocabulary = 504))) rejected(run(listOf(a), envelope = identity.copy(model = model)))
        rejected(run(listOf(a), envelope = identity.copy(detectorSha = "0".repeat(64))))
        rejected(run(listOf(a), envelope = identity.copy(dictionarySha = "0".repeat(64))))
        for (source in listOf("", "x".repeat(161))) rejected(run(listOf(a), envelope = identity.copy(sourceBatch = source)))
        assertTrue(run(listOf(a), envelope = identity.copy(sourceBatch = "x".repeat(160))).published)
        rejected(run(emptyList(), envelope = identity.copy(pageFixtureId = "bad/page")))
        val latin = identity.copy(model = FullPageOcrContract.models.last(),
            dictionarySha = "d80d308e9759b3ea3aaf6e57fa51b39ef0a2eeb3145a9b23df819f0f0279af44")
        assertTrue(run(listOf(a.copy(modelId = "latin")), envelope = latin).published)
        rejected(run(listOf(a), envelope = latin))
        // An envelope is caller evidence, not authentication of a candidate's historical origin.
        assertTrue(run(listOf(a), envelope = identity.copy(sourceBatch = "different-envelope")).published)
    }

    @Test fun case18_malformedGeometryMetadataPlanAndReceiptHistogramRejectWholePage() {
        val a = candidate("A")
        val p = a.provenance
        val invalid = listOf(provenance(a, strip = -1), provenance(a, strip = 4),
            provenance(a, read = p.read.copy(bottom = 665)), provenance(a, core = p.core.copy(bottom = 599)),
            provenance(a, local = p.localQuad.dropLast(1)), provenance(a, quad = p.pageQuad.dropLast(1)),
            provenance(a, local = listOf(Point(Double.NaN, 580.0)) + p.localQuad.drop(1)),
            provenance(a, quad = listOf(Point(100.0, Double.POSITIVE_INFINITY)) + p.pageQuad.drop(1)),
            provenance(a, local = listOf(Point(-.01, 580.0)) + p.localQuad.drop(1)),
            provenance(a, local = listOf(Point(1176.0, 580.0)) + p.localQuad.drop(1)),
            provenance(a, quad = listOf(Point(100.1, 580.0)) + p.pageQuad.drop(1)),
            provenance(a, score = Double.NaN), provenance(a, score = -0.01), provenance(a, score = 1.01),
            provenance(a, contour = -1), provenance(a, contour = 1000), provenance(a, raw = -1),
            provenance(a, raw = 1000), provenance(a, final = -1), provenance(a, final = 1000),
            provenance(a, rank = -1), provenance(a, rank = 64), provenance(a, id = ""),
            a.copy(recognitionWidth = 319), a.copy(recognitionWidth = 1025), a.copy(recognitionTime = 41),
            a.copy(recognitionWidth = 452, recognitionTime = 57))
        for (bad in invalid) rejected(run(listOf(bad, candidate("B", 1))))
        assertTrue(run(listOf(a.copy(recognitionWidth = 452, recognitionTime = 56))).published)
        assertTrue(run(listOf(provenance(a, contour = 999, raw = 999, final = 999, rank = 63))).published)
        rejected(run(listOf(a, candidate("A", 1))), Rejection.DUPLICATE_ID)
        val s = plan.strips.first()
        val wrongPlans = listOf(FullPageStripPlanner.Plan(0, 2400, plan.strips),
            FullPageStripPlanner.Plan(1176, 2400, plan.strips.dropLast(1)),
            FullPageStripPlanner.Plan(1176, 2400, plan.strips.reversed()),
            FullPageStripPlanner.Plan(1176, 2400, listOf(FullPageStripPlanner.Strip(s.index,
                s.read, s.core, s.detector.copy(width = 1))) + plan.strips.drop(1)))
        for (wrong in wrongPlans) rejected(run(listOf(a), suppliedPlan = wrong))
        val rs = receipts(listOf(a))
        for (bad in listOf(rs.first().copy(index = 9), rs.first().copy(read = s.read.copy(bottom = 1)),
            rs.first().copy(core = s.core.copy(top = 1)), rs.first().copy(candidateCount = 0)))
            rejected(run(listOf(a), rs = listOf(bad) + rs.drop(1)))
        // Whole-category precedence is independent of candidate and receipt iteration order.
        val mixed = listOf(provenance(a, contour = -1), candidate("B", 1).copy(modelId = "other"))
        assertEquals(canonical(run(mixed)), canonical(run(mixed.reversed(), rs = receipts(mixed).reversed())))
        val tolerant = provenance(a, quad = listOf(Point(100.0000005, 580.0)) + p.pageQuad.drop(1))
        assertTrue(run(listOf(tolerant)).published)
        rejected(run(listOf(provenance(a, quad = listOf(Point(100.000002, 580.0)) + p.pageQuad.drop(1)))))
    }

    @Test fun case19_exactAndExcessBudgetsCountUtf16UnitsIncludingSupplementaryCharacters() {
        fun rows(strip: Int, count: Int): List<Candidate> = List(count) { i ->
            val y = if (strip == 0) 100.0 else if (strip == 1) 800.0 else 1400.0
            candidate("s$strip-$i", strip, "", rect(t = y, b = y + 20))
        }
        val exact = rows(0, 64) + rows(1, 64)
        assertEquals(128, published(exact).rawCandidates.size)
        rejected(run(rows(0, 65)), Rejection.BUDGET_EXCEEDED)
        rejected(run(exact + rows(2, 1)), Rejection.BUDGET_EXCEEDED)
        assertTrue(run(listOf(candidate("A", text = "x".repeat(8192)))).published)
        rejected(run(listOf(candidate("A", text = "x".repeat(8193)))), Rejection.BUDGET_EXCEEDED)
        val supplementary = "\uD83D\uDE00".repeat(4096)
        assertEquals(8192, supplementary.length)
        assertTrue(run(listOf(candidate("A", text = supplementary))).published)
        rejected(run(listOf(candidate("A", text = supplementary + "x"))), Rejection.BUDGET_EXCEEDED)
        rejected(run(listOf(candidate("A", text = "x".repeat(4096)),
            candidate("B", 1, "y".repeat(4097)))), Rejection.BUDGET_EXCEEDED)
    }

    @Test fun case20_permutationAndCallerOrOutputMutationCannotChangePublishedSnapshots() {
        val local = rect().toMutableList()
        val page = rect().toMutableList()
        val cs = mutableListOf(provenance(candidate("A"), local = local, quad = page),
            candidate("C", 1, ""), candidate("B", 1, "conflict"),
            candidate("D", 2, quad = rect(t = 1400.0, b = 1440.0)))
        val rs = receipts(cs).toMutableList()
        val result = run(cs, rs = rs)
        assertTrue(result.published)
        val value = canonical(result)
        for (seed in 0..10) assertEquals(value,
            canonical(run(cs.shuffled(Random(seed)), rs = rs.shuffled(Random(seed + 20)))))
        assertNotSame(cs.first().provenance, result.rawCandidates.first().provenance)
        local[0] = Point(-100.0, -100.0)
        page.clear()
        cs.clear()
        rs.clear()
        assertEquals(value, canonical(result))
        fun immutable(block: () -> Unit) { assertThrows(UnsupportedOperationException::class.java, block) }
        immutable { (result.rawCandidates as MutableList).clear() }
        immutable { (result.edges as MutableList).clear() }
        immutable { (result.groups as MutableList).clear() }
        result.rawCandidates.forEach { c ->
            immutable { (c.provenance.localQuad as MutableList)[0] = Point(0.0, 0.0) }
            immutable { (c.provenance.pageQuad as MutableList).clear() }
        }
        result.groups.forEach { g ->
            immutable { (g.memberIds as MutableList).clear() }
            immutable { (g.reasons as MutableList).clear() }
        }
        for (empty in listOf(run(emptyList()), run(emptyList(), current = { false }))) {
            immutable { (empty.rawCandidates as MutableList).clear() }
            immutable { (empty.edges as MutableList).clear() }
            immutable { (empty.groups as MutableList).clear() }
        }
        assertEquals(value, canonical(result))
    }
}
