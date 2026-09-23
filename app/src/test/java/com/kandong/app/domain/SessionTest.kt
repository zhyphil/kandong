package com.kandong.app.domain

import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    private val context = ScreenContext("fixture", 7, 0, 0, Box(0, 0, 1000, 1000))
    private fun node(index: Int, clickable: Boolean = true, bounds: Box = Box(10, 300, 100, 400)) =
        NodeSnapshot(index, "0/$index", "相同标签", "", "Button", clickable, true,
            bounds, 7, "fixture", null, false)
    private val result get() = ReadResult(listOf(node(2), node(1), node(0, false)), false, false)

    @Test fun selectionIsDeterministicAndBoundsChecked() {
        val session = Session({ 0 }); session.start()
        assertTrue(session.accept(session.generation, context, result, 0, null))
        assertEquals(1, session.current(context)?.index)
        assertEquals(1, session.move(-1, context)?.index)
        assertEquals(2, session.move(1, context)?.index)
        assertEquals(0, session.move(500, context)?.index)
        assertFalse(CandidateSelector.explanation(node(0, false)).contains("按钮"))
    }
    @Test fun panelOcclusionExcludesCandidateWithoutGuessing() {
        assertTrue(CandidateSelector.candidates(listOf(node(1)), Box(0, 200, 200, 500)).isEmpty())
    }
    @Test fun expiryDoesNotRenewOnSelectionAndDropsLateCompletion() {
        var now = 100L
        val session = Session({ now }); session.start()
        val token = session.generation
        assertTrue(session.accept(token, context, result, now, null))
        now += 14_999
        assertNotNull(session.move(1, context))
        now++
        assertNull(session.current(context)); assertNull(session.snapshot)
        assertFalse(session.accept(token, context, result, 100, null))
    }
    @Test fun navigationAndExplicitInvalidationCancelQueuedWork() {
        val session = Session({ 0 }); session.start()
        val token = session.generation
        assertTrue(session.accept(token, context, result, 0, null))
        assertNull(session.current(context.copy(windowId = 8)))
        assertFalse(session.accept(token, context, result, 0, null))
        val newToken = session.generation
        session.invalidate()
        assertFalse(session.accept(newToken, context, result, 0, null))
    }
    @Test fun stopRejectsLateResultAndClearsAllCandidates() {
        val session = Session({ 0 }); session.start()
        val token = session.generation
        assertTrue(session.accept(token, context, result, 0, null))
        session.stop()
        assertNull(session.snapshot); assertTrue(session.candidates.isEmpty())
        assertFalse(session.accept(token, context, result, 0, null))
    }
    @Test fun snapshotCopiesCallerList() {
        val input = mutableListOf(node(1))
        val snapshot = Snapshot(context, input, 0, 15_000, false)
        input.clear()
        assertEquals(1, snapshot.nodes.size)
    }
}
