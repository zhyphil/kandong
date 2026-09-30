package com.kandong.modelprobe

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.InputDevice
import android.widget.ScrollView
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real fixed-page OCR UI; local reply placeholders are not translation quality evidence. */
@RunWith(AndroidJUnit4::class)
class RegionVisualTranslationUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity:RegionVisualLabActivity
    private lateinit var surface:RegionVisualLabSurface
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    @Before fun launch() {
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,RegionVisualLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RegionVisualLabActivity
        instrumentation.waitForIdleSync()
        main { surface=activity.labSurface as RegionVisualLabSurface }
        await { surface.isForeground() && surface.source.width>0 }
    }
    @After fun finish() {
        if(::activity.isInitialized) main { activity.finish() }
        instrumentation.waitForIdleSync()
        await { !RegionVisualOcrRuntime.slot.busy() }
    }
    private fun await(predicate:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+120000
        while(SystemClock.elapsedRealtime()<deadline) {
            var done=false; main { done=predicate() }
            if(done) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Translation demo UI timed out")
    }
    private fun button(tag:String):View {
        val result=surface.getView().findViewWithTag<View>(tag)
        assertNotNull("Missing explicit UI control: $tag",result)
        return result!!
    }
    private fun texts(view:View):String = when(view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { texts(view.getChildAt(it)) }
        else -> ""
    }
    private fun cards()=texts(button("lab_cards"))
    private fun hasMarker(text:String)=Regex("绑定演示 [0-9]+").containsMatchIn(text)
    private fun assertNoCards() {
        val container=button("lab_cards") as ViewGroup
        assertTrue((0 until container.childCount).none { container.getChildAt(it).tag.toString().startsWith("lab_translation_candidate_") })
        assertFalse(cards(),hasMarker(cards()))
    }
    private fun rootDrag(target:View,x:Float,y:Float,dx:Float,dy:Float) {
        var left=0f; var top=0f
        main {
            val a=IntArray(2); val b=IntArray(2)
            target.getLocationOnScreen(a); surface.getView().getLocationOnScreen(b)
            left=(a[0]-b[0]).toFloat(); top=(a[1]-b[1]).toFloat()
        }
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,step:Int) = main {
            check(surface.isForeground())
            val event=MotionEvent.obtain(down,down+step*20L,action,left+x+dx*step/10,top+y+dy*step/10,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(surface.getView().dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN,0)
        for(i in 1..9) send(MotionEvent.ACTION_MOVE,i)
        send(MotionEvent.ACTION_UP,10); instrumentation.waitForIdleSync()
    }
    /** Intentionally delivers even cancelled messages, modelling an already queued foreign reply. */
    private class HeldReplies:RegionVisualDemoReplies {
        data class Delivery(val run:RegionVisualTranslationController.Run,
            val request:FullPageTranslationProbe.ProbeRequest,val receiver:RegionVisualDemoReplies.Receiver)
        val deliveries=mutableListOf<Delivery>()
        var cancellations=0
        override fun schedule(run:RegionVisualTranslationController.Run,request:FullPageTranslationProbe.ProbeRequest,
            receiver:RegionVisualDemoReplies.Receiver):RegionVisualDemoReplies.Handle {
            deliveries.add(Delivery(run,request,receiver))
            return RegionVisualDemoReplies.Handle { cancellations++ }
        }
        fun emit(index:Int,fail:Boolean=false) {
            val d=deliveries[index]
            d.receiver.completed(d.run,d.request,if(fail) null else RegionVisualDemoReplies.markers(d.request))
        }
    }
    @Test fun explicitDemoEntryStartsFreshOcrAndLifecycleNeverAutoReads() {
        main {
            val translate=button("lab_translate")
            assertTrue(translate.isShown)
            assertTrue((translate as TextView).text.contains("演示"))
            assertEquals(0,surface.session.state().loads)
            assertNull(surface.realResult())
            translate.performClick()
            assertEquals(1,surface.session.state().loads)
        }
        await { surface.session.state().frame!=null }
        main {
            assertTrue(surface.realResult()!!.diagnostics.cleanupBalanced)
            button("lab_collapse").performClick()
            assertNull(surface.realResult()); assertNull(surface.session.state().frame)
            button("lab_restore").performClick()
            assertNull(surface.realResult()); assertNull(surface.session.state().frame)
            assertEquals(1,surface.session.state().loads)
        }
    }
    @Test fun fourPagesDemoAndOriginalToggleReuseSameFullPageInference() {
        FullPageVisualOcrRunner.PAGES.forEachIndexed { index,spec ->
            main { button("lab_translate").performClick() }
            await { !surface.nativeBusy() && surface.session.state().frame!=null }
            main {
                val result=checkNotNull(surface.realResult())
                assertEquals(spec.id,result.page.identity!!.pageFixtureId)
                assertEquals(index+1,surface.session.state().loads)
                assertTrue(result.diagnostics.originalCandidatesEqual)
                button("lab_demo").performClick() // Flush the asynchronous reply into the actual card views.
                val demo=cards()
                val rendered=checkNotNull(surface.translationRender()).cards
                if(index<2) {
                    assertTrue(demo,hasMarker(demo))
                    rendered.filter { it.chinese!=null }.forEach { card ->
                        assertTrue(demo,demo.contains(card.chinese!!)); assertTrue(demo,demo.contains(card.sourceText))
                    }
                } else {
                    assertFalse(demo,hasMarker(demo)); assertTrue(rendered.all { it.chinese==null })
                }
                button("lab_original").performClick()
                val original=cards()
                assertFalse(original,hasMarker(original))
                rendered.forEach { assertTrue(original,original.contains(it.sourceText)) }
                button("lab_demo").performClick()
                surface.session.move(10.0,300.0); surface.session.resize(-30.0,-20.0)
                surface.session.scale(3.0); surface.session.pan(20.0,15.0)
                assertSame(result,surface.realResult())
                assertEquals(index+1,surface.session.state().loads)
                button("lab_page").performClick()
                assertNull(surface.realResult()); assertNull(surface.session.state().frame)
                assertNoCards()
            }
        }
    }
    @Test fun rejectedBusyOcrClickDoesNotReplaceDemoAndCancelClearsCards() {
        main {
            button("lab_translate").performClick()
            button("lab_show").performClick()
            assertEquals(1,surface.session.state().loads)
        }
        await { !surface.nativeBusy() && surface.session.state().frame!=null }
        main {
            button("lab_demo").performClick()
            assertTrue(cards(),hasMarker(cards()))
            button("lab_cancel").performClick()
            assertNull(surface.realResult()); assertNull(surface.session.state().frame)
            assertNoCards()
            button("lab_demo").performClick(); button("lab_original").performClick()
            assertEquals(1,surface.session.state().loads)
            assertNull(surface.realResult())
        }
    }
    @Test fun heldAndFailedRepliesCannotReviveCancelledRunOrReplaceNewRequest() {
        val replies=HeldReplies()
        lateinit var backend:RegionVisualOcrBackend
        main {
            backend=RegionVisualOcrBackend(instrumentation.targetContext.applicationContext,instrumentation.context.assets)
            backend.installReplySchedulerForProbe(replies)
            surface.installRealBackendForProbe(backend)
            button("lab_translate").performClick()
        }
        await { replies.deliveries.size==1 }
        main {
            assertEquals(RegionVisualTranslationController.Phase.WAITING,backend.translation.state(SystemClock.elapsedRealtime()).phase)
            val receipt=backend.originalReceiptForProbe()!!
            assertEquals(receipt.value.metadata.acquiredAtMillis,receipt.acquiredAtMillis)
            assertTrue(receipt.validatedAtMillis>=receipt.acquiredAtMillis)
            button("lab_cancel").performClick()
            assertEquals(1,replies.cancellations); assertNull(surface.realResult())
            button("lab_translate").performClick()
        }
        await { replies.deliveries.size==2 }
        main {
            replies.emit(0); replies.emit(0,true)
            assertEquals(1,replies.cancellations)
            assertEquals(RegionVisualTranslationController.Phase.WAITING,backend.translation.state(SystemClock.elapsedRealtime()).phase)
            replies.emit(1,true)
            assertEquals(RegionVisualTranslationController.Phase.ERROR,backend.translation.state(SystemClock.elapsedRealtime()).phase)
            assertNull(surface.realResult()); assertNull(surface.session.state().frame)
            button("lab_translate").performClick()
        }
        await { replies.deliveries.size==3 }
        main {
            replies.emit(1); replies.emit(2)
            assertEquals(RegionVisualTranslationController.Phase.READY,backend.translation.state(SystemClock.elapsedRealtime()).phase)
            assertNotNull(surface.realResult()); assertEquals(3,surface.session.state().loads)
            button("lab_open_menu").performClick()
            assertNull(surface.realResult()); assertTrue(backend.translation.sourceMap(SystemClock.elapsedRealtime()).isEmpty())
        }
    }
    @Test fun originalAcquisitionDeadlineExpiresPendingReplyAndOneFreshClickWorks() {
        val replies=HeldReplies()
        lateinit var backend:RegionVisualOcrBackend
        var acquired=0L
        main {
            backend=RegionVisualOcrBackend(instrumentation.targetContext.applicationContext,instrumentation.context.assets)
            backend.installReplySchedulerForProbe(replies); surface.installRealBackendForProbe(backend)
            button("lab_translate").performClick()
        }
        await { replies.deliveries.size==1 }
        main { acquired=backend.originalReceiptForProbe()!!.acquiredAtMillis }
        // Real monotonic sixty-second acquisition TTL; no clock rewriting or replacement deadline.
        await { SystemClock.elapsedRealtime()>=acquired+60000 }
        main {
            surface.session.refresh()
            assertNull(surface.realResult()); assertNull(surface.session.state().frame)
            assertTrue(backend.translation.sourceMap(SystemClock.elapsedRealtime()).isEmpty())
            assertEquals(1,replies.cancellations)
            replies.emit(0)
            assertNull(surface.realResult())
            button("lab_translate").performClick()
            assertEquals(2,surface.session.state().loads)
        }
        await { replies.deliveries.size==2 }
        main { replies.emit(1); assertNotNull(surface.realResult()) }
    }
    @Test fun realLayoutRoutesSourceDragAndNestedCardScrollThroughActualParents() {
        main { button("lab_translate").performClick() }
        await { !surface.nativeBusy() && surface.session.state().frame!=null }
        lateinit var before:RegionVisualSession.State
        lateinit var outer:ScrollView
        var x=0f;var y=0f;var oldScroll=0
        main {
            button("lab_demo").performClick()
            before=surface.session.state(); outer=button("lab_page_scroll") as ScrollView
            oldScroll=outer.scrollY
            x=(surface.source.leftPad+(before.roi.left+before.roi.width*.45)*surface.source.factor).toFloat()
            y=(surface.source.topPad+(before.roi.top+before.roi.height*.80)*surface.source.factor).toFloat()
        }
        rootDrag(surface.source,x,y,0f,45f)
        main {
            assertTrue(surface.session.state().roi.top>before.roi.top)
            assertEquals(oldScroll,outer.scrollY)
            assertEquals(before.roi.width,surface.session.state().roi.width,0.01)
            surface.session.move(10000.0,10000.0); surface.session.move(-10000.0,0.0)
            surface.session.resize(10000.0,-10000.0); button("lab_demo").performClick()
            outer.scrollTo(0,100000)
        }
        instrumentation.waitForIdleSync()
        lateinit var inner:ScrollView
        main { inner=button("lab_candidates_scroll") as ScrollView; assertEquals(0,inner.scrollY) }
        rootDrag(inner,inner.width*.5f,inner.height*.80f,0f,-inner.height*.60f)
        main {
            assertTrue("Inner cards must receive the vertical gesture",inner.scrollY>0)
            assertEquals(1,surface.session.state().loads)
            assertSame(before.frame!!.page,surface.session.state().frame!!.page)
        }
    }
    @Test fun translatingAfterOcrOnlyAcquiresANewPageInsteadOfRetimingOldEvidence() {
        main { button("lab_show").performClick() }
        await { surface.session.state().frame!=null }
        lateinit var original:FullPageVisualOcrRunner.Result
        main {
            original=surface.realResult()!!
            button("lab_translate").performClick()
            assertNull(surface.realResult()); assertEquals(2,surface.session.state().loads)
        }
        await { !surface.nativeBusy() && surface.session.state().frame!=null }
        main {
            val current=surface.realResult()!!
            assertNotSame(original,current)
            assertNotEquals(original.metadata.version,current.metadata.version)
            assertTrue(current.metadata.acquiredAtMillis>original.metadata.acquiredAtMillis)
            assertEquals(original.metadata.ttlMillis,current.metadata.ttlMillis)
            button("lab_demo").performClick()
            assertTrue(cards(),hasMarker(cards()))
        }
    }
}
