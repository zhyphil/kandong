package com.kandong.ocrlab.context

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.ocrlab.OcrLabActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Exercises our synthetic activity only; no permissions, external apps or network. */
class ProtectedDisplayInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: ProtectedTranslationLabActivity? = null
    private fun launch(): ProtectedTranslationLabActivity {
        val intent = Intent(instrumentation.targetContext, ProtectedTranslationLabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return (instrumentation.startActivitySync(intent) as ProtectedTranslationLabActivity).also {
            activity = it; instrumentation.waitForIdleSync()
        }
    }
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun view(tag: String): View = activity!!.window.decorView.findViewWithTag(tag)
    private fun click(tag: String) { ui { assertTrue(view(tag).performClick()) }; instrumentation.waitForIdleSync() }
    private fun text(tag: String): String { var value = ""; ui { value = (view(tag) as TextView).text.toString() }; return value }
    private fun shown(tag: String): Boolean { var value = false; ui { value = view(tag).isShown }; return value }
    private fun until(timeout: Long = 3000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { if (SystemClock.elapsedRealtime() >= end) fail("Synthetic UI condition timed out"); SystemClock.sleep(25) }
    }
    private fun translated() { click("translate"); until { text("status").startsWith("已显示") } }
    @After fun finish() { activity?.let { current -> ui { current.finish() } }; instrumentation.waitForIdleSync() }

    @Test fun recordedAssetBindsAllFiveWholePagesAndKeepsChineseOriginal() {
        val pages = ProtectedDisplayFixtures.load(instrumentation.targetContext)
        assertEquals(listOf("p303", "p106", "p304", "p007", "p008"), pages.map { it.id })
        val counts = mutableMapOf<AnswerKind, Int>()
        for ((index, fixture) in pages.withIndex()) {
            val page = TranslationPage(1, index.toLong(), 1)
            val flow = OnDemandTranslation(ProtectedDisplayFixtures.provider); flow.observePage(page)
            val ticket = flow.clickTranslate(100)!!
            assertTrue(flow.captured(ticket, fixture.snapshot(page, 100), fixture.roi(0), 100))
            val request = flow.pending!!
            assertEquals(fixture.template.blocks, request.screenContext.blocks)
            assertEquals(fixture.template.blocks.size, request.targets.size)
            val response = fixture.response(request)
            response.answers.forEach { counts[it.kind] = counts.getOrDefault(it.kind, 0) + 1 }
            assertTrue(flow.accept(response, 100))
            for (i in fixture.targets.indices) {
                flow.select(fixture.roi(i), 100)
                val frame = flow.render(100); val card = frame.cards.single()
                assertEquals(fixture.targets[i], frame.anchors.single().sourceId)
                assertEquals(fixture.source(i).text, card.sourceText)
                if (fixture.source(i).language.startsWith("zh")) {
                    assertEquals(AnswerKind.KEEP_ORIGINAL, card.kind); assertEquals(AnswerOrigin.SOURCE, card.origin)
                    assertNull(card.chinese); assertEquals(fixture.source(i).text, card.presentation().text); assertNull(card.presentation().notice)
                }
            }
        }
        assertEquals(mapOf(AnswerKind.CANDIDATE to 6, AnswerKind.LOCAL_DATE to 3, AnswerKind.KEEP_ORIGINAL to 16), counts)
    }

    @Test fun clickThenMoveShowsMoneyDateAndOriginalWarningWithoutAnotherRequest() {
        launch(); assertEquals("£18.75 per hour", text("mirror")); assertTrue(text("status").endsWith("：0"))
        translated(); assertEquals("£18.75 每小时", text("mirror"))
        click("next-region"); click("next-region")
        assertEquals("仅在2027年2月14日有效", text("mirror")); assertFalse(shown("notice"))
        click("next-region")
        assertEquals("No refund if cancelled less than 24 hours before pickup.", text("mirror"))
        assertEquals("这段翻译暂时无法核对，请先看原文。", text("notice"))
        assertTrue(text("status").endsWith("：1"))
        click("next-page"); assertEquals("Prix total : 37,80 €", text("mirror")); assertFalse(shown("notice"))
        translated(); assertEquals("总价：37,80 €", text("mirror"))
        click("next-region"); assertEquals("在2026年12月5日有效", text("mirror"))
    }

    @Test fun menuAndCollapseClearVisibleResultsAndRestorePriorSurface() {
        launch(); translated(); click("pause")
        assertTrue(shown("bubble")); assertFalse(shown("main")); assertEquals("£18.75 per hour", text("mirror"))
        click("bubble-menu"); assertTrue(shown("menu-pane")); assertFalse(shown("bubble"))
        click("close-menu"); assertTrue(shown("bubble")); click("resume")
        assertTrue(shown("main")); assertTrue(text("status").endsWith("：1")); assertEquals("£18.75 per hour", text("mirror"))
        translated(); click("menu"); assertTrue(shown("menu-pane")); click("close-menu")
        assertTrue(shown("main")); assertEquals("£18.75 per hour", text("mirror")); assertTrue(text("status").endsWith("：2"))
    }

    @Test fun clearingDuringDeliveryDoesNotResurrectOldResults() {
        launch()
        for (action in listOf("menu", "pause", "page-change", "next-page")) {
            click("translate"); click(action)
            if (action == "menu") click("close-menu")
            if (action == "pause") click("resume")
            SystemClock.sleep(450); instrumentation.waitForIdleSync()
            assertEquals(text("selected-source").removePrefix("原文："), text("mirror"))
            assertFalse(text("status").startsWith("已显示")); assertFalse(shown("notice"))
        }
    }

    @Test fun goingToOurLauncherAndBackDropsResultsWithoutAutomaticProcessing() {
        val current = launch(); translated()
        val launcher = instrumentation.startActivitySync(Intent(instrumentation.targetContext, OcrLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        instrumentation.waitForIdleSync()
        assertEquals("£18.75 per hour", text("mirror"))
        ui { launcher.finish() }; instrumentation.waitForIdleSync()
        until { var focused = false; ui { focused = current.hasWindowFocus() }; focused }
        assertTrue(text("status").endsWith("：1")); assertEquals("£18.75 per hour", text("mirror"))
    }

    @Test fun expiryUpdatesActualViewWithoutUserAction() {
        launch(); translated()
        until(65_000) { text("status").startsWith("页面已变化或过期") }
        assertEquals("£18.75 per hour", text("mirror")); assertFalse(shown("notice")); assertTrue(text("status").endsWith("：1"))
    }
}
