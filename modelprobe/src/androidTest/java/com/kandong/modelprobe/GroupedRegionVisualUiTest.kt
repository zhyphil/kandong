package com.kandong.modelprobe

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.ocrlab.context.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** No supplier assumptions: expected outcomes come only from the final pinned reviewed packet. */
@RunWith(AndroidJUnit4::class)
class GroupedRegionVisualUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity:RegionVisualLabActivity
    private lateinit var surface:RegionVisualLabSurface
    private var release:CountDownLatch?=null
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(predicate:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+120000
        while(SystemClock.elapsedRealtime()<until) {
            var done=false;main { done=predicate() };if(done)return
            SystemClock.sleep(50)
        };throw AssertionError("Grouped UI timeout")
    }
    @Before fun launch() {
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,RegionVisualLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RegionVisualLabActivity
        instrumentation.waitForIdleSync();main { surface=activity.labSurface as RegionVisualLabSurface }
        await { surface.isForeground() && surface.source.width>0 }
    }
    @After fun finish() {
        release?.countDown()
        if(::activity.isInitialized)main { activity.finish() }
        instrumentation.waitForIdleSync();await { !RegionVisualOcrRuntime.slot.busy() }
    }
    private fun button(tag:String)=surface.getView().findViewWithTag<View>(tag) ?: error("Missing $tag")
    private fun texts(v:View):String=when(v) {
        is TextView -> v.text.toString()
        is ViewGroup -> (0 until v.childCount).joinToString("\n") { texts(v.getChildAt(it)) }
        else -> ""
    }
    @Test fun switchRequiresFreshClickAndNativeSlotRemainsOwnedAfterCancellation() {
        val reached=CountDownLatch(1);val gate=CountDownLatch(1);release=gate
        main {
            surface.installRealBackendForProbe(RegionVisualOcrBackend(instrumentation.targetContext,instrumentation.context.assets,recorded=true) {
                reached.countDown();check(gate.await(90,TimeUnit.SECONDS))
            })
            assertFalse(surface.groupedForProbe());assertTrue(surface.toggleGroupedForProbe())
            assertTrue(surface.groupedForProbe());assertEquals(0,surface.session.state().loads);assertNull(surface.realResult())
            button("lab_translate").performClick();assertEquals(1,surface.session.state().loads)
            assertFalse(surface.toggleGroupedForProbe())
        }
        assertTrue(reached.await(90,TimeUnit.SECONDS))
        main {
            button("lab_cancel").performClick()
            assertNull(surface.realResult());assertFalse(surface.toggleGroupedForProbe())
        }
        gate.countDown()
        await { !surface.nativeBusy() }
        main {
            assertTrue(surface.toggleGroupedForProbe());assertFalse(surface.groupedForProbe())
            assertNull(surface.realResult());assertNull(surface.translationRender());assertEquals(1,surface.session.state().loads)
            button("lab_open_menu").performClick();button("lab_close_menu").performClick()
            assertEquals(1,surface.session.state().loads);assertNull(surface.realResult())
        }
    }
    @Test fun actualFourPagesMatchPinnedGroupedPacketAndEveryCardUsesAllSourceOrdinals() {
        assertTrue("Export and pin the genuine grouped recording before acceptance",RecordedGroupedRegionVisualReplies.available())
        val bytes=instrumentation.context.assets.open(RecordedGroupedRegionVisualReplies.ASSET).use { ProbeInputs.bounded(it,524288) }
        assertEquals(RecordedGroupedRegionVisualReplies.SHA,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        val packet=JSONObject(String(bytes,Charsets.UTF_8));val pages=packet.getJSONArray("pages")
        main { button("lab_grouped").performClick();assertTrue(surface.groupedForProbe());assertEquals(0,surface.session.state().loads) }
        FullPageVisualOcrRunner.PAGES.forEachIndexed { index,spec ->
            main { button("lab_translate").performClick() }
            await { !surface.nativeBusy() && surface.translationState()?.phase==RegionVisualTranslationController.Phase.READY }
            main {
                val result=surface.realResult()!!;assertTrue(result.diagnostics.cleanupBalanced)
                val expected=(0 until pages.length()).map { pages.getJSONObject(it) }.single { it.getString("id")==spec.id }
                val evidence=FullPageTranslationAdapter.Evidence(result.metadata,result.page)
                val fields=expected.getJSONArray("canonicalFields")
                assertEquals((0 until fields.length()).map { fields.getString(it) },RecordedGroupedFullPageTranslation.fields(evidence,spec.language))
                assertEquals(expected.getString("fingerprint"),RecordedGroupedFullPageTranslation.fingerprint(evidence,spec.language))
                val adapted=FullPageTranslationAdapter.adapt(result.metadata,result.page,spec.language,true) as FullPageTranslationAdapter.AdaptedPage
                val outcomes=expected.getJSONArray("outcomes");val byKey=(0 until outcomes.length()).map { outcomes.getJSONObject(it) }.associateBy { it.getString("key") }
                surface.session.move(-10000.0,10000.0);surface.session.resize(10000.0,-10000.0)
                val render=surface.translationRender()!!
                assertEquals(adapted.targetMembers.keys,render.cards.map { it.targetId }.toSet())
                render.cards.forEach { card ->
                    val members=adapted.targetMembers.getValue(card.targetId).map { adapted.sourceMap.getValue(it) }
                    val keys=members.map(RecordedFullPageTranslation::key)
                    val ordinal=members.joinToString("+") { c -> (result.page.rawCandidates.indexOfFirst { it.provenance.id==c.provenance.id }+1).toString() }
                    assertEquals(members.joinToString(" ") { it.rawText },card.sourceText)
                    assertTrue(texts(button("lab_translation_candidate_$ordinal")).contains(card.sourceText))
                    val target=adapted.layout!!.targets.singleOrNull { it.memberKeys==keys }
                    val outcome=target?.let { byKey[it.key] }
                    if(outcome!=null) {
                        assertEquals(if(outcome.isNull("chinese"))null else outcome.getString("chinese"),card.chinese)
                        assertEquals(outcome.getString("kind"),card.kind?.name)
                        assertEquals(outcome.getString("origin"),card.origin?.name)
                        assertEquals(if(outcome.isNull("reason"))null else outcome.getString("reason"),card.reason)
                    } else assertNull(card.chinese)
                    if(keys.size==4)assertEquals("AMBIGUOUS_GROUP",card.reason)
                }
                val loads=surface.session.state().loads
                surface.session.scale(3.0);surface.session.pan(10.0,10.0);surface.session.move(20.0,-20.0)
                surface.translationRender();assertSame(result,surface.realResult());assertEquals(loads,surface.session.state().loads)
                button("lab_original").performClick();assertTrue(surface.translationRender()!!.cards.all { it.chinese==null })
                button("lab_demo").performClick();assertSame(result,surface.realResult())
                if(index==1)assertEquals(listOf(2,4,2),adapted.layout!!.groups.map { it.memberKeys.size })
                if(spec.language.startsWith("zh"))assertTrue(render.cards.all { it.chinese==null })
                button("lab_page").performClick();assertNull(surface.realResult());assertNull(surface.translationRender())
            }
        }
        main {
            val loads=surface.session.state().loads
            button("lab_grouped").performClick();assertFalse(surface.groupedForProbe())
            assertEquals(loads,surface.session.state().loads);assertNull(surface.realResult())
        }
    }

    @Test fun tailOnlyRegionShowsCompleteConditionThenExpiresAtOriginalDeadline() {
        assertTrue(RecordedGroupedRegionVisualReplies.available())
        main {
            button("lab_grouped").performClick();button("lab_page").performClick()
            button("lab_translate").performClick()
        }
        await { !surface.nativeBusy() && surface.translationState()?.phase==RegionVisualTranslationController.Phase.READY }
        var deadline=0L
        main {
            val result=surface.realResult()!!
            assertEquals("fr-seam",result.page.identity!!.pageFixtureId)
            val layout=FullPageSemanticLayout.derive(result.page,"fr")
            val phrase=layout.groups.first { it.eligible }
            val members=phrase.memberKeys.map { key -> result.page.rawCandidates.single { RecordedFullPageTranslation.key(it)==key } }
            val tail=FullPageSemanticLayout.bounds(members.last())
            val roi=ContextRect(tail.left-1,tail.top-1,tail.right+1,tail.bottom+1)
            surface.session.minimumRegion(48.0)
            var current=surface.session.state().roi
            surface.session.resize(roi.width-current.width,current.height-roi.height)
            current=surface.session.state().roi
            surface.session.move(roi.left-current.left,roi.top-current.top)
            val rendered=surface.translationRender()!!
            assertEquals(1,rendered.cards.size)
            assertEquals(members.joinToString(" ") { it.rawText },rendered.cards.single().sourceText)
            val ordinal=members.joinToString("+") { c -> (result.page.rawCandidates.indexOfFirst { it.provenance.id==c.provenance.id }+1).toString() }
            assertTrue(texts(button("lab_translation_candidate_$ordinal")).contains(rendered.cards.single().sourceText))
            val loads=surface.session.state().loads
            surface.session.scale(4.0);surface.session.pan(3.0,2.0);surface.translationRender()
            assertSame(result,surface.realResult());assertEquals(loads,surface.session.state().loads)
            deadline=result.metadata.acquiredAtMillis+result.metadata.ttlMillis
        }
        while(SystemClock.elapsedRealtime()<deadline+300)SystemClock.sleep(100)
        await { surface.translationState()?.phase==RegionVisualTranslationController.Phase.EXPIRED }
        main {
            assertNull(surface.realResult());assertNull(surface.translationRender())
            val cards=button("lab_cards") as ViewGroup
            assertTrue((0 until cards.childCount).none {
                cards.getChildAt(it).tag.toString().startsWith("lab_translation_candidate_")
            })
            assertEquals(1,surface.session.state().loads)
            button("lab_translate").performClick()
        }
        await { !surface.nativeBusy() && surface.translationState()?.phase==RegionVisualTranslationController.Phase.READY }
        main {
            assertEquals(2,surface.session.state().loads)
            button("lab_open_menu").performClick();assertNull(surface.realResult());assertNull(surface.translationRender())
            button("lab_close_menu").performClick();assertNull(surface.realResult())
            button("lab_collapse").performClick();button("lab_restore").performClick()
            assertNull(surface.realResult());assertNull(surface.translationRender())
            assertEquals(2,surface.session.state().loads)
        }
    }
}
