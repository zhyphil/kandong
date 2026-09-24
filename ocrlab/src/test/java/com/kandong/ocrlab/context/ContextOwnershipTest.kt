package com.kandong.ocrlab.context

import org.junit.Assert.*
import org.junit.Test

class ContextOwnershipTest {
    @Test fun crossCardRelationRejectsSnapshotAndRevokesPreviousResults() {
        val page = ContextFixtures.page(0, 1, 100)
        val engine = ContextEngine()
        assertTrue(engine.activateSnapshot(page, 100))
        engine.select(ContextRect(20.0, 110.0, 125.0, 145.0), 100)
        val old = engine.requestMissing(100)!!
        assertTrue(engine.acceptResponse(ContextFixtures.response(old)!!, 100))
        assertEquals(1, engine.cacheCount)

        // A malformed extractor relationship must not attach Hotel B's refund terms to Hotel A.
        val mixed = page.copy(blocks = page.blocks.map {
            if (it.id == "a.price") it.copy(contextIds = listOf("page.heading", "b.condition")) else it
        })
        assertFalse(engine.activateSnapshot(mixed, 100))
        assertNull(engine.snapshot)
        assertEquals(0, engine.cacheCount)
        assertNull(engine.requestMissing(100))
        assertTrue(engine.render(100).cards.isEmpty())
    }
}
