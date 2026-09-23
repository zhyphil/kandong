package com.kandong.app

import android.Manifest
import android.os.Build
import android.provider.Settings
import android.graphics.Color
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.app.service.ServiceBridge
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhaseZeroIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var automation: UiAutomation? = null

    @After fun stop() {
        instrumentation.runOnMainSync { ServiceBridge.service?.stopSession(); ServiceBridge.consent = false }
        automation = null
    }

    @Test fun productionManifestHasOnlyOwnSignaturePermissionAndBackupIsDisabled() {
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        val allowed = setOf("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        assertTrue("Only AndroidX own signature permission is allowed",
            info.requestedPermissions.orEmpty().all { it in allowed })
        assertEquals(0, info.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    /** Requires the fixture installed and user-enabled accessibility. Never enables the service. */
    @Test fun crossAppCaptureDoesNotClickAndPhysicalTouchPassesThroughHighlight() {
        val ui = prepareFixture()
        assertEquals("点击次数：0", find(ui, "点击次数：0")?.text?.toString())
        instrumentation.runOnMainSync { ServiceBridge.service!!.captureOnce() }
        await("Capture/highlight failed; check the service's safe refusal message") {
            var showing = false
            instrumentation.runOnMainSync { showing = ServiceBridge.service?.hasSnapshotForTest == true &&
                ServiceBridge.service?.hasHighlightForTest == true }
            showing
        }
        // Capture and panel selection are passive: counter must remain zero.
        assertNotNull(find(ui, "点击次数：0"))
        val target = find(ui, "测试按钮") ?: error("Fixture target missing")
        val bounds = Rect().also(target::getBoundsInScreen)
        await("Yellow border must match the external node on all four sides") {
            val bitmap = ui.takeScreenshot() ?: return@await false
            val inset = (3 * context.resources.displayMetrics.density).toInt()
            val samples = listOf(bounds.centerX() to bounds.top + inset,
                bounds.centerX() to bounds.bottom - inset,
                bounds.left + inset to bounds.centerY(), bounds.right - inset to bounds.centerY())
            try { samples.all { (x, y) ->
                val pixel = bitmap.getPixel(x, y)
                Color.red(pixel) > 220 && Color.green(pixel) > 220 && Color.blue(pixel) < 80
            } } finally { bitmap.recycle() }
        }
        if (InstrumentationRegistry.getArguments().getString("captureScreenshot") == "true") {
            val bitmap = requireNotNull(ui.takeScreenshot())
            try { java.io.File(context.cacheDir, "phase0-highlight.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { bitmap.recycle() }
        }
        tap(ui, bounds.exactCenterX(), bounds.exactCenterY())
        await("Touch did not reach fixture exactly once") { find(ui, "点击次数：1") != null }
        await("Underlying content change did not invalidate snapshot") {
            var cleared = false
            instrumentation.runOnMainSync { cleared = ServiceBridge.service?.hasSnapshotForTest == false &&
                ServiceBridge.service?.hasHighlightForTest == false }
            cleared
        }
        instrumentation.runOnMainSync { assertTrue(ServiceBridge.service!!.isRunningForTest) }
        ui.waitForIdle(300, 3_000)
        instrumentation.runOnMainSync { ServiceBridge.service!!.captureOnce() }
        await("Panel did not support repeated capture") {
            var captured = false
            instrumentation.runOnMainSync { captured = ServiceBridge.service?.hasSnapshotForTest == true }
            captured
        }
        assertNotNull(find(ui, "点击次数：1"))
        instrumentation.runOnMainSync {
            ServiceBridge.service!!.stopSession()
            assertFalse(ServiceBridge.service!!.hasSnapshotForTest)
            assertFalse(ServiceBridge.service!!.hasHighlightForTest)
        }
    }

    @Test fun expiryAndDialogClearTheResultWithoutStoppingThePanel() {
        val ui = prepareFixture()
        fun capture(): Long {
            ui.waitForIdle(300, 3_000)
            val started = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { ServiceBridge.service!!.captureOnce() }
            await("Expected a current snapshot") {
                var result = false
                instrumentation.runOnMainSync { result = ServiceBridge.service!!.hasSnapshotForTest && ServiceBridge.service!!.hasHighlightForTest }
                result
            }
            return started
        }
        val started = capture()
        await("Snapshot must expire after 15 seconds", 17_000) {
            var cleared = false
            instrumentation.runOnMainSync { cleared = !ServiceBridge.service!!.hasSnapshotForTest && !ServiceBridge.service!!.hasHighlightForTest }
            cleared
        }
        assertTrue("Unexpected early invalidation is not expiry: ${ServiceBridge.service?.lastEventForTest}", SystemClock.elapsedRealtime() - started >= 14_900)
        instrumentation.runOnMainSync { assertTrue(ServiceBridge.service!!.isRunningForTest) }
        capture()
        val dialogButton = requireNotNull(find(ui, "打开弹窗"))
        val bounds = Rect().also(dialogButton::getBoundsInScreen)
        tap(ui, bounds.exactCenterX(), bounds.exactCenterY())
        await("Dialog must invalidate old guidance") {
            var cleared = false
            instrumentation.runOnMainSync { cleared = !ServiceBridge.service!!.hasSnapshotForTest && !ServiceBridge.service!!.hasHighlightForTest }
            cleared
        }
        ui.waitForIdle(300, 3_000)
        instrumentation.runOnMainSync { ServiceBridge.service!!.captureOnce() }
        await("Dialog capture must finish with a safe refusal") {
            var refused = false
            instrumentation.runOnMainSync { refused = !ServiceBridge.status.capturing && !ServiceBridge.service!!.hasSnapshotForTest }
            refused
        }
    }

    private fun prepareFixture(): UiAutomation {
        // Default UiAutomation suppresses real accessibility services; this flag is essential.
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation = ui
        val serviceInfo = ui.serviceInfo
        serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = serviceInfo
        rebindEnabledServiceOnEmulator(ui)
        await("Enable KanDong accessibility on the dedicated test device before running integration tests") {
            var connected = false
            instrumentation.runOnMainSync { connected = ServiceBridge.service != null }
            connected
        }
        val nonce = "测试会话：${java.util.UUID.randomUUID()}"
        context.startActivity(Intent().putExtra("testSession", nonce).setComponent(ComponentName("com.kandong.fixture", "com.kandong.fixture.FixtureActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("New fixture activity did not open") { find(ui, nonce) != null }
        instrumentation.runOnMainSync { ServiceBridge.consent = true; ServiceBridge.service!!.startSession() }
        // Wait for overlay attachment and initial window events before explicit capture.
        ui.waitForIdle(300, 3_000)
        return ui
    }

    // am instrument restarts the target process; Android marks its bound service crashed.
    // Explicit emulator-only opt-in rebinds an ALREADY enabled component, preserving others.
    private fun rebindEnabledServiceOnEmulator(ui: UiAutomation) {
        if (InstrumentationRegistry.getArguments().getString("rebindAccessibility") != "true") return
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Rebind is emulator-only" }
        val component = ComponentName(context, com.kandong.app.service.KanDongAccessibilityService::class.java)
        val resolver = context.contentResolver
        val original = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val names = original.split(':').filter { it.isNotEmpty() }
        check(names.any { ComponentName.unflattenFromString(it) == component }) { "Enable the test service first" }
        val retained = names.filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")
        ui.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, retained))
            // A crashed service is already absent from getEnabledAccessibilityServiceList.
            // Wait for the system's configured list, not a misleading empty bound-service list.
            await("System did not release configured test service") {
                val state = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    ui.executeShellCommand("dumpsys accessibility")).bufferedReader().use { it.readText() }
                state.lineSequence().any { it.contains("Enabled services:") && !it.contains("com.kandong.app/") }
            }
        } finally {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            ui.dropShellPermissionIdentity()
        }
    }

    private fun fixtureRoot(ui: UiAutomation): AccessibilityNodeInfo? = ui.windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .mapNotNull { it.root }.firstOrNull { it.packageName == "com.kandong.fixture" }

    private fun find(ui: UiAutomation, text: String): AccessibilityNodeInfo? = fixtureRoot(ui)
        ?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text }

    private fun tap(ui: UiAutomation, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(ui.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    private fun await(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        val windows = automation?.windows?.joinToString { "type=${it.type} bounds=${Rect().also(it::getBoundsInScreen)}" }
        var metrics = ""
        instrumentation.runOnMainSync {
            ServiceBridge.service?.getSystemService(android.view.WindowManager::class.java)?.maximumWindowMetrics?.let {
                metrics = "display=${it.bounds} insets=${it.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())}"
            }
        }
        fail("$message; service status: ${ServiceBridge.status.message}; windows=$windows; $metrics")
    }
}
