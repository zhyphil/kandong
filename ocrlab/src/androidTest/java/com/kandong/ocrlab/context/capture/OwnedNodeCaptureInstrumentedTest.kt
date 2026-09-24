package com.kandong.ocrlab.context.capture

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.ocrlab.OcrLabActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Only our own Views with fixed synthetic text. No UiAutomation or service permissions. */
class OwnedNodeCaptureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: OwnedNodeLabActivity? = null
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun launch(): OwnedNodeLabActivity {
        val value = instrumentation.startActivitySync(Intent(instrumentation.targetContext, OwnedNodeLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OwnedNodeLabActivity
        activity = value; instrumentation.waitForIdleSync()
        until { value.hasWindowFocus() && value.fixtureViews.getValue(1).width > 0 }
        return value
    }
    private fun until(timeout: Long = 4000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (true) {
            var ready = false; ui { ready = predicate() }; if (ready) return
            if (SystemClock.elapsedRealtime() >= end) fail("Owned synthetic page condition timed out")
            SystemClock.sleep(25)
        }
    }
    private fun click(tag: String) {
        ui { assertTrue(activity!!.window.decorView.findViewWithTag<View>(tag).performClick()) }
        instrumentation.waitForIdleSync()
    }
    private fun capture(): LocalCaptureInspection {
        click("owned-read"); until { activity!!.result != null }
        var r: LocalCaptureInspection? = null; ui { r = activity!!.result }; return r!!
    }
    private fun preview(): String { var text = ""; ui { text = activity!!.window.decorView.findViewWithTag<TextView>("owned-preview").text.toString() }; return text }
    @After fun finish() { activity?.let { ui { it.finish() } }; instrumentation.waitForIdleSync() }

    @Test fun readsRealOwnedNodesOnlyAfterClickAndKeepsFourSingleLanguagePages() {
        val a = launch()
        ui { assertEquals(0, a.captureCount); assertNull(a.result); assertTrue(a.observations.isEmpty()) }
        val expected = listOf(listOf("Train tickets", "Book", "£18.75 per ticket", "No refund after departure."),
            listOf("Billets de train", "Réserver", "37,80 € par billet", "Non remboursable après le départ."),
            listOf("列车票", "预订", "每张票37.80元", "出发后不可退款。"),
            listOf("列車票", "預訂", "每張票37.80元", "出發後不可退款。"))
        for ((pageIndex, words) in expected.withIndex()) {
            val r = capture()
            assertEquals(CaptureOrigin.OWNED_ANDROID_FIXTURE, r.origin)
            assertEquals(CaptureCoverage.UNVERIFIED, r.coverage)
            assertEquals(CaptureEvidence.NO, r.enumeration)
            if (Build.VERSION.SDK_INT >= 34) {
                assertEquals(words, r.blocks.filter { it.node in 1..4 }.map { it.text })
            } else { assertTrue(r.blocks.isEmpty()) }
            ui { assertEquals(12, a.obtained); assertEquals(a.obtained, a.released) }
            // Render ONLY this Activity's owned synthetic view tree, never a device screenshot.
            ui {
                val decor = a.window.decorView
                assertTrue(a.hasWindowFocus())
                val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                try {
                    decor.draw(Canvas(bitmap))
                    File(a.filesDir, "owned-node-page-$pageIndex.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
                val rows = JSONArray()
                for (o in a.observations) rows.put(JSONObject().put("id", o.id).put("class", o.className)
                    .put("clickable", o.clickable).put("frameworkWindowId", o.frameworkWindowId)
                    .put("screenBounds", o.screenBounds.toString()).put("viewScreenBounds", o.viewScreenBounds.toString())
                    .put("visible", o.node.visible.name).put("password", o.node.password.name)
                    .put("editable", o.node.editable.name).put("sensitive", o.node.sensitive.name))
                File(a.filesDir, "owned-node-page-$pageIndex.json").writeText(JSONObject()
                    .put("scope", "owned-synthetic-view-registry").put("api", Build.VERSION.SDK_INT)
                    .put("page", pageIndex).put("origin", r.origin.name).put("coverage", r.coverage.name)
                    .put("obtained", a.obtained).put("released", a.released).put("labelReads", JSONArray(a.lastReads))
                    .put("observations", rows).toString(2) + "\n")
            }
            click("owned-next"); ui { assertNull(a.result) }
        }
    }

    @Test fun androidFlagsAndBoundsComeFromFrameworkNotExpectedStrings() {
        val a = launch(); capture()
        ui {
            val byId = a.observations.associateBy { it.id }
            assertEquals("android.widget.Button", byId.getValue(2).className)
            assertTrue(byId.getValue(2).clickable)
            assertEquals("android.widget.EditText", byId.getValue(6).className)
            assertEquals(CaptureEvidence.YES, byId.getValue(6).node.password)
            assertEquals(CaptureEvidence.YES, byId.getValue(7).node.editable)
            assertEquals(if (Build.VERSION.SDK_INT >= 34) CaptureEvidence.YES else CaptureEvidence.UNAVAILABLE,
                byId.getValue(8).node.sensitive)
            for (id in 1..4) {
                val v = a.fixtureViews.getValue(id)
                val xy = IntArray(2); v.getLocationOnScreen(xy)
                val expected = CaptureRect(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height)
                assertEquals(expected, byId.getValue(id).viewScreenBounds)
                assertEquals(expected, byId.getValue(id).screenBounds)
                val origin = IntArray(2); a.window.decorView.getLocationOnScreen(origin)
                assertEquals(CaptureRect(expected.left - origin[0], expected.top - origin[1], expected.right - origin[0], expected.bottom - origin[1]), byId.getValue(id).node.original)
            }
            assertFalse(a.lastReads.contains(6)); assertFalse(a.lastReads.contains(7)); assertFalse(a.lastReads.contains(8)); assertFalse(a.lastReads.contains(9))
        }
    }

    @Test fun oldApiNeverCallsUnavailableSensitiveGetterOrDefaultsItToFalse() {
        var called = false
        assertEquals(CaptureEvidence.UNAVAILABLE, OwnedNodeSnapshot.sensitiveEvidence(31) { called = true; false })
        assertFalse(called)
        assertEquals(CaptureEvidence.YES, OwnedNodeSnapshot.sensitiveEvidence(34) { true })
        assertEquals(CaptureEvidence.NO, OwnedNodeSnapshot.sensitiveEvidence(37) { false })
    }

    @Test fun metadataDoesNotInvokeOurDeferredLabelGetterAndClosingReleasesEveryNode() {
        val a = launch()
        ui {
            val frame = OwnedNodeSnapshot.obtain(a, CaptureVersion(1, 1, 1, 1, 1, 0))
            assertEquals(12, frame.observations.size); assertTrue(frame.reads.isEmpty())
            assertFalse(frame.closed)
            frame.close(); frame.close()
            assertTrue(frame.closed); assertEquals(12, frame.releasedCount)
            try { frame.readLabel(1); fail("Closed frame must not retain label access") } catch (_: IllegalStateException) { }
        }
    }

    @Test fun sameWindowCoverDoesNotBecomeReadableJustBecauseNodeSaysVisible() {
        val a = launch(); capture(); click("owned-cover")
        ui { assertNull(a.result) }
        val covered = capture()
        ui {
            assertEquals(CaptureEvidence.YES, a.observations.first { it.id == 1 }.node.visible)
            assertEquals(CaptureEvidence.YES, a.observations.first { it.id == 1 }.node.occluded)
            assertFalse(a.lastReads.contains(1))
        }
        assertFalse(covered.blocks.any { it.node == 1 })
        click("owned-cover"); ui { assertNull(a.result) }
        SystemClock.sleep(200); ui { assertNull(a.result) }
    }

    @Test fun partialScrollExcludesCompleteLabelAndClearsPriorResult() {
        val a = launch(); capture()
        ui { a.scroll.scrollTo(0, (35 * a.resources.displayMetrics.density).toInt()) }
        instrumentation.waitForIdleSync(); ui { assertNull(a.result) }
        val r = capture()
        ui {
            val first = a.observations.first { it.id == 1 }.node
            assertNotEquals(first.original, first.visibleBounds)
            assertFalse(a.lastReads.contains(1))
        }
        assertFalse(r.blocks.any { it.node == 1 })
    }

    @Test fun descriptionAggregationAndEllipsisAreHandledWithoutLeakingOrRewriting() {
        val a = launch()
        val initial = capture()
        if (Build.VERSION.SDK_INT >= 34) assertEquals("Help", initial.blocks.first { it.node == 5 }.text)
        ui { a.scroll.isSmoothScrollingEnabled = false; a.scroll.fullScroll(View.FOCUS_DOWN) }; instrumentation.waitForIdleSync()
        val r = capture()
        ui {
            assertEquals(CaptureEvidence.YES, a.observations.first { it.id == 11 }.node.truncated)
            assertFalse(a.lastReads.contains(9)); assertFalse(a.lastReads.contains(11))
        }
        assertFalse(r.blocks.any { it.text.contains("SYNTHETIC-AGGREGATE") })
        if (Build.VERSION.SDK_INT >= 34) assertEquals("Public information", r.blocks.first { it.node == 10 }.text)
    }

    @Test fun actualTextChangeInvalidatesOutputAndRequiresAnotherClick() {
        val a = launch(); capture()
        ui { (a.fixtureViews.getValue(3) as TextView).text = "£21.75 per ticket" }
        instrumentation.waitForIdleSync(); ui { assertNull(a.result); assertEquals(1, a.captureCount) }
        assertFalse(preview().contains("£18.75"))
        val fresh = capture()
        if (Build.VERSION.SDK_INT >= 34) assertEquals("£21.75 per ticket", fresh.blocks.first { it.node == 3 }.text)
    }

    @Test fun nodeRelayoutWithUnchangedTextClearsTheOldPositionBinding() {
        val a = launch(); capture()
        ui {
            val view = a.fixtureViews.getValue(3)
            view.layout(view.left + 8, view.top, view.right + 8, view.bottom)
            assertNull(a.result)
            assertTrue(a.observations.isEmpty())
            assertEquals(1, a.captureCount)
        }
        assertFalse(preview().contains("£18.75"))
    }

    @Test fun readDuringAnimatedScrollIsCancelledAndDoesNotRetryWhenMotionStops() {
        val a = launch()
        ui {
            a.scroll.isSmoothScrollingEnabled = true
            a.scroll.fullScroll(View.FOCUS_DOWN)
            a.window.decorView.findViewWithTag<View>("owned-read").performClick()
        }
        until { a.scroll.scrollY == a.scroll.getChildAt(0).height - a.scroll.height }
        SystemClock.sleep(200)
        ui { assertNull(a.result); assertEquals(0, a.captureCount) }
        capture()
    }

    @Test fun clearOrPageChangeWhileQueuedDoesNotStartCapture() {
        val a = launch()
        for (action in listOf("owned-clear", "owned-next", "owned-update")) {
            ui {
                a.window.decorView.findViewWithTag<View>("owned-read").performClick()
                a.window.decorView.findViewWithTag<View>(action).performClick()
            }
            SystemClock.sleep(250)
            ui { assertEquals(0, a.captureCount); assertNull(a.result); assertTrue(a.observations.isEmpty()) }
        }
    }

    @Test fun ourSecureWindowIsRefusedBeforeAnyDeferredLabelRead() {
        val a = launch()
        ui { a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }; instrumentation.waitForIdleSync()
        val r = capture()
        assertTrue(r.blocks.isEmpty()); assertTrue(r.gaps.all { it.reason == CaptureGapReason.SENSITIVE_WINDOW })
        ui { assertTrue(a.lastReads.isEmpty()) }
    }

    @Test fun backgroundReturnDropsTextAndDoesNotAutomaticallyReadAgain() {
        val a = launch(); capture()
        val launcher = instrumentation.startActivitySync(Intent(instrumentation.targetContext, OcrLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        instrumentation.waitForIdleSync(); ui { assertNull(a.result); launcher.finish() }
        until { a.hasWindowFocus() }
        ui { assertNull(a.result); assertEquals(1, a.captureCount); assertTrue(a.observations.isEmpty()) }
    }

    @Test fun actualExpiryClearsTextWithoutAnotherCapture() {
        val a = launch(); capture()
        until(17_000) { a.result == null }
        ui { assertEquals(1, a.captureCount); assertTrue(a.observations.isEmpty()) }
        assertFalse(preview().contains("Train tickets"))
    }
}
