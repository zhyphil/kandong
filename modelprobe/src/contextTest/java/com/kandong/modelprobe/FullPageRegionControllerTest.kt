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
    private fun staged(c: FullPageRegionController, t: FullPageRegionController.Ticket,
        cs: List<FullPageOcrContract.Candidate> = inputs(), metadata: RgbaFrameMetadata =
            RgbaFrameMetadata(1176,2400,version,now,1000),
        guard: FullPageOcrPublication.Guard = FullPageOcrPublication.Guard(metadata) { c.checkpoint(t) }
    ): FullPageOcrPublication.Pending {
        val plan = FullPageStripPlanner.plan(metadata.width, metadata.height)
        return FullPageOcrPublication.stage("region-run", "region-page", metadata, model,
            "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
            "72ad5c46d5bbc22921613539feaa90e2c4c4567bfb107a7e741be72e5e54a1af",
            FullPageOcrPublication.Input(true,null,cs,plan.strips.map { s -> FullPageOcrPublication.Strip(
                s.index,s.read,s.core,s.detector.width,s.detector.height,"COMPLETE",
                cs.count { it.provenance.stripIndex == s.index }) },plan.strips.size,cs.size,1,1,null),guard)
    }
    @Test fun failedScopeOrCleanupConsumesProofAndFinishesGuard() {
        for ((scope, cleanup) in listOf(false to true, true to false, false to false)) {
            val t=click(); val meta=RgbaFrameMetadata(1176,2400,version,now,1000)
            val g=FullPageOcrPublication.Guard(meta) { controller.checkpoint(t) }
            val p=staged(controller,t,guard=g)
            assertNull(p.claimForRegion(scope,cleanup)); assertFalse(g.isCurrent())
            assertNull(p.claimForRegion(true,true)); assertFalse(p.publish(true,true).published)
            assertFalse(controller.accept(t,null)); assertNull(controller.render(roi,mirror))
        }
        val t=click(); val p=pending(t)
        assertTrue(p.publish(true,true).published); assertNull(p.claimForRegion(true,true))
    }
    @Test fun constructingPendingFromPublishedEvidenceCannotMintRegionAuthority() {
        val t=click(); val archived=requireNotNull(pending(t).publish(true,true).association)
        val meta=RgbaFrameMetadata(1176,2400,version,now,1000)
        val g=FullPageOcrPublication.Guard(meta) { controller.checkpoint(t) }
        val forged=FullPageOcrPublication.Pending(archived,g)
        assertNull(forged.claimForRegion(true,true)); assertFalse(g.isCurrent())
        assertFalse(controller.accept(t,null)); assertNull(controller.render(roi,mirror))
    }
    @Test fun closeBeforeAcceptAndFailureAfterMintReleaseOnlyUnclaimedPermit() {
        val t=click(); val meta=RgbaFrameMetadata(1176,2400,version,now,1000)
        val g=FullPageOcrPublication.Guard(meta) { controller.checkpoint(t) }
        val p=staged(controller,t,guard=g); val permit=requireNotNull(p.claimForRegion(true,true))
        permit.close(); permit.close(); assertFalse(g.isCurrent())
        assertFalse(controller.accept(t,permit)); assertNull(controller.render(roi,mirror))
        val next=click(); val staged=pending(next); val abandoned=requireNotNull(staged.claimForRegion(true,true))
        controller.clear(ClearReason.STOP); staged.discard(); abandoned.close()
        assertFalse(controller.accept(next,abandoned)); assertNull(controller.render(roi,mirror))
    }
    @Test fun foreignAndFabricatedTicketsDoNotTickOrRevokeCurrentOwner() {
        var reads=0
        val c=FullPageRegionController { reads++; CaptureCheckpoint(version,true,now) }
        c.observePage(version); val own=requireNotNull(c.click())
        assertTrue(c.accept(own,staged(c,own).claimForRegion(true,true)))
        val foreign=click(); val meta=RgbaFrameMetadata(1176,2400,version,now,1000)
        val g=FullPageOcrPublication.Guard(meta) { controller.checkpoint(foreign) }
        val rejected=requireNotNull(staged(controller,foreign,guard=g).claimForRegion(true,true))
        val before=reads
        assertFalse(c.accept(foreign,rejected)); assertEquals(before,reads); assertFalse(g.isCurrent())
        assertFalse(c.accept(FullPageRegionController.Ticket(),null)); assertEquals(before,reads)
        assertThrows(java.util.concurrent.CancellationException::class.java) { c.checkpoint(foreign) }
        assertEquals(before,reads); assertNotNull(c.render(roi,mirror))
    }
    @Test fun everyVersionFieldRevokesBothObservedAndSourceOnlyChanges() {
        val versions=listOf(version.copy(session=8),version.copy(snapshot=8),version.copy(page=8),
            version.copy(revision=8),version.copy(window=8),version.copy(display=8))
        for (changed in versions) {
            current=version; val t=activate()
            current=changed; assertNull(controller.render(roi,mirror))
            current=version; assertNull(controller.render(roi,mirror))
            assertThrows(java.util.concurrent.CancellationException::class.java) { controller.checkpoint(t) }
            activate(); controller.observePage(changed); controller.observePage(version)
            assertNull(controller.render(roi,mirror))
        }
    }
    @Test fun sourceExceptionInactiveAndClockRollbackRequireNewExplicitAuthorization() {
        var failure=false; var enabled=true
        val c=FullPageRegionController {
            if(failure) error("CHECKPOINT_FAILED")
            CaptureCheckpoint(version,enabled,now)
        }
        c.observePage(version); failure=true; assertNull(c.click()); failure=false
        val t=requireNotNull(c.click()); assertTrue(c.accept(t,staged(c,t).claimForRegion(true,true)))
        failure=true; assertNull(c.render(roi,mirror)); failure=false; assertNull(c.render(roi,mirror))
        val next=requireNotNull(c.click()); assertTrue(c.accept(next,staged(c,next).claimForRegion(true,true)))
        enabled=false; assertNull(c.render(roi,mirror)); enabled=true
        now=500; val last=requireNotNull(c.click()); c.clear(ClearReason.MENU)
        now=499; assertNull(c.click()); now=501
        assertThrows(java.util.concurrent.CancellationException::class.java) { c.checkpoint(last) }
        assertNull(c.render(roi,mirror)); assertNotNull(c.click())
    }
    @Test fun newClickAndInvalidCurrentReplacementDropOldPageBeforeValidation() {
        val old=activate(); val next=requireNotNull(controller.click())
        assertNull(controller.render(roi,mirror))
        assertFalse(controller.accept(next,null)); assertNull(controller.render(roi,mirror))
        assertThrows(java.util.concurrent.CancellationException::class.java) { controller.checkpoint(next) }
        val latest=activate(); assertFalse(controller.accept(old,null)); assertFalse(controller.accept(latest,null))
        assertNotNull(controller.render(roi,mirror))
        val attempt=requireNotNull(controller.click())
        val other=version.copy(snapshot=999)
        val meta=RgbaFrameMetadata(1176,2400,other,now,1000)
        val wrong=staged(controller,attempt,emptyList(),meta,
            FullPageOcrPublication.Guard(meta) { CaptureCheckpoint(other,true,now) })
        assertFalse(controller.accept(attempt,wrong.claimForRegion(true,true)))
        assertNull(controller.render(roi,mirror))
    }
    @Test fun futureAcquisitionCannotBeAdmittedByAnIndependentProducerClock() {
        val t=click(); val meta=RgbaFrameMetadata(1176,2400,version,now+1,1000)
        val p=staged(controller,t,emptyList(),meta,
            FullPageOcrPublication.Guard(meta) { CaptureCheckpoint(version,true,now+1) })
        assertFalse(controller.accept(t,p.claimForRegion(true,true)))
        assertNull(controller.render(roi,mirror))
    }
    @Test fun envelopesMarkRotatedAndUnorderedQuadsAndRetainUnsupportedGeometry() {
        val diamond=listOf(GeometryProbeContract.Point(200.0,100.0),GeometryProbeContract.Point(300.0,200.0),
            GeometryProbeContract.Point(200.0,300.0),GeometryProbeContract.Point(100.0,200.0))
        val rect=box(100.0,100.0,300.0,300.0)
        val cs=listOf(candidate("diamond","斜",0,diamond,false),
            candidate("unordered","",0,listOf(rect[0],rect[2],rect[1],rect[3]),true),
            candidate("line","退化",0,box(400.0,100.0,400.0,300.0),true),
            candidate("axis","框",0,rect,true),
            candidate("touch","相接",0,box(110.0,100.0,150.0,110.0),true))
        activate(cs)
        // The diamond's true polygon misses this ROI: this slice deliberately reports its AABB.
        val f=requireNotNull(controller.render(ContextRect(100.0,100.0,110.0,110.0),MirrorTransform(2.0,20.0,20.0)))
        assertEquals(setOf("axis","diamond","unordered"),f.selectedIds.toSet())
        assertEquals(setOf("diamond","unordered"),f.visible.filter { it.conservative }.map { it.candidateId }.toSet())
        assertEquals(listOf("line"),f.unsupportedIds); assertEquals(5,f.page.rawCandidates.size)
        assertEquals(diamond,f.page.rawCandidates.single { it.provenance.id=="diamond" }.provenance.pageQuad)
        assertEquals("",f.page.rawCandidates.single { it.provenance.id=="unordered" }.rawText)
    }
    @Test fun pageClippingPartialIntersectionAndClampedSourcePanHaveConcreteCoordinates() {
        activate(listOf(candidate("edge","",0,box(0.0,0.0,30.0,30.0),false)))
        val f=requireNotNull(controller.render(ContextRect(-20.0,-10.0,20.0,10.0),MirrorTransform(2.0,10.0,10.0,999.0,-3.0)))
        assertEquals(ContextRect(0.0,0.0,20.0,10.0),f.roi)
        assertEquals(MirrorTransform(2.0,10.0,10.0,15.0,0.0),f.transform)
        assertEquals(ContextRect(0.0,0.0,20.0,10.0),f.visible.single().sourceIntersection)
        assertEquals(ContextRect(0.0,0.0,10.0,10.0),f.visible.single().mirrorRect)
        assertEquals(listOf("edge"),f.selectedIds); assertFalse(f.visible.single().conservative)
        val off=requireNotNull(controller.render(ContextRect(0.0,0.0,100.0,100.0),MirrorTransform(2.0,10.0,10.0,95.0,95.0)))
        assertEquals(listOf("edge"),off.selectedIds); assertTrue(off.visible.isEmpty())
    }
    @Test fun invalidRoiAndTransformNeverReturnPreviousRendering() {
        activate(); assertNotNull(controller.render(roi,mirror))
        for (r in listOf(ContextRect(Double.NaN,0.0,100.0,100.0),ContextRect(0.0,0.0,0.0,100.0),
            ContextRect(2000.0,0.0,2100.0,100.0))) assertNull(controller.render(r,mirror))
        for (t in listOf(mirror.copy(scale=.9),mirror.copy(scale=5.1),mirror.copy(width=0.0),
            mirror.copy(height=10001.0),mirror.copy(panX=Double.NaN),mirror.copy(scale=Double.POSITIVE_INFINITY)))
            assertNull(controller.render(roi,t))
        assertNotNull(controller.render(roi,mirror))
    }
    @Test fun finalProjectionCallbackCannotPublishAfterClearOrReplacement() {
        var reads=0; var action: (() -> Unit)?=null
        val c=FullPageRegionController { reads++; action?.invoke(); CaptureCheckpoint(version,true,now) }
        fun install() {
            c.observePage(version); val t=requireNotNull(c.click())
            assertTrue(c.accept(t,staged(c,t).claimForRegion(true,true)))
        }
        install(); val before=reads; assertNotNull(c.render(roi,mirror)); val renderReads=reads-before
        assertTrue(renderReads>1)
        for (replace in listOf(false,true)) {
            if (!replace) Unit else install()
            val target=reads+renderReads
            action={ if(reads==target) {
                action=null; c.clear(ClearReason.PAUSE)
                if(replace) {
                    c.observePage(version); val t=requireNotNull(c.click())
                    assertTrue(c.accept(t,staged(c,t,emptyList()).claimForRegion(true,true)))
                }
            } }
            assertNull(c.render(roi,mirror)); action=null
            if(replace) assertTrue(requireNotNull(c.render(roi,mirror)).page.rawCandidates.isEmpty())
            else assertNull(c.render(roi,mirror))
        }
    }
    @Test fun reentrantObservationDuringClickAndExceptionAfterReplacementPreserveIdentity() {
        var action: (() -> Unit)?=null
        val c=FullPageRegionController { action?.invoke(); CaptureCheckpoint(version,true,now) }
        c.observePage(version)
        action={ action=null; c.observePage(version.copy(page=10)); c.observePage(version) }
        assertNull(c.click()); assertNull(c.render(roi,mirror))
        val t=requireNotNull(c.click()); assertTrue(c.accept(t,staged(c,t).claimForRegion(true,true)))
        action={
            action=null; c.clear(ClearReason.PAGE_CHANGE)
            val next=requireNotNull(c.click()); assertTrue(c.accept(next,staged(c,next,emptyList()).claimForRegion(true,true)))
            error("OLD_CALLBACK_FAILED")
        }
        assertNull(c.render(roi,mirror)); assertTrue(requireNotNull(c.render(roi,mirror)).selectedIds.isEmpty())
    }
    @Test fun immutableEvidenceSurvivesCallerMutationWithoutRereadingInputOrChangingEvidence() {
        var contentReads=0
        val rows=inputs().toMutableList()
        val sourceRows=object : AbstractList<FullPageOcrContract.Candidate>() {
            override val size get()=rows.size
            override fun get(index: Int)=rows[index].also { contentReads++ }
        }
        val t=click(); val p=pending(t,sourceRows); val readsAfterStaging=contentReads
        rows.clear(); assertTrue(controller.accept(t,p.claimForRegion(true,true)))
        val a=requireNotNull(controller.render(roi,mirror))
        val b=requireNotNull(controller.render(ContextRect(450.0,1400.0,650.0,1600.0),MirrorTransform(4.0,100.0,200.0,25.0,20.0)))
        assertEquals(readsAfterStaging,contentReads); assertSame(a.page,b.page); assertSame(a.metadata,b.metadata)
        assertEquals(listOf("empty"),b.selectedIds)
        val collections=listOf(a.selectedIds,a.visible,a.contextIds,a.unsupportedIds,a.page.rawCandidates,a.page.edges,a.page.groups,
            a.page.groups.first().memberIds,a.page.groups.first().reasons,
            a.page.rawCandidates.first().provenance.localQuad,a.page.rawCandidates.first().provenance.pageQuad)
        collections.forEach { items -> assertThrows(UnsupportedOperationException::class.java) { (items as MutableList<*>).clear() } }
        controller.clear(ClearReason.STOP); assertNull(controller.render(roi,mirror))
        assertEquals(4,a.page.rawCandidates.size) // A caller's immutable copy is not a live authorization.
    }
    @Test fun diagonalZeroAreaGeometryIsRetainedWithoutAFabricatedEnvelopeAnchor() {
        val q=listOf(GeometryProbeContract.Point(100.0,100.0),GeometryProbeContract.Point(150.0,150.0),
            GeometryProbeContract.Point(200.0,200.0),GeometryProbeContract.Point(250.0,250.0))
        activate(listOf(candidate("diagonal","退化斜线",0,q,false)))
        val f=requireNotNull(controller.render(ContextRect(100.0,100.0,300.0,300.0),MirrorTransform(2.0,400.0,400.0)))
        assertEquals(listOf("diagonal"),f.unsupportedIds)
        assertTrue(f.selectedIds.isEmpty()); assertTrue(f.visible.isEmpty())
        assertEquals(q,f.page.rawCandidates.single().provenance.pageQuad)
        assertEquals("退化斜线",f.page.rawCandidates.single().rawText)
    }
}
