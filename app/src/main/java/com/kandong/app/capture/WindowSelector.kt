package com.kandong.app.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.PowerManager
import android.os.Build
import android.accessibilityservice.MagnificationConfig
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import com.kandong.app.domain.isOutsideContent
import com.kandong.app.domain.Box
import com.kandong.app.domain.ScreenContext
import com.kandong.app.domain.NodeSnapshot
import com.kandong.app.domain.NodePolicy

internal class UnsupportedScreen(val feedback: String) : RuntimeException()
internal data class SelectedWindow(val context: ScreenContext, val root: AccessibilityNodeInfo)

/** No label reads here. Only identity/geometry; never fall back to an underlying root. */
internal class WindowSelector(private val service: AccessibilityService) {
    fun validateTarget(context: ScreenContext, target: NodeSnapshot, ownIds: Set<Int>): Boolean {
        val selected = select(ownIds)
        if (selected.context != context) return false
        var node: com.kandong.app.domain.NodeReader = AndroidNodeReader(selected.root)
        val path = target.path.split('/').drop(1)
        if (path.size > 24) return false
        for (part in path) {
            if (NodePolicy.skipSubtree(node.flags)) return false
            val index = part.toIntOrNull() ?: return false
            if (index !in 0 until node.childCount) return false
            node = node.child(index) ?: return false
        }
        return !NodePolicy.skipSubtree(node.flags) && node.visible && node.enabled &&
            node.windowId == context.windowId && node.packageName == context.packageName &&
            node.bounds.clip(context.bounds) == target.bounds && node.clickable == target.clickable &&
            node.className == target.className && node.viewId == target.viewId
    }

    fun select(ownWindowIds: Set<Int>): SelectedWindow {
        val power = service.getSystemService(PowerManager::class.java)
        val lock = service.getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || lock.isKeyguardLocked) reject("屏幕已锁定，请解锁后重新读取。")
        val magnification = service.magnificationController.magnificationConfig
            ?: reject("无法确认屏幕放大状态，请稍后重试。")
        val magnified = if (Build.VERSION.SDK_INT >= 34) magnification.isActivated
            else magnification.scale > 1f || magnification.mode == MagnificationConfig.MAGNIFICATION_MODE_WINDOW
        if (magnified) {
            reject("当前放大模式尚不支持，请关闭放大后重试。")
        }
        val wm = service.getSystemService(WindowManager::class.java)
        val metrics = wm.maximumWindowMetrics
        val displayBounds = metrics.bounds.toBox()
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val usable = Box(displayBounds.left + insets.left, displayBounds.top + insets.top,
            displayBounds.right - insets.right, displayBounds.bottom - insets.bottom)
        val windows = service.windows
        if (windows.isEmpty() || windows.size > 16) reject("无法确认当前窗口，请回到普通应用页面。")
        if (windows.any { it.displayId != Display.DEFAULT_DISPLAY }) reject("暂不支持外接屏幕。")
        val external = windows.filterNot {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.id in ownWindowIds
        }
        val apps = external.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (apps.size != 1) reject("暂不支持弹窗、分屏或多窗口，请关闭后重新读取。")
        val app = apps.single()
        if (!app.isActive || !app.isFocused || app.isInPictureInPictureMode) {
            reject("请先切换到要理解的普通应用页面。")
        }
        val bounds = Rect().also(app::getBoundsInScreen).toBox()
        // Accommodate edge-to-edge and inset layouts, but no floating/partial-screen windows.
        if (!bounds.contains(usable) || !displayBounds.contains(bounds)) {
            reject("当前窗口不是完整页面，暂不支持解释。")
        }
        for (window in external) {
            if (window.id == app.id) continue
            val cover = Rect().also(window::getBoundsInScreen).toBox()
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && cover.intersects(bounds)) {
                reject("请先收起键盘，再读取页面。")
            }
            // Only system bars entirely outside the usable display area are tolerated.
            val isSystemBar = window.type == AccessibilityWindowInfo.TYPE_SYSTEM && !window.isActive && !window.isFocused &&
                isOutsideContent(cover, usable)
            if (!isSystemBar && (window.isActive || window.isFocused || window.layer >= app.layer) &&
                cover.intersects(bounds)) reject("页面有其他窗口遮挡，请关闭后重试。")
        }
        val root = app.root ?: reject("应用没有提供可读取的页面信息。")
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == service.packageName || packageName == "android" ||
            packageName.startsWith("com.android.") || packageName == "com.google.android.permissioncontroller") {
            reject("请切换到其他应用；看懂不读取自身或系统页面。")
        }
        if (root.windowId != app.id) reject("页面身份发生变化，请重新读取。")
        val rotation = service.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: reject("无法确认屏幕方向。")
        return SelectedWindow(ScreenContext(packageName, app.id, app.displayId, rotation, bounds), root)
    }

    private fun reject(message: String): Nothing = throw UnsupportedScreen(message)
}
