package com.kandong.modelprobe

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.ocrlab.context.ContextRect
import com.kandong.ocrlab.context.MirrorTransform
import com.kandong.ocrlab.context.capture.CaptureVersion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed PNG/Image equality and THIS inference's evidence preservation, never OCR-quality grading. */
@RunWith(AndroidJUnit4::class)
class FullPageVisualOcrProbeTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)

    @Test fun fourPreparedPagesDeliverIdenticalRgbaRowsToActualImageWithoutAnotherDecode() {
        val geometry=GeometryCleanup()
        val fixtures=FullPageOcrFixtures(instrumentation.context.assets,geometry)
        for((index,spec) in FullPageVisualOcrRunner.PAGES.withIndex()) {
            val page=fixtures.prepare(fixtures.pages.single { it.getString("id")==spec.id })
            assertEquals(2,page.decodedTileCount)
            val decoded=geometry.bitmapOpened
            val meta=RgbaFrameMetadata(page.width,page.height,CaptureVersion(1,index.toLong()+1,0,1,0,0),
                SystemClock.elapsedRealtime(),60000)
            fixtures.withFrame(page,meta) { image ->
                assertSame(meta,image.metadata)
                val plane=image.plane(); val bytes=plane.bytes.duplicate(); val base=bytes.position()
                val expected=IntArray(page.width)
                for(y in 0 until page.height) {
                    page.copyRowArgb(y,expected)
                    var equal=true
                    for(x in 0 until page.width) {
                        val offset=base+y*plane.rowStride+x*plane.pixelStride
                        val actual=((bytes.get(offset+3).toInt() and 255) shl 24) or
                            ((bytes.get(offset).toInt() and 255) shl 16) or
                            ((bytes.get(offset+1).toInt() and 255) shl 8) or (bytes.get(offset+2).toInt() and 255)
                        if(actual!=expected[x]) equal=false
                    }
                    assertTrue("Prepared raster/Image pixel mismatch",equal)
                }
                // Returned rows are copies; changing a caller buffer cannot alter the raster.
                page.copyRowArgb(0,expected); val first=expected.copyOf(); expected.fill(0)
                page.copyRowArgb(0,expected); assertArrayEquals(first,expected)
            }
            assertEquals(decoded,geometry.bitmapOpened)
            assertTrue(fixtures.balanced); assertTrue(geometry.balanced)
        }
    }

    @Test fun fourExplicitInferencesRetainRawEvidenceAndCloseBeforeGeometry() {
        val backendHolder=arrayOfNulls<RegionVisualOcrBackend>(1)
        main { backendHolder[0]=RegionVisualOcrBackend(instrumentation.targetContext.applicationContext,instrumentation.context.assets) }
        val backend=checkNotNull(backendHolder[0])
        try {
            for((index,spec) in FullPageVisualOcrRunner.PAGES.withIndex()) {
                main { assertTrue(backend.start(index)); assertNull(backend.result()) }
                val result=awaitResult(backend)
                val d=result.diagnostics
                assertEquals(spec.id,result.page.identity!!.pageFixtureId)
                assertEquals(spec.modelId,result.page.identity!!.model.id)
                assertEquals(spec.width,result.metadata.width); assertEquals(spec.height,result.metadata.height)
                assertEquals(60000L,result.metadata.ttlMillis)
                assertTrue(d.originalCandidatesEqual); assertTrue(d.cleanupBalanced)
                assertEquals(4,d.detectors); assertEquals(result.page.rawCandidates.size,d.recognitions)
                assertEquals(2,d.sessionsOpened); assertEquals(2,d.sessionsClosed); assertEquals(2,d.peakSessions)
                assertEquals(d.ortOpened,d.ortAttempts); assertEquals(d.ortOpened,d.ortClosed)
                assertEquals(d.matsOpened,d.matsAttempted); assertEquals(d.matsOpened,d.matsReleased)
                assertEquals(d.threadsBefore,d.threadsAfter)
                assertEquals(1,d.transport.getValue("imagesClosed")); assertEquals(1,d.transport.getValue("imageCloseAttempts"))
                assertEquals(result.page.rawCandidates.map { it.provenance.id }.toSet(),
                    result.page.groups.flatMap { it.memberIds }.toSet())
                main {
                    val roi=ContextRect(60.0,610.0,700.0,1300.0)
                    val a=FullPageRegionProjection.project(result.metadata,result.page,roi,MirrorTransform(2.0,600.0,400.0)) {
                        check(backend.result()===result)
                    }!!
                    val b=FullPageRegionProjection.project(result.metadata,result.page,roi,MirrorTransform(4.0,300.0,200.0,40.0,30.0)) {
                        check(backend.result()===result)
                    }!!
                    assertSame(a.page,b.page); assertSame(a.metadata,b.metadata); assertEquals(a.selectedIds,b.selectedIds)
                    assertSame(d,backend.result()!!.diagnostics); assertFalse(backend.busy())
                    backend.cancel(); assertNull(backend.result())
                }
            }
        } finally { main { backend.dispose() } }
    }
    private fun awaitResult(backend:RegionVisualOcrBackend):FullPageVisualOcrRunner.Result {
        val until=SystemClock.elapsedRealtime()+120000
        while(SystemClock.elapsedRealtime()<until) {
            var result:FullPageVisualOcrRunner.Result?=null
            main { assertNull("Local fixed-page runner failure",backend.error()); result=backend.result() }
            result?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Fixed-page OCR did not deliver")
    }
}
