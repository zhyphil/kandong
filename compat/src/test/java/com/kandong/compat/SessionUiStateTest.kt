package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class SessionUiStateTest {
    @Test fun lateMenuCallbacksAreRejectedAfterCloseOrTermination() {
        val state=SessionUiState()
        assertFalse(state.acceptsMenuCallbacks)
        state.menu(); assertTrue(state.acceptsMenuCallbacks)
        state.closeMenu(); assertFalse(state.acceptsMenuCallbacks)
        state.collapse(); state.menu(); assertTrue(state.acceptsMenuCallbacks)
        state.end(); assertFalse(state.acceptsMenuCallbacks)
        state.menu(); assertFalse(state.acceptsMenuCallbacks)
    }

    @Test fun menuAlwaysPausesAndReturnsToItsOrigin() {
        for(collapsed in listOf(false,true)) {
            val state=SessionUiState()
            if(collapsed) state.collapse()
            val before=state.mode
            assertTrue(state.menu()); assertFalse(state.capturing)
            assertFalse(state.menu()); assertEquals(before,state.returnTo)
            assertTrue(state.closeMenu()); assertEquals(before,state.mode)
            assertEquals(!collapsed,state.capturing)
        }
    }
    @Test fun terminalGuardPreventsEveryResurrectionPath() {
        for(mode in SessionMode.entries) {
            val state=SessionUiState()
            when(mode) { SessionMode.COLLAPSED -> state.collapse(); SessionMode.MENU -> state.menu(); else -> Unit }
            state.end(); state.end()
            assertFalse(state.resume()); assertFalse(state.collapse()); assertFalse(state.menu()); assertFalse(state.closeMenu())
            assertEquals(SessionMode.TERMINAL,state.mode); assertFalse(state.capturing)
        }
    }
    @Test fun togglePreservesSourceScaleAndBothPanAxes() {
        val viewport=MagnifierViewport().apply { selectSource(300,200); setViewport(200,120); setScale(2.63f); dragBy(-80f,-70f) }
        val before=listOf(viewport.sourceWidth.toFloat(),viewport.sourceHeight.toFloat(),viewport.scale,viewport.panX,viewport.panY)
        val state=SessionUiState()
        repeat(10) {
            state.collapse(); state.menu(); state.closeMenu(); state.resume()
            // The real service ignores zero-sized layouts and restores this same viewport size.
            viewport.setViewport(200,120)
            assertEquals(before,listOf(viewport.sourceWidth.toFloat(),viewport.sourceHeight.toFloat(),viewport.scale,viewport.panX,viewport.panY))
        }
    }
    @Test fun bridgePublishesImmutableStateAndRemovesListeners() {
        var calls=0
        val listener: (SessionSnapshot)->Unit = { calls++ }
        SessionBridge.add(listener); val initial=calls
        SessionBridge.publish(SessionSnapshot("opaque",SessionMode.COLLAPSED)); assertEquals(initial+1,calls)
        SessionBridge.remove(listener); SessionBridge.publish(SessionSnapshot()); assertEquals(initial+1,calls)
    }
}
