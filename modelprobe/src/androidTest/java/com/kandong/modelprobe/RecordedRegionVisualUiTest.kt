package com.kandong.modelprobe

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual new fixed-page OCR matched to recorded provider responses, never live cloud translation. */
@RunWith(AndroidJUnit4::class)
class RecordedRegionVisualUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity:RegionVisualLabActivity
    private lateinit var surface:RegionVisualLabSurface
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(predicate:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+120000
        while(SystemClock.elapsedRealtime()<until) {
            var done=false;main { done=predicate() };if(done)return
            SystemClock.sleep(50)
        };throw AssertionError("Recorded UI timeout")
    }
    private fun button(tag:String)=surface.getView().findViewWithTag<View>(tag) ?: error("Missing $tag")
    private fun texts(v:View):String=when(v) {
        is TextView -> v.text.toString()
        is ViewGroup -> (0 until v.childCount).joinToString("\n") { texts(v.getChildAt(it)) }
        else -> ""
    }
    @Before fun launch() {
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,RegionVisualLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RegionVisualLabActivity
        instrumentation.waitForIdleSync();main { surface=activity.labSurface as RegionVisualLabSurface }
        await { surface.isForeground() && surface.source.width>0 }
    }
    @After fun finish() {
        if(::activity.isInitialized)main { activity.finish() }
        instrumentation.waitForIdleSync();await { !RegionVisualOcrRuntime.slot.busy() }
    }
    private fun noCards() {
        assertNull(surface.realResult());assertNull(surface.translationRender())
        val container=button("lab_cards") as ViewGroup
        assertTrue((0 until container.childCount).none { container.getChildAt(it).tag.toString().startsWith("lab_translation_candidate_") })
    }
    private fun recordedPage(id:String):JSONObject {
        val packet=JSONObject(instrumentation.context.assets.open(RecordedRegionVisualReplies.ASSET).bufferedReader().use { it.readText() })
        val pages=packet.getJSONArray("pages")
        return (0 until pages.length()).map { pages.getJSONObject(it) }.single { it.getString("id")==id }
    }
    private fun start() {
        main { button("lab_translate").performClick() }
        await { !surface.nativeBusy() && surface.translationState()?.phase==RegionVisualTranslationController.Phase.READY }
    }
    @Test fun fourFreshPagesMatchWholeRecordedInputAndShowGatedProviderCards() {
        val counts=listOf(8,20,9,16)
        FullPageVisualOcrRunner.PAGES.forEachIndexed { i,spec ->
            main {
                assertTrue(texts(button("lab_notice")).contains("本次未联网"))
                assertEquals("译文回放",texts(button("lab_translate")))
                assertNull(surface.realResult())
            }
            start()
            main {
                val result=surface.realResult()!!
                assertEquals(spec.id,result.page.identity!!.pageFixtureId)
                assertEquals(counts[i],result.page.rawCandidates.size)
                assertTrue(result.diagnostics.cleanupBalanced)
                val expected=recordedPage(spec.id)
                val evidence=FullPageTranslationAdapter.Evidence(result.metadata,result.page)
                val fields=expected.getJSONArray("canonicalFields")
                assertEquals((0 until fields.length()).map { fields.getString(it) },RecordedFullPageTranslation.fields(evidence,spec.language))
                assertEquals(expected.getString("fingerprint"),RecordedFullPageTranslation.fingerprint(evidence,spec.language))
                val adapted=FullPageTranslationAdapter.adapt(result.metadata,result.page,spec.language) as FullPageTranslationAdapter.AdaptedPage
                val outcomes=expected.getJSONArray("outcomes")
                val byKey=(0 until outcomes.length()).map { outcomes.getJSONObject(it) }.associateBy { it.getString("key") }
                surface.session.move(-10000.0,10000.0)
                surface.session.resize(10000.0,-10000.0)
                val render=surface.translationRender()!!
                assertEquals(counts[i],render.cards.size)
                val cards=texts(button("lab_cards"))
                assertFalse(cards.contains("绑定演示"))
                // Expected outputs come from the final separately reviewed, pinned packet, not a guessed phrase.
                render.cards.forEach { card ->
                    val candidate=adapted.sourceMap.getValue(card.targetId)
                    assertEquals(candidate.rawText,card.sourceText)
                    val ordinal=result.page.rawCandidates.indexOfFirst { it.provenance.id==candidate.provenance.id }+1
                    val view=button("lab_translation_candidate_$ordinal")
                    assertTrue(texts(view).contains(candidate.rawText))
                    val outcome=byKey[RecordedFullPageTranslation.key(candidate)]
                    if(outcome!=null) {
                        val translated=if(outcome.isNull("chinese")) null else outcome.getString("chinese")
                        assertEquals(translated,card.chinese)
                        assertEquals(outcome.getString("origin"),card.origin?.name)
                        assertEquals(outcome.getString("kind"),card.kind?.name)
                        assertEquals(if(outcome.isNull("reason")) null else outcome.getString("reason"),card.reason)
                        if(translated!=null)assertTrue(texts(view).contains(translated))
                        else if(card.reason=="CHECK_UNVERIFIED")assertTrue(texts(view).contains("译文尚未通过核对"))
                    } else assertNull(card.chinese)
                }
                if(spec.language.startsWith("zh")) {
                    assertTrue(render.cards.all { it.chinese==null })
                    assertEquals("中文原文就绪 · 不请求翻译",texts(button("lab_translation_status")))
                }
                val original=result;val loads=surface.session.state().loads
                button("lab_original").performClick()
                assertTrue(surface.translationRender()!!.cards.all { it.chinese==null })
                button("lab_demo").performClick()
                assertSame(original,surface.realResult());assertEquals(loads,surface.session.state().loads)
                button("lab_page").performClick()
                assertNull(surface.realResult());assertNull(surface.translationRender())
            }
        }
    }
    @Test fun recordedCancelAndCollapseNeverKeepCardsOrAutomaticallyReadAgain() {
        start()
        main {
            assertNotNull(surface.translationRender())
            button("lab_cancel").performClick()
            noCards()
        }
        start()
        main {
            val loads=surface.session.state().loads
            button("lab_collapse").performClick();assertNull(surface.realResult())
            button("lab_restore").performClick();assertNull(surface.realResult())
            assertEquals(loads,surface.session.state().loads)
            noCards()
        }
    }
    @Test fun recordedResultExpiresAtOriginalAcquisitionAndOneClickStartsNewOcr() {
        start();var deadline=0L
        main { deadline=surface.realResult()!!.metadata.acquiredAtMillis+60000 }
        while(SystemClock.elapsedRealtime()<deadline+300)SystemClock.sleep(100)
        await { surface.translationState()?.phase==RegionVisualTranslationController.Phase.EXPIRED }
        main { noCards() }
        start()
        main { assertEquals(2,surface.session.state().loads);assertNotNull(surface.translationRender()) }
    }
}
