package com.kandong.ocrlab.context

import org.junit.Assert.*
import org.junit.Test

class ContextEngineTest {
    private val roiA = ContextRect(20.0, 110.0, 125.0, 145.0)
    private val roiB = ContextRect(20.0, 300.0, 125.0, 335.0)
    private fun engine(scene: Int = 0): ContextEngine = ContextEngine().apply {
        assertTrue(activateSnapshot(ContextFixtures.page(scene, 1, 100), 100))
        select(roiA, 100)
    }
    private fun request(e: ContextEngine) = e.requestMissing(100)!!
    private fun response(r: FixtureRequest) = ContextFixtures.response(r)!!
    private fun target(e: ContextEngine) = e.selection!!.targets.single()

    @Test fun hotelSelectionKeepsExactOutsideRoiContextWithoutOtherCard() {
        val e = engine()
        val a = target(e)
        assertEquals("a.price", a.id)
        assertEquals(listOf("120 €"), a.sources.map { it.text })
        assertEquals(listOf("page.heading", "a.title", "a.unit", "a.condition"), a.context.map { it.id })
        assertEquals(listOf("酒店两张卡", "Hotel A", "/ night", "Free cancellation until 15 Oct 18:00"), a.context.map { it.text })
        e.select(roiB, 100)
        val b = target(e)
        assertEquals("b.price", b.id)
        assertEquals(listOf("300 €"), b.sources.map { it.text })
        assertEquals(listOf("page.heading", "b.title", "b.unit", "b.condition"), b.context.map { it.id })
        assertEquals(listOf("酒店两张卡", "Hotel B", "/ total, 2 nights", "Non-refundable"), b.context.map { it.text })
    }
    @Test fun headingRelationIsNotTraversedIntoOtherCard() {
        val original = ContextFixtures.page(0, 1, 100)
        val p = original.copy(blocks = original.blocks.map { if (it.id == "page.heading") it.copy(contextIds = listOf("b.condition")) else it })
        val e = ContextEngine(); assertTrue(e.activateSnapshot(p, 100)); e.select(roiA, 100)
        assertEquals(listOf("page.heading", "a.title", "a.unit", "a.condition"), target(e).context.map { it.id })
    }
    @Test fun repeatedContinueIdsAndReorderedResponseKeepGroups() {
        val e = engine(1)
        e.select(ContextRect(20.0, 110.0, 160.0, 335.0), 100)
        val r = request(e)
        assertEquals(listOf("continue.a.button", "continue.b.button"), r.targets.map { it.id })
        assertEquals(listOf("continue.a", "continue.b"), r.targets.map { it.groupId })
        assertEquals(listOf("Continue", "Continue"), r.targets.map { it.sources.single().text })
        assertTrue(e.acceptResponse(response(r).copy(answers = response(r).answers.reversed()), 100))
        assertEquals(listOf("继续", "继续"), e.render(100).cards.map { it.chinese })
    }
    @Test fun bookContextsAreProvidedAndChineseIsHandcrafted() {
        val hotel = request(engine(2)); val shop = request(engine(3))
        assertEquals("Book", hotel.targets.single().sources.single().text)
        assertEquals("Book", shop.targets.single().sources.single().text)
        assertEquals(listOf("page.heading", "booking.title"), hotel.targets.single().context.map { it.id })
        assertEquals(listOf("page.heading", "bookshop.title"), shop.targets.single().context.map { it.id })
        assertEquals("Reserve a hotel room", hotel.targets.single().context.last().text)
        assertEquals("Bookshop catalogue", shop.targets.single().context.last().text)
        assertEquals("HANDCRAFTED_NO_MODEL", hotel.model)
        assertEquals("预订", response(hotel).answers.single().chinese)
        assertEquals("书籍", response(shop).answers.single().chinese)
    }
    @Test fun completeNegationIncludesBothFragmentsWhileMissingDoesNotInventText() {
        val e = engine(4)
        val r = request(e)
        assertEquals("neg.phrase", r.targets.single().id)
        assertEquals(listOf("neg.first", "neg.second"), r.targets.single().sources.map { it.id })
        assertEquals("Vous ne pouvez pas ne pas payer.", r.targets.single().sources.joinToString(" ") { it.text })
        assertEquals("您不能不付款。", response(r).answers.single().chinese)
        assertTrue(e.acceptResponse(response(r), 100))
        assertEquals(listOf("neg.first"), e.render(100).anchors.map { it.sourceId })
        val missing = engine(5)
        assertEquals("MISSING_GROUP_MEMBER", target(missing).reason)
        assertEquals(listOf("Vous ne pouvez pas"), target(missing).sources.map { it.text })
        assertFalse(missing.snapshot!!.blocks.any { it.id == "neg.second" })
        assertNull(missing.requestMissing(100)); assertNull(missing.render(100).cards.single().chinese)
    }
    @Test fun snapshotReusedAndLateResultRendersOnlyLatestSelectionAndTransform() {
        val e = engine(); val p = e.snapshot; val r = request(e)
        val oldSelection = e.selection!!.generation
        e.select(roiB, 100)
        assertSame(p, e.snapshot); assertEquals(9, e.snapshot!!.blocks.size)
        assertTrue(e.selection!!.generation > oldSelection)
        assertTrue(e.acceptResponse(response(r), 100))
        assertEquals(listOf("b.price"), e.render(100).cards.map { it.targetId })
        assertNull(e.render(100).cards.single().chinese)
        e.select(roiA, 100)
        assertEquals("每晚 120 欧元；可在 10 月 15 日 18:00 前免费取消", e.render(100).cards.single().chinese)
        assertNull(e.requestMissing(100))
        val selection = e.selection
        e.setTransform(MirrorTransform(3.0, 240.0, 120.0, 5.0, 0.0), 100)
        assertSame(selection, e.selection); assertSame(p, e.snapshot)
        assertEquals(ContextRect(0.0, 15.0, 240.0, 90.0), e.render(100).anchors.single().rect)
    }
    @Test fun responseAfterZoomUsesNewGeometryWithoutNewRequest() {
        val e = engine(); val r = request(e)
        e.setTransform(MirrorTransform(5.0, 200.0, 100.0, 10.0, 2.0), 100)
        assertEquals(1, e.pendingCount); assertNull(e.requestMissing(100))
        assertTrue(e.acceptResponse(response(r), 100))
        assertEquals(ContextRect(0.0, 15.0, 200.0, 100.0), e.render(100).anchors.single().rect)
    }
    @Test fun sameTextAndCoordinatesOrAmountChangeOnNewGenerationRejectOldRequest() {
        for (changeAmount in listOf(false, true)) {
            val e = engine(); val old = request(e); val p = e.snapshot!!
            val next = p.copy(identity = p.identity.copy(contentRevision = 2), blocks = p.blocks.map { if (changeAmount && it.id == "a.price") it.copy(text = "121 €") else it })
            assertTrue(e.activateSnapshot(next, 100)); e.select(roiA, 100)
            assertFalse(e.acceptResponse(response(old), 100)); assertEquals(0, e.cacheCount)
            assertNull(e.render(100).cards.single().chinese)
        }
    }
    @Test fun everyLifecycleClearAndReopenRevokesOldResponses() {
        for (reason in listOf(ClearReason.PAUSE, ClearReason.MENU, ClearReason.STOP, ClearReason.PAGE_CHANGE)) {
            val e = engine(); val r = request(e)
            e.clear(reason)
            assertNull(e.snapshot); assertNull(e.selection); assertNull(e.transform)
            assertEquals(0, e.pendingCount); assertEquals(0, e.cacheCount)
            assertFalse(e.acceptResponse(response(r), 100)); assertTrue(e.render(100).cards.isEmpty())
            assertNull(e.select(roiA, 100))
            // Even reloading the identical identity cannot resurrect an old request.
            assertTrue(e.activateSnapshot(ContextFixtures.page(0, 1, 100), 100)); e.select(roiA, 100)
            val fresh = request(e); assertNotEquals(r.id, fresh.id)
            assertFalse(e.acceptResponse(response(r), 100)); assertTrue(e.acceptResponse(response(fresh), 100))
            e.clear(reason); assertTrue(e.render(100).cards.isEmpty()); assertEquals(0, e.cacheCount)
        }
    }
    @Test fun explicitCancelRevokesPublishAndAllowsNewRequest() {
        val e = engine(); val r = request(e)
        assertTrue(e.cancelRequest(r.id)); assertFalse(e.cancelRequest(r.id))
        assertFalse(e.acceptResponse(response(r), 100)); assertEquals(0, e.cacheCount)
        val next = request(e); assertNotEquals(r.id, next.id); assertTrue(e.acceptResponse(response(next), 100))
        assertFalse(e.acceptResponse(response(next), 100))
    }
    @Test fun expiryBoundaryMonotonicityAndTtlBoundsWithoutSleep() {
        val e = engine(); val r = request(e)
        assertTrue(e.tick(60_099)); assertFalse(e.tick(60_100))
        assertEquals(ClearReason.EXPIRED, e.lastClear); assertNull(e.snapshot)
        assertFalse(e.acceptResponse(response(r), 60_100)); assertTrue(e.render(60_100).cards.isEmpty())
        val clock = engine(); assertFalse(clock.tick(99)); assertEquals(ClearReason.CLOCK_INVALID, clock.lastClear)
        val p = ContextFixtures.page(0, 1, 100)
        for (ttl in listOf(-1L, 0L, 60_001L, Long.MAX_VALUE)) assertFalse(ContextEngine().activateSnapshot(p.copy(ttlMillis = ttl), 100))
        assertFalse(ContextEngine().activateSnapshot(p.copy(capturedAt = 101), 100))
        assertFalse(ContextEngine().activateSnapshot(p.copy(capturedAt = Long.MAX_VALUE - 1), Long.MAX_VALUE - 1))
        assertFalse(ContextEngine().activateSnapshot(p, -1))
        assertTrue(ContextEngine().activateSnapshot(p.copy(ttlMillis = 1), 100))
        assertFalse(ContextEngine().activateSnapshot(p.copy(ttlMillis = 1), 101))
    }
    @Test fun invalidSnapshotAlwaysClearsOldCacheAndPendingFirst() {
        val e = engine(); val r = request(e); assertTrue(e.acceptResponse(response(r), 100))
        e.select(roiB, 100); val pending = request(e)
        assertFalse(e.activateSnapshot(e.snapshot!!.copy(width = Double.NaN), 100))
        assertNull(e.snapshot); assertNull(e.selection); assertEquals(0, e.cacheCount); assertEquals(0, e.pendingCount)
        assertFalse(e.acceptResponse(response(pending), 100)); assertTrue(e.render(100).cards.isEmpty())
    }
    @Test fun malformedSourceIdsGeometryRelationsAndBoundsAreRejected() {
        val p = ContextFixtures.page(0, 1, 100)
        val bad = listOf(
            p.copy(blocks = p.blocks + p.blocks.first()),
            p.copy(groups = p.groups + p.groups.first()),
            p.copy(blocks = p.blocks.map { if (it.id == "a.price") it.copy(contextIds = listOf("missing")) else it }),
            p.copy(blocks = p.blocks.map { if (it.id == "a.price") it.copy(groupId = "hotel.b") else it }),
            p.copy(blocks = p.blocks.map { if (it.id == "a.price") it.copy(visible = ContextRect(0.0, 0.0, 500.0, 500.0)) else it }),
            p.copy(blocks = p.blocks.map { if (it.id == "a.price") it.copy(original = ContextRect(-1.0, 115.0, 120.0, 140.0)) else it }),
            p.copy(blocks = p.blocks.map { if (it.id == "a.price") it.copy(text = "x".repeat(2001)) else it }),
            p.copy(blocks = p.blocks.map { if (it.id == "a.price") it.copy(confidence = Double.NaN) else it }),
        )
        bad.forEach { assertFalse(ContextEngine().activateSnapshot(it, 100)) }
        val e = engine()
        assertThrows(IllegalArgumentException::class.java) { e.select(ContextRect(-1.0, 0.0, 20.0, 20.0), 100) }
        assertThrows(IllegalArgumentException::class.java) { e.select(ContextRect(0.0, 0.0, 500.0, 500.0), 100) }
    }
    @Test fun incompleteEvidenceRemainsReasonsAndDoesNotBlockUnrelatedCard() {
        for (state in BlockState.entries.filter { it != BlockState.KNOWN }) {
            val p = ContextFixtures.page(0, 1, 100)
            val e = ContextEngine()
            assertTrue(e.activateSnapshot(p.copy(blocks = p.blocks.map { if (it.id == "a.condition") it.copy(state = state) else it }), 100))
            e.select(roiA, 100); assertEquals("INCOMPLETE_$state", target(e).reason); assertNull(e.requestMissing(100))
            e.select(roiB, 100); assertEquals("b.price", request(e).targets.single().id)
        }
        val e = engine(6); e.select(ContextRect(10.0, 65.0, 390.0, 550.0), 100)
        assertEquals(listOf("INCOMPLETE_UNKNOWN", "INCOMPLETE_ICON_ONLY", "INCOMPLETE_OCCLUDED", "INCOMPLETE_AMBIGUOUS", "INCOMPLETE_TRUNCATED", "INCOMPLETE_CONFLICT", "MISSING_GROUP_MEMBER", "AMBIGUOUS_GROUP", null), e.selection!!.targets.map { it.reason })
        assertEquals(listOf("known.book"), request(e).targets.map { it.id })
    }
    @Test fun clippedOriginalAllowedOnlyWhenDisclosedAndNotTreatedAsComplete() {
        val p = ContextFixtures.page(0, 1, 100)
        val e = ContextEngine()
        assertTrue(e.activateSnapshot(p.copy(blocks = p.blocks.map { if (it.id == "a.price") it.copy(original = ContextRect(-5.0, 115.0, 120.0, 140.0), clipped = true) else it }), 100))
        e.select(roiA, 100); assertEquals("TRUNCATED_GEOMETRY", target(e).reason); assertNull(e.requestMissing(100))
    }
    @Test fun responseCorruptionRejectsWholeBatchThenValidBatchCanPublishOnce() {
        val e = engine(1); e.select(ContextRect(20.0, 110.0, 160.0, 335.0), 100)
        val r = request(e); val valid = response(r); val a = valid.answers[0]; val b = valid.answers[1]
        fun changed(binding: TargetBinding) = valid.copy(answers = listOf(a.copy(binding = binding), b))
        val corruptions = listOf(
            valid.copy(requestId = r.id + 1), valid.copy(page = r.page.copy(windowId = "other")),
            valid.copy(page = r.page.copy(session = 5)), valid.copy(page = r.page.copy(pageGeneration = 4)),
            valid.copy(targetLanguage = "fr"), valid.copy(model = "model"), valid.copy(version = "other"),
            valid.copy(answers = listOf(a, a)), valid.copy(answers = listOf(a)), valid.copy(answers = listOf(a, b, b)),
            changed(a.binding.copy(id = "unknown")), changed(a.binding.copy(groupId = b.binding.groupId)),
            changed(a.binding.copy(role = "price")), changed(a.binding.copy(groupKind = GroupKind.PHRASE)),
            changed(a.binding.copy(context = b.binding.context)),
            changed(a.binding.copy(context = a.binding.context.map { it.copy(text = "wrong context") })),
            changed(a.binding.copy(sources = a.binding.sources.map { it.copy(text = "wrong source") })),
            changed(a.binding.copy(sources = a.binding.sources.map { it.copy(language = "fr") })),
            valid.copy(answers = listOf(a.copy(chinese = ""), b)),
        )
        corruptions.forEach { assertFalse(e.acceptResponse(it, 100)); assertEquals(0, e.cacheCount); assertTrue(e.render(100).cards.all { card -> card.chinese == null }) }
        assertTrue(e.acceptResponse(valid, 100)); assertFalse(e.acceptResponse(valid, 100))
    }
    @Test fun callerCollectionMutationCannotChangeSnapshotRequestOrCache() {
        val p = ContextFixtures.page(0, 1, 100)
        val refs = mutableListOf("page.heading")
        val blocks = p.blocks.map { if (it.id == "a.price") it.copy(contextIds = refs) else it }.toMutableList()
        val members = p.groups[0].memberIds.toMutableList()
        val groups = p.groups.toMutableList().apply { set(0, p.groups[0].copy(memberIds = members)) }
        val coverage = mutableListOf("synthetic")
        val e = ContextEngine(); assertTrue(e.activateSnapshot(p.copy(blocks = blocks, groups = groups, coverageReasons = coverage), 100))
        refs.clear(); blocks.clear(); members.clear(); groups.clear(); coverage.clear()
        e.select(roiA, 100); val r = request(e)
        assertEquals(listOf("page.heading", "a.title", "a.unit", "a.condition"), r.targets.single().context.map { it.id })
        assertEquals(listOf("synthetic"), e.snapshot!!.coverageReasons)
        assertThrows(UnsupportedOperationException::class.java) { (r.targets as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (e.snapshot!!.blocks as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (r.targets.single().sources.single().contextIds as MutableList).clear() }
        val target = r.targets.single(); val mutableSources = target.sources.toMutableList(); val mutableContext = target.context.toMutableList()
        val answers = mutableListOf(FixtureAnswer(target.copy(sources = mutableSources, context = mutableContext), "人工测试值"))
        val reply = response(r).copy(answers = answers)
        assertTrue(e.acceptResponse(reply, 100))
        mutableSources.clear(); mutableContext.clear(); answers.clear()
        assertEquals("人工测试值", e.render(100).cards.single().chinese)
        // Deliberately arbitrary Chinese passes: only structure is certified, never semantics.
    }
    @Test fun unsupportedPresetResponseIsNotInvented() {
        val e = engine(); e.select(ContextRect(20.0, 75.0, 370.0, 100.0), 100)
        assertNull(ContextFixtures.response(request(e)))
    }
}
