package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test

class FullPageRegionControllerTest {
    private val version = CaptureVersion(1, 2, 3, 4, 0, 0)
    private var now = 100L
    private var current = version
    private var active = true
    private val controller = FullPageRegionController { CaptureCheckpoint(current, active, now) }
    private val roi = ContextRect(90.0, 570.0, 310.0, 630.0)
    private val mirror = MirrorTransform(2.0, 440.0, 120.0)
    private val model = FullPageOcrContract.models.first()
    private fun candidate(id: String, text: String, strip: Int, q: List<GeometryProbeContract.Point>, owns: Boolean) : FullPageOcrContract.Candidate {
        val s = FullPageStripPlanner.plan(1176, 2400).strips[strip]
        return FullPageOcrContract.Candidate(FullPageOcrContract.Provenance(version, "region-page", strip,
            s.read, s.core, 0, 0, 0, 0, q.map { it.copy(y = it.y - s.read.top) }, q, .85, owns, id),
            model.id, text, 320, 40)
    }
    private fun box(l: Double, t: Double, r: Double, b: Double) = listOf(
        GeometryProbeContract.Point(l, t), GeometryProbeContract.Point(r, t),
        GeometryProbeContract.Point(r, b), GeometryProbeContract.Point(l, b))
    private fun inputs() = listOf(candidate("a", "稅", 0, box(100.0,580.0,300.0,620.0), true),
        candidate("b", "税", 1, box(102.0,582.0,302.0,622.0), false),
        candidate("condition", "不含税，最多8元", 0, box(700.0,100.0,900.0,140.0), true),
        candidate("empty", "", 2, box(500.0,1500.0,600.0,1520.0), false))
    private fun pending(ticket: FullPageRegionController.Ticket, cs: List<FullPageOcrContract.Candidate> = inputs(),
        acquired: Long = now, ttl: Long = 1000): FullPageOcrPublication.Pending {
        val meta = RgbaFrameMetadata(1176,2400,version,acquired,ttl)
        val plan = FullPageStripPlanner.plan(meta.width,meta.height)
        val input = FullPageOcrPublication.Input(true,null,cs,plan.strips.map { s ->
            FullPageOcrPublication.Strip(s.index,s.read,s.core,s.detector.width,s.detector.height,"COMPLETE",
                cs.count { it.provenance.stripIndex == s.index }) },plan.strips.size,cs.size,1,1,null)
        return FullPageOcrPublication.stage("region-run","region-page",meta,model,
            "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
            "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af",input,
            FullPageOcrPublication.Guard(meta) { controller.checkpoint(ticket) })
    }
    private fun click(): FullPageRegionController.Ticket { controller.observePage(version); return requireNotNull(controller.click()) }
    private fun activate(cs: List<FullPageOcrContract.Candidate> = inputs()): FullPageRegionController.Ticket {
        val t=click(); assertTrue(controller.accept(t,pending(t,cs).claimForRegion(true,true))); return t
    }
    @Test fun observationDoesNotReadAndBusyClickCreatesNoSecondTicket() {
        var reads=0; val c=FullPageRegionController { reads++; CaptureCheckpoint(version,true,100) }
        c.observePage(version); assertEquals(0,reads); assertNull(c.render(roi,mirror)); assertEquals(0,reads)
        assertNotNull(c.click()); assertNull(c.click())
    }
    @Test fun wholePageAndOffRoiSpatialMembersSurviveWithoutChoosingConflict() {
        activate(); val f=requireNotNull(controller.render(ContextRect(100.0,580.0,101.0,581.0),MirrorTransform(2.0,20.0,20.0)))
        assertEquals(listOf("a"),f.selectedIds); assertEquals(setOf("a","b"),f.contextIds.toSet())
        assertEquals(setOf("a","b","condition","empty"),f.page.rawCandidates.map { it.provenance.id }.toSet())
        assertEquals(listOf("稅","税","不含税，最多8元",""),inputs().map { c -> f.page.rawCandidates.single { it.provenance.id==c.provenance.id }.rawText })
        assertTrue(f.page.groups.any { it.text==FullPageOcrAssociation.Text.DIFFERENT_RAW && it.agreedRaw==null })
        assertEquals(100L,f.metadata.acquiredAtMillis); assertEquals(1000L,f.metadata.ttlMillis)
        assertEquals(ContextRect(0.0,0.0,2.0,2.0),f.visible.single().mirrorRect)
    }
    @Test fun sourceSelectionDoesNotChangeWhenOnlyMirrorScaleAndPanChange() {
        activate(); val a=requireNotNull(controller.render(roi,mirror))
        val b=requireNotNull(controller.render(roi,MirrorTransform(3.0,120.0,90.0,40.0,0.0)))
        assertEquals(a.selectedIds,b.selectedIds); assertEquals(a.page,b.page)
        assertEquals(ContextRect(0.0,30.0,120.0,90.0),b.visible.single { it.candidateId=="a" }.mirrorRect)
    }
    @Test fun blankIsValidButExpiredPageIsAbsentAndCannotRevive() {
        activate(emptyList()); val f=requireNotNull(controller.render(roi,mirror)); assertTrue(f.selectedIds.isEmpty())
        now=1100; assertNull(controller.render(roi,mirror)); now=101; assertNull(controller.render(roi,mirror))
    }
    @Test fun permitTransfersOnceAndOldPendingCleanupCannotRevokeNewOwner() {
        val t=click(); val p=pending(t); val permit=requireNotNull(p.claimForRegion(true,true))
        assertNull(p.claimForRegion(true,true)); assertFalse(p.publish(true,true).published); p.discard()
        assertTrue(controller.accept(t,permit)); assertNotNull(controller.render(roi,mirror))
        assertFalse(controller.accept(t,permit)); permit.close(); assertNotNull(controller.render(roi,mirror))
    }
    @Test fun pauseMenuStopAndOldCallbacksCannotRestoreOrClearNewerPage() {
        for (reason in listOf(ClearReason.PAUSE,ClearReason.MENU,ClearReason.STOP)) {
            val t=activate(); controller.clear(reason); assertNull(controller.render(roi,mirror))
            val next=activate(); assertFalse(controller.accept(t,null)); assertNotNull(controller.render(roi,mirror))
            assertFalse(controller.accept(next,null)); assertNotNull(controller.render(roi,mirror))
            controller.clear(reason)
        }
    }
    @Test fun clockHistorySpansStagingHandoffAndRender() {
        val t=click(); val p=pending(t); now=500; val permit=requireNotNull(p.claimForRegion(true,true))
        now=499; assertFalse(controller.accept(t,permit)); assertNull(controller.render(roi,mirror))
    }
    @Test fun evidenceAcquiredBeforeClickCannotBecomeCurrent() {
        val t=click(); assertFalse(controller.accept(t,pending(t,acquired=99).claimForRegion(true,true)))
        assertNull(controller.render(roi,mirror))
    }
    @Test fun revokedGuardCannotSucceedAfterReentrantCallback() {
        val meta=RgbaFrameMetadata(1176,2400,version,100,1000)
        lateinit var g: FullPageOcrPublication.Guard
        g=FullPageOcrPublication.Guard(meta) { g.finish(); CaptureCheckpoint(version,true,101) }
        assertFalse(g.isCurrent()); assertFalse(g.isCurrent())
    }
}
