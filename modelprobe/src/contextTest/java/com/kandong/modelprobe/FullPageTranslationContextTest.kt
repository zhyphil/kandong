package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import org.junit.Assert.*
import org.junit.Test

/** Authored monolingual pages; tests transport and binding, never model translation quality. */
class FullPageTranslationContextTest {
    private val roi = ContextRect(20.0, 100.0, 180.0, 140.0)
    private val otherRoi = ContextRect(20.0, 260.0, 180.0, 290.0)
    private fun page(language: String): ScreenSnapshot {
        val text = when (language) {
            "en" -> listOf("Hotel reservations", "Book", "Refundable until 18:00", "Non-refundable offer", "Prices include tax")
            "fr" -> listOf("Réservation d’hôtel", "Réserver", "Annulation possible avant 18:00", "Offre non remboursable", "Taxes comprises")
            "zh-CN" -> listOf("酒店预订", "预订", "18:00前可退款", "不可退款房价", "价格含税")
            else -> listOf("酒店預訂", "預訂", "18:00前可退款", "不可退款房價", "價格含稅")
        }
        val ids = listOf("heading", "action", "condition", "other", "page-note")
        val blocks = ids.mapIndexed { i, id ->
            val top = listOf(20.0, 110.0, 170.0, 265.0, 380.0)[i]
            val rect = ContextRect(20.0, top, 180.0, top + 20)
            ContextBlock(id, text[i], language, if (id == "action") "button" else "text", rect, rect, i,
                groupId = when (id) { "action", "condition" -> "offer-a"; "other" -> "offer-b"; else -> null })
        }
        return ScreenSnapshot(ScreenIdentity(1, "single-$language", 1, 1), 400.0, 600.0, 100, 60_000,
            blocks, listOf(SemanticGroup("offer-a", GroupKind.CARD, listOf("action", "condition")),
                SemanticGroup("offer-b", GroupKind.CARD, listOf("other"))))
    }
    private fun answer(r: FixtureRequest) = FixtureResponse(r.id, r.page, r.targetLanguage, r.model, r.version,
        r.targets.map { FixtureAnswer(it, "人工占位：${it.id}") })

    @Test fun wholePageIsAvailableWhileTargetConditionsStayWithTheirOwnCard() {
        for (language in listOf("en", "fr", "zh-CN", "zh-TW")) {
            val p = page(language); val engine = ContextEngine()
            assertTrue(engine.activateSnapshot(p, 100)); engine.select(roi, 100)
            val r = engine.requestMissing(100)!!
            assertEquals(listOf("action"), r.targets.map { it.id })
            assertEquals(listOf("condition"), r.targets.single().context.map { it.id })
            // Neither the unlinked footer nor the other card is silently dropped from global context.
            assertEquals(p, r.screenContext)
            assertEquals(p.identity, r.page)
            assertEquals(listOf("heading", "action", "condition", "other", "page-note"), r.screenContext.blocks.map { it.id })
            assertEquals(setOf(language), r.screenContext.blocks.map { it.language }.toSet())
            assertEquals("offer-b", r.screenContext.blocks.single { it.id == "other" }.groupId)
        }
    }

    @Test fun fullPageSnapshotCannotBeChangedThroughCallerOwnedCollections() {
        val original = page("en")
        val blocks = original.blocks.toMutableList()
        val members = original.groups.first().memberIds.toMutableList()
        val groups = listOf(original.groups.first().copy(memberIds = members), original.groups.last()).toMutableList()
        val engine = ContextEngine()
        assertTrue(engine.activateSnapshot(original.copy(blocks = blocks, groups = groups), 100))
        engine.select(roi, 100); val request = engine.requestMissing(100)!!
        blocks.clear(); members.clear(); groups.clear()
        assertEquals(original, request.screenContext)
        assertThrows(UnsupportedOperationException::class.java) { (request.screenContext.blocks as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (request.screenContext.groups.first().memberIds as MutableList).clear() }
    }

    @Test fun oneClickSharesWholeContextAcrossRegionMovesAndRejectsOldPageAnswer() {
        val t = OnDemandTranslation(); t.observePage(TranslationPage(1, 1, 1))
        assertNull(t.pending); assertNull(t.snapshot)
        val ticket = t.clickTranslate(100)!!
        assertTrue(t.captured(ticket, page("fr"), roi, 100)); val request = t.pending!!
        val screen = request.screenContext
        assertEquals(5, request.targets.size)
        t.select(otherRoi, 101); t.setTransform(MirrorTransform(4.0, 240.0, 120.0), 101)
        assertSame(request, t.pending); assertSame(screen, t.snapshot)
        assertTrue(t.accept(answer(request), 102))
        assertEquals(listOf("other"), t.render(102).cards.map { it.targetId })
        t.select(roi, 103)
        assertEquals(listOf("action"), t.render(103).cards.map { it.targetId }); assertNull(t.pending)
        t.observePage(TranslationPage(1, 1, 2))
        assertNull(t.snapshot); assertFalse(t.accept(answer(request), 104)); assertTrue(t.render(104).cards.isEmpty())
    }

    @Test fun missingGlobalEvidenceStaysExplicitInsteadOfBeingInventedOrOmitted() {
        val original = page("en")
        val partial = original.copy(blocks = original.blocks.map {
            if (it.id == "page-note") it.copy(text = "", state = BlockState.OCCLUDED) else it
        }, coverageReasons = listOf("Synthetic footer is occluded"))
        val engine = ContextEngine(); assertTrue(engine.activateSnapshot(partial, 100)); engine.select(roi, 100)
        val request = engine.requestMissing(100)!!
        assertEquals(BlockState.OCCLUDED, request.screenContext.blocks.last().state)
        assertEquals("", request.screenContext.blocks.last().text)
        assertEquals(partial.coverageReasons, request.screenContext.coverageReasons)
        assertEquals(listOf("action"), request.targets.map { it.id })
        engine.clear(ClearReason.STOP)
        assertNull(engine.snapshot); assertEquals(0, engine.pendingCount)
        assertFalse(engine.acceptResponse(answer(request), 101))
    }
}
