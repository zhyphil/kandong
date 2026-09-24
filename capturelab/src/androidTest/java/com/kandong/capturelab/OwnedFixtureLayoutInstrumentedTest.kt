package com.kandong.capturelab

import android.content.Intent
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real layout transitions; no projection grant or service is created. */
class OwnedFixtureLayoutInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun afterLayout(activity: CaptureLabActivity) {
        val latch = CountDownLatch(1)
        ui { activity.window.decorView.postOnAnimation {
            activity.window.decorView.postOnAnimation { latch.countDown() }
        } }
        assertTrue("Owned layout did not produce frame callbacks", latch.await(4, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    @Test fun statusChangesDoNotMoveTheFrozenCaptureGeometry() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, CaptureLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CaptureLabActivity
        try {
            afterLayout(activity)
            lateinit var initial: FixtureGeometry
            ui { initial = activity.fixture.screenGeometry() }
            val states = listOf(
                "等待本次系统屏幕共享授权；取消不会开始采集。",
                "开始验证；离开页面、失焦或锁屏会立即停止。"
            )
            for (text in states) {
                ui { activity.window.decorView.findViewWithTag<TextView>("capture-status").text = text }
                afterLayout(activity)
                ui { assertEquals("Status changed fixed fixture geometry", initial, activity.fixture.screenGeometry()) }
            }
            for (phase in Phase.entries) {
                ui { activity.showPhase(phase, Marker.forPhase(987, phase)) }
                afterLayout(activity)
                ui { assertEquals("Phase ${phase.name} moved fixed fixture geometry", initial, activity.fixture.screenGeometry()) }
            }
        } finally { ui { activity.finish() }; instrumentation.waitForIdleSync() }
    }
}
