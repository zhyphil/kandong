package com.kandong.capturelab

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** No projection/overlay grant, shell permission, screenshot, UiAutomation, or disk output. */
class OwnedFixtureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val activities = mutableListOf<CaptureLabActivity>()
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun launch(newTask: Boolean = false): CaptureLabActivity {
        val intent = Intent(instrumentation.targetContext, CaptureLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or if (newTask) Intent.FLAG_ACTIVITY_MULTIPLE_TASK else 0)
        val activity = instrumentation.startActivitySync(intent) as CaptureLabActivity
        activities += activity
        until { activity.hasWindowFocus() && activity.fixture.width > 0 }
        return activity
    }
    private fun until(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (true) {
            var ready = false; ui { ready = condition() }; if (ready) return
            if (SystemClock.elapsedRealtime() >= deadline) fail("Owned Activity condition timed out")
            SystemClock.sleep(25)
        }
    }
    @After fun finish() { ui { activities.asReversed().forEach { it.finish() } }; instrumentation.waitForIdleSync() }

    @Test fun measuresActualOwnedViewAndStartsWithoutCaptureOrPermissionRequests() {
        val activity = launch()
        ui {
            val view = activity.fixture
            val xy = IntArray(2); view.getLocationOnScreen(xy)
            val expected = FixtureGeometry.local(view.width, view.height).shift(xy[0], xy[1])
            assertEquals(expected, view.screenGeometry())
            val bounds = activity.windowManager.currentWindowMetrics.bounds
            assertTrue(expected.inside(bounds.width(), bounds.height()))
            assertTrue(expected.patch.right < expected.witness.left)
            assertEquals(ConsentGate.State.IDLE, activity.gate.state)
            assertNull(activity.service); assertNull(activity.report)
            assertTrue(activity.window.decorView.findViewWithTag<View>("capture-stop").isShown)
        }
    }

    @Test fun explicitStopCancelsArmedGateDropsLateResultAndClearsOwnedSecureFlag() {
        val activity = launch()
        ui {
            // Arm only the pure state gate, without requesting or inventing a projection token.
            assertTrue(activity.gate.request())
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            activity.window.decorView.findViewWithTag<View>("capture-stop").performClick()
            assertEquals(ConsentGate.State.IDLE, activity.gate.state)
            assertFalse(activity.gate.result(true))
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertEquals(EndReason.USER_STOP, activity.report!!.reason)
            assertEquals(0, activity.report!!.displays); assertEquals(0, activity.report!!.acquired)
            assertNull(activity.service)
        }
    }

    @Test fun realActivityDepartureAndReturnNeverResumeAnArmedSession() {
        val activity = launch()
        ui {
            assertTrue(activity.gate.request()); assertTrue(activity.gate.result(true))
            assertTrue(activity.gate.consume(true, true)) // State only; zero service/token/permission.
        }
        val covering = launch(newTask = true)
        until { activity.gate.state == ConsentGate.State.IDLE }
        ui {
            assertTrue(activity.report!!.reason in listOf(EndReason.FOCUS_LOST, EndReason.BACKGROUND))
            assertEquals(0, activity.report!!.displays); assertNull(activity.service)
            covering.finish()
        }
        until { activity.hasWindowFocus() }
        ui { assertEquals(ConsentGate.State.IDLE, activity.gate.state); assertNull(activity.service) }
    }

    @Test fun stoppedConsentPromptCannotBecomeAStartWhenAnOldResultArrives() {
        val activity = launch()
        ui { assertTrue(activity.gate.request()) }
        val covering = launch(newTask = true)
        until { activity.gate.state == ConsentGate.State.IDLE }
        ui {
            assertEquals(EndReason.CONSENT_ABANDONED, activity.report!!.reason)
            assertFalse(activity.gate.result(true)); assertNull(activity.service)
            covering.finish()
        }
        until { activity.hasWindowFocus() }
        ui { assertEquals(0, activity.report!!.acquired); assertEquals(ConsentGate.State.IDLE, activity.gate.state) }
    }
}
