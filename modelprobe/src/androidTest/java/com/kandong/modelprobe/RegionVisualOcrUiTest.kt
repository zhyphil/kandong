package com.kandong.modelprobe

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UI tests of real fixed-fixture inference; synthetic gestures remain in RegionVisualLabUiTest. */
@RunWith(AndroidJUnit4::class)
class RegionVisualOcrUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity:RegionVisualLabActivity
    private lateinit var surface:RegionVisualLabSurface
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    @Before fun launch() {
        val intent=Intent(instrumentation.targetContext,RegionVisualLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity=instrumentation.startActivitySync(intent) as RegionVisualLabActivity
        instrumentation.waitForIdleSync()
        main {
            surface=activity.labSurface as RegionVisualLabSurface
            assertFalse(surface.session.synthetic); assertEquals(0,surface.session.state().loads)
            assertNull(surface.session.state().frame)
        }
        waitUntil { surface.isForeground() && surface.source.width>0 }
    }
    @After fun finish() {
        if(::activity.isInitialized) main { activity.finish() }
        instrumentation.waitForIdleSync()
        waitUntil { !RegionVisualOcrRuntime.slot.busy() }
    }
    private fun click(tag:String) = main {
        check(surface.isForeground())
        val button=checkNotNull(surface.getView().findViewWithTag<View>(tag))
        check(button.isShown); button.performClick()
    }
    private fun waitUntil(predicate:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+120000
        while(SystemClock.elapsedRealtime()<until) {
            var done=false; main { assertNull("Native runner failed",surface.realError()); done=predicate() }
            if(done) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Fixed-fixture UI condition timed out")
    }
    @Test fun defaultRealUiRendersFourPagesAndGeometryReusesThisInference() {
        for((index,spec) in FullPageVisualOcrRunner.PAGES.withIndex()) {
            click("lab_show")
            waitUntil { surface.session.state().frame!=null }
            main {
                val before=surface.session.state().frame!!
                val result=surface.realResult()!!
                assertEquals(spec.id,before.page.identity!!.pageFixtureId)
                assertEquals(spec.modelId,before.page.identity!!.model.id)
                assertSame(result.page,before.page); assertSame(result.metadata,before.metadata)
                assertTrue(result.diagnostics.originalCandidatesEqual); assertTrue(result.diagnostics.cleanupBalanced)
                surface.session.move(20.0,400.0); surface.session.resize(-20.0,-40.0)
                surface.session.scale(4.0); surface.session.pan(30.0,20.0); surface.session.refresh()
                val after=surface.session.state().frame!!
                assertSame(before.page,after.page); assertSame(before.metadata,after.metadata)
                assertSame(result,surface.realResult()); assertEquals(index+1,surface.session.state().loads)
                assertEquals(1176,surface.session.pageWidth); assertEquals(2400,surface.session.pageHeight)
                assertTrue(after.roi.right<=1176 && after.roi.bottom<=2400)
            }
            click("lab_page")
            main { assertNull(surface.session.state().frame); assertNull(surface.realResult()) }
        }
    }
    @Test fun mainRemainsResponsiveDuringRequestAndCancellationCannotRevive() {
        main {
            check(surface.isForeground())
            surface.getView().findViewWithTag<View>("lab_show").performClick()
            assertTrue(surface.nativeBusy())
            assertNull(surface.session.state().frame)
            // This main-thread callback executes while a native reservation is still held.
            surface.session.move(10.0,20.0); surface.session.scale(3.0)
            surface.getView().findViewWithTag<View>("lab_collapse").performClick()
            assertNull(surface.realResult()); assertNull(surface.session.state().frame)
            surface.getView().findViewWithTag<View>("lab_restore").performClick()
            assertNull(surface.session.state().frame)
        }
        waitUntil { !surface.nativeBusy() }
        instrumentation.waitForIdleSync()
        main {
            surface.session.refresh(); assertNull(surface.realResult()); assertNull(surface.session.state().frame)
            assertEquals(1,surface.session.state().loads)
        }
        click("lab_show"); waitUntil { surface.session.state().frame!=null }
        main { assertEquals(2,surface.session.state().loads) }
        // Actual Activity pause/destruction removes its subscription; no resume auto-read.
        main { activity.finish() }
        instrumentation.waitForIdleSync()
        main { assertNull(surface.realResult()); assertNull(surface.session.state().frame) }
    }

    @Test fun actualInferenceThenActivityReplacementRejectsLateResultAndWaitsForCleanup() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val monitor=instrumentation.addMonitor(RegionVisualLabActivity::class.java.name,null,false)
        val oldSurface=surface
        try {
            main {
                surface.installRealBackendForProbe(RegionVisualOcrBackend(instrumentation.targetContext.applicationContext,
                    instrumentation.context.assets) {
                    entered.countDown()
                    check(release.await(20,TimeUnit.SECONDS)) { "PROBE_RELEASE_TIMEOUT" }
                })
            }
            click("lab_show")
            assertTrue("actual OCR did not reach pre-close boundary",entered.await(40,TimeUnit.SECONDS))
            main {
                assertTrue(surface.nativeBusy()); assertNull(surface.realResult())
                activity.recreate()
            }
            activity=instrumentation.waitForMonitorWithTimeout(monitor,10000) as RegionVisualLabActivity
            instrumentation.waitForIdleSync()
            main {
                surface=activity.labSurface as RegionVisualLabSurface
                assertTrue(RegionVisualOcrRuntime.slot.busy())
                assertNull(oldSurface.realResult()); assertNull(oldSurface.session.state().frame)
                assertNull(surface.realResult()); assertEquals(0,surface.session.state().loads)
                surface.session.showSample() // Explicit attempt is refused until OLD native scopes close.
                assertEquals(0,surface.session.state().loads)
            }
            release.countDown()
            waitUntil { !RegionVisualOcrRuntime.slot.busy() }
            instrumentation.waitForIdleSync()
            main {
                assertFalse(RegionVisualOcrRuntime.slot.poisoned)
                assertNull(oldSurface.session.state().frame); assertNull(surface.session.state().frame)
                assertNull(surface.realResult()); assertEquals(0,surface.session.state().loads)
            }
            waitUntil { surface.isForeground() }
            click("lab_show"); waitUntil { surface.session.state().frame!=null }
            main { assertEquals(1,surface.session.state().loads); assertTrue(surface.realResult()!!.diagnostics.cleanupBalanced) }
        } finally {
            release.countDown(); instrumentation.removeMonitor(monitor)
        }
    }
}
