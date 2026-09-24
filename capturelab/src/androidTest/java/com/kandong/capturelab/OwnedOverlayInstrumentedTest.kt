package com.kandong.capturelab

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Test-only compositor regression, restricted to this fixed, foreground owned Activity.
 * Requires the user's existing overlay grant; never requests/grants projection. The developer
 * screenshot is checked in memory and recycled, not saved or used as projection evidence.
 */
class OwnedOverlayInstrumentedTest {
    @Test fun fixedOwnedWindowsKeepOpaqueMarkerColors() {
        val inst = InstrumentationRegistry.getInstrumentation()
        fun ui(block: () -> Unit) = inst.runOnMainSync(block)
        val activity = inst.startActivitySync(Intent(inst.targetContext, CaptureLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CaptureLabActivity
        lateinit var geometry: FixtureGeometry
        val marker = Marker.forPhase(0, Phase.BASELINE)
        var wm: WindowManager? = null
        var cover: View? = null
        var witness: WitnessView? = null
        try {
            assertTrue("User-granted overlay permission required", Settings.canDrawOverlays(inst.targetContext))
            val deadline = SystemClock.elapsedRealtime() + 4000
            while (true) {
                var ready = false
                ui { ready = activity.fixture.width > 0 && activity.hasWindowFocus() }
                if (ready) break
                check(SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(25)
            }
            ui {
                val display = inst.targetContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                val ctx = inst.targetContext.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                wm = ctx.getSystemService(WindowManager::class.java)
                val g = activity.fixture.screenGeometry(); geometry = g
                val service = CaptureLabService()
                val params = service.javaClass.getDeclaredMethod("overlayParams", Box::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                cover = View(ctx).apply { setBackgroundColor(Marker.COVER) }
                witness = WitnessView(ctx, marker)
                wm!!.addView(cover, params.invoke(service, g.patch, false) as WindowManager.LayoutParams)
                wm!!.addView(witness, params.invoke(service, g.witness, false) as WindowManager.LayoutParams)
            }
            val layoutDeadline = SystemClock.elapsedRealtime() + 4000
            while (true) {
                var ready = false
                ui { ready = cover!!.isLaidOut && witness!!.isLaidOut }
                if (ready) break
                check(SystemClock.elapsedRealtime() < layoutDeadline)
                SystemClock.sleep(25)
            }
            ui {
                val v = cover!!
                val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                try {
                    v.draw(Canvas(bitmap))
                    assertEquals(0xffe45235.toInt(), bitmap.getPixel(v.width/2, v.height/2))
                } finally { bitmap.recycle() }
            }
            // Surface composition is asynchronous; do not mistake the first old frame for a fault.
            val compositionDeadline = SystemClock.elapsedRealtime() + 2500
            var colorsMatched: Boolean
            do {
                ui {
                    assertTrue(activity.hasWindowFocus() && activity.fixture.isShown)
                    assertEquals(ConsentGate.State.IDLE, activity.gate.state)
                    assertNull(activity.service)
                    assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                    for ((v, box) in listOf(cover!! to geometry.patch, witness!! to geometry.witness)) {
                        val xy = IntArray(2); v.getLocationOnScreen(xy)
                        assertEquals(box, Box(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height))
                    }
                }
                val screenshot = checkNotNull(inst.uiAutomation.takeScreenshot())
                try {
                    val cells = FixtureGeometry.witnessCells(geometry.witness.width, geometry.witness.height)
                    colorsMatched = matches(screenshot, geometry.patch, Marker.COVER) &&
                        matches(screenshot, cells.first.shift(geometry.witness.left, geometry.witness.top), marker.first) &&
                        matches(screenshot, cells.second.shift(geometry.witness.left, geometry.witness.top), marker.second)
                } finally { screenshot.recycle() }
                if (!colorsMatched) SystemClock.sleep(50)
            } while (!colorsMatched && SystemClock.elapsedRealtime() < compositionDeadline)
            assertTrue("Actual owned overlay colors were blended or missing", colorsMatched)
        } finally {
            ui {
                cover?.let { wm?.removeViewImmediate(it) }
                witness?.let { wm?.removeViewImmediate(it) }
                activity.finish()
            }
        }
    }

    private fun matches(bitmap: Bitmap, box: Box, expected: Int): Boolean {
        assertTrue(box.inside(bitmap.width, bitmap.height))
        val x = (box.left + box.right) / 2; val y = (box.top + box.bottom) / 2
        val dx = box.width / 4; val dy = box.height / 4
        return listOf(x to y, x-dx to y-dy, x+dx to y-dy, x-dx to y+dy, x+dx to y+dy).all { (px, py) ->
            val actual = bitmap.getPixel(px, py)
            listOf(0, 8, 16).all { shift ->
                kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 2
            }
        }
    }
}
