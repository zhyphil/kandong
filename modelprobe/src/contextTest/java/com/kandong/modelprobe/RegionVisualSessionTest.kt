package com.kandong.modelprobe

import org.junit.Assert.*
import org.junit.Test

class RegionVisualSessionTest {
    private var now = 100L
    private fun session() = RegionVisualSession { now }.also { it.viewport(600.0,260.0) }
    @Test fun onlyExplicitShowLoadsAndRetainsWholeEvidenceWithConflictAndEmpty() {
        val s=session(); s.move(1.0,1.0); s.scale(3.0); s.refresh()
        assertEquals(0,s.state().loads); assertNull(s.state().frame)
        s.showSample(); val f=requireNotNull(s.state().frame)
        assertEquals(1,s.state().loads)
        assertTrue(f.page.rawCandidates.size > f.selectedIds.size)
        assertTrue(f.page.groups.any { it.text==FullPageOcrAssociation.Text.DIFFERENT_RAW })
        assertTrue(f.page.rawCandidates.any { it.rawText.isEmpty() })
    }
    @Test fun moveScalePanResizeReuseSamePageAndOriginalAcquiredTime() {
        val s=session(); s.showSample(); val f=requireNotNull(s.state().frame)
        now=250; s.move(20.0,30.0); s.resize(-25.0,10.0); s.scale(4.0); s.pan(40.0,10.0)
        val next=requireNotNull(s.state().frame)
        assertSame(f.page,next.page); assertSame(f.metadata,next.metadata); assertEquals(1,s.state().loads)
    }
    @Test fun independentFreeCornerAndZoomUseSourceCoordinates() {
        val s=session(); val first=s.state(); s.resize(40.0,-20.0)
        val resized=s.state(); assertEquals(first.roi.width+40,resized.roi.width,0.0)
        assertEquals(first.roi.height+20,resized.roi.height,0.0); assertEquals(2.0,resized.scale,0.0)
        s.scale(3.35); assertEquals(resized.roi,s.state().roi); assertEquals(3.35,s.state().scale,0.0001)
        s.scale(10.0); assertEquals(5.0,s.state().scale,0.0); s.scale(-2.0); assertEquals(1.0,s.state().scale,0.0)
    }
    @Test fun menuRestoresCollapsedModeWithoutReloadingOrOldText() {
        val s=session(); s.showSample(); s.collapse(); s.menu(); assertEquals(RegionVisualSession.Mode.MENU,s.state().mode)
        s.closeMenu(); assertEquals(RegionVisualSession.Mode.COLLAPSED,s.state().mode); assertNull(s.state().frame)
        s.restore(); assertEquals(RegionVisualSession.Mode.EXPANDED,s.state().mode); assertNull(s.state().frame)
        assertEquals(1,s.state().loads)
    }
    @Test fun collapsedRestorePreservesRegionScaleAndPanButRequiresAnotherClick() {
        val s=session(); s.showSample(); s.scale(4.0); s.pan(40.0,10.0); val before=s.state()
        s.collapse(); s.restore(); val after=s.state()
        assertEquals(before.roi,after.roi); assertEquals(before.scale,after.scale,0.0)
        assertEquals(before.panX,after.panX,0.0); assertEquals(before.panY,after.panY,0.0); assertNull(after.frame)
        s.showSample(); assertNotNull(s.state().frame); assertEquals(2,s.state().loads)
    }
    @Test fun blankPageIsNotAnUntriggeredOrExpiredPage() {
        val s=session(); s.showSample(); s.nextPage(); assertNull(s.state().frame); assertEquals(1,s.state().pageIndex)
        s.showSample(); val f=requireNotNull(s.state().frame); assertTrue(f.page.rawCandidates.isEmpty())
        assertEquals(2,s.state().loads); s.nextPage(); assertNull(s.state().frame)
    }
    @Test fun originalSixtySecondDeadlineClearsAndCannotReviveOnClockRollback() {
        val s=session(); s.showSample(); now=60099; s.refresh(); assertNotNull(s.state().frame)
        now=60100; s.refresh(); assertNull(s.state().frame); assertEquals(1,s.state().loads)
        now=200; s.refresh(); assertNull(s.state().frame)
    }
    @Test fun backgroundAndStopClearDisplayedResultsWithoutAutoReload() {
        val s=session(); s.showSample(); s.foreground(false); assertNull(s.state().frame)
        s.foreground(true); assertNull(s.state().frame); assertEquals(1,s.state().loads)
        s.showSample(); s.stop(); assertEquals(RegionVisualSession.Mode.STOPPED,s.state().mode)
        assertNull(s.state().frame); s.refresh(); assertEquals(2,s.state().loads)
    }
    @Test fun extremeMovesAndResizesStayWithinPageAndRejectNonFiniteChanges() {
        val s=session(); s.move(10000.0,-10000.0); val moved=s.state().roi
        assertEquals(1200.0,moved.right,0.0); assertEquals(0.0,moved.top,0.0)
        s.resize(-10000.0,10000.0); val small=s.state().roi
        assertTrue(small.valid()); assertTrue(small.width>=48 && small.height>=48)
        val previous=s.state(); s.move(Double.NaN,0.0); s.scale(Double.NaN); s.pan(0.0,Double.POSITIVE_INFINITY)
        assertEquals(previous,s.state())
    }

    @Test fun viewportAndGeometryNormalizePanWithoutLoadingOrRenewing() {
        val s=session(); s.showSample(); val first=s.state().frame!!
        s.scale(5.0); s.pan(10000.0,10000.0)
        assertEquals(s.state().roi.width-600.0/5,s.state().panX,0.0)
        s.viewport(4000.0,3000.0)
        assertEquals(40.0,s.state().panX,0.0); assertEquals(0.0,s.state().panY,0.0)
        assertSame(first.metadata,s.state().frame!!.metadata); assertEquals(1,s.state().loads)
    }
    @Test fun fixtureUsesActualStripOverlapAndRetainsOffRoiConflictMember() {
        val s=session(); s.showSample(); val page=s.state().frame!!.page
        val pair=page.groups.single { it.text==FullPageOcrAssociation.Text.DIFFERENT_RAW }
        val cs=page.rawCandidates.filter { it.provenance.id in pair.memberIds }
        assertEquals(setOf(0,1),cs.map { it.provenance.stripIndex }.toSet())
        for(c in cs) {
            val p=c.provenance
            assertTrue(p.pageQuad.all { it.y>=336 && it.y<=463 })
            assertEquals(p.pageQuad,p.localQuad.map { it.copy(x=it.x+p.read.left,y=it.y+p.read.top) })
        }
        assertEquals(7,page.rawCandidates.size)
        assertTrue(page.identity!!.sourceBatch.startsWith("visual-synthetic/"))
    }
    @Test fun oldRefreshAfterNewExplicitShowUsesNewDeadline() {
        val s=session(); s.showSample(); now=59000; s.showSample()
        now=60100; s.refresh(); assertNotNull(s.state().frame); assertEquals(2,s.state().loads)
        now=119000; s.refresh(); assertNull(s.state().frame)
    }
    @Test fun clockRollbackDuringActiveDisplayClearsPermanently() {
        val s=session(); s.showSample(); now=200; s.refresh(); now=199; s.refresh()
        assertNull(s.state().frame); now=250; s.refresh(); assertNull(s.state().frame)
    }
    @Test fun stoppedExplicitRestartAndBackgroundClickBoundary() {
        val s=session(); s.showSample(); s.stop(); s.showSample()
        assertNotNull(s.state().frame); assertEquals(2,s.state().loads)
        s.foreground(false); s.showSample(); assertEquals(2,s.state().loads); assertNull(s.state().frame)
        s.foreground(true); s.refresh(); assertNull(s.state().frame)
    }

    @Test fun narrowSelectionKeepsOffRoiConflictingMemberInContext() {
        val s=session(); s.showSample()
        s.move(-6.0,-37.0); s.resize(-798.0,92.0)
        val f=s.state().frame!!
        assertEquals(listOf("visual-synthetic/tax"),f.selectedIds)
        assertEquals(setOf("visual-synthetic/tax","visual-synthetic/tax-alternate"),f.contextIds.toSet())
        assertEquals(7,f.page.rawCandidates.size); assertEquals(1,s.state().loads)
    }
}
