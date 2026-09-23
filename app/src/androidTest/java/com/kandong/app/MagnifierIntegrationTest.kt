package com.kandong.app

import android.Manifest
import android.accessibilityservice.MagnificationConfig
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
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
class MagnifierIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var automation: UiAutomation? = null

    @After fun stop() {
        instrumentation.runOnMainSync { ServiceBridge.service?.stopSession(); ServiceBridge.consent = false }
    }
    @Test fun manifestLimitsCapabilitiesToMagnification() {
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        assertTrue(info.requestedPermissions.orEmpty().all { it == "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" })
        assertEquals(0, info.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        val installed = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            .installedAccessibilityServiceList.single { it.resolveInfo.serviceInfo.packageName == context.packageName }
        assertEquals(android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_CONTROL_MAGNIFICATION, installed.capabilities)
    }
    @Test fun windowMagnifiesCrossAppAt234MovesAndKeepsOriginalLayout() {
        val ui = prepare()
        val signature = layoutSignature(ui)
        assertNotNull("Fixture must report local layout", signature)
        val before = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync { ServiceBridge.consent = true; ServiceBridge.service!!.startSession() }
        await("Window magnifier must become observed active") { ServiceBridge.status.active }
        println("WINDOW_START_OBSERVED_MS=" + (SystemClock.elapsedRealtime()-before))
        for (scale in listOf(2f, 3f, 4f)) {
            instrumentation.runOnMainSync { ServiceBridge.service!!.setScale(scale) }
            await("Expected WINDOW at $scale with nonempty source") {
                var valid = false
                instrumentation.runOnMainSync {
                    val service = ServiceBridge.service!!
                    val config = service.magnificationController.magnificationConfig
                    valid = config?.mode == MagnificationConfig.MAGNIFICATION_MODE_WINDOW && config.scale == scale &&
                        service.sourceBoundsForTest?.isEmpty == false && service.hasControlsForTest && ServiceBridge.status.active
                }; valid
            }
            SystemClock.sleep(350)
            assertEquals("Native magnification must not reflow original app", signature, layoutSignature(ui))
            assertNotNull("Scale changes must never tap the app", find(ui, "点击次数：0"))
            saveScreenshot(ui, "phase0a-native-${scale.toInt()}x.png")
        }
        var region: Rect? = null
        instrumentation.runOnMainSync { region = ServiceBridge.service!!.sourceBoundsForTest }
        val original = requireNotNull(region)
        instrumentation.runOnMainSync { ServiceBridge.service!!.moveSourceCenter(original.exactCenterX()+80, original.exactCenterY()+100) }
        await("Public center API must move the source region") {
            instrumentation.runOnMainSync { region = ServiceBridge.service!!.sourceBoundsForTest }
            region != null && region != original
        }
        assertEquals(signature, layoutSignature(ui))
        saveScreenshot(ui, "phase0a-native-moved.png")
        // Real framework Settings provides a second app; no contents are recorded.
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Settings did not open") { ui.windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == "com.android.settings" } }
        assertTrue(ServiceBridge.status.active)
        saveScreenshot(ui, "phase0a-native-settings.png")
        instrumentation.runOnMainSync { ServiceBridge.service!!.stopSession() }
        await("Stop must remove our controls and native magnifier") {
            var stopped = false
            instrumentation.runOnMainSync {
                val service = ServiceBridge.service!!
                stopped = !ServiceBridge.status.running && !service.hasControlsForTest && service.magnificationController.currentMagnificationRegion.isEmpty
            }; stopped
        }
    }
    @Test fun existingUserFullscreenMagnificationIsNotTakenOver() {
        prepare()
        try {
            instrumentation.runOnMainSync {
                val controller = ServiceBridge.service!!.magnificationController
                assertTrue(controller.setMagnificationConfig(MagnificationConfig.Builder()
                    .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN).setScale(2f).build(), false))
            }
            await("Test setup: fullscreen must activate") {
                var active = false
                instrumentation.runOnMainSync { active = ServiceBridge.service!!.magnificationController.magnificationConfig?.scale == 2f }
                active
            }
            instrumentation.runOnMainSync {
                ServiceBridge.consent = true; ServiceBridge.service!!.startSession()
                assertFalse(ServiceBridge.status.running)
                ServiceBridge.service!!.stopSession()
                val config = ServiceBridge.service!!.magnificationController.magnificationConfig!!
                assertEquals(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN, config.mode)
                assertEquals(2f, config.scale)
            }
        } finally {
            // Test cleanup of ONLY the magnification this test itself created.
            instrumentation.runOnMainSync { ServiceBridge.service?.magnificationController?.resetCurrentMagnification(false) }
        }
    }
    private fun prepare(): UiAutomation {
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation = ui
        val info = ui.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = info
        var connected = false
        instrumentation.runOnMainSync { connected = ServiceBridge.service != null }
        if (!connected) rebindEnabledServiceOnEmulator(ui)
        await("Enable the native service on the dedicated emulator") { ServiceBridge.service != null }
        instrumentation.runOnMainSync { assertFalse(ServiceBridge.status.running); assertTrue(ServiceBridge.status.supported) }
        val nonce = "测试会话：" + java.util.UUID.randomUUID().toString().take(8)
        context.startActivity(Intent().setComponent(ComponentName("com.kandong.fixture", "com.kandong.fixture.FixtureActivity"))
            .putExtra("testSession", nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Fresh fixture did not appear") { find(ui, nonce) != null && layoutSignature(ui) != null }
        ui.waitForIdle(300, 3000)
        return ui
    }
    private fun fixtureRoot(ui: UiAutomation): AccessibilityNodeInfo? = ui.windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
        .firstOrNull { it.packageName == "com.kandong.fixture" }
    private fun find(ui: UiAutomation, text: String) = fixtureRoot(ui)?.findAccessibilityNodeInfosByText(text)
        ?.firstOrNull { it.text?.toString() == text }
    private fun layoutSignature(ui: UiAutomation) = fixtureRoot(ui)?.findAccessibilityNodeInfosByText("KANDONG_LAYOUT:")
        ?.firstOrNull()?.text?.toString()
    private fun saveScreenshot(ui: UiAutomation, name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshot") != "true") return
        val bitmap = requireNotNull(ui.takeScreenshot())
        try { java.io.File(context.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        finally { bitmap.recycle() }
    }
    private fun await(message: String, timeoutMs: Long = 6000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis()+timeoutMs
        while (SystemClock.uptimeMillis()<deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("$message; ${ServiceBridge.status}")
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

}
