package com.kandong.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.MagnificationConfig
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.kandong.app.magnification.AndroidMagnifier
import com.kandong.app.magnification.MagnifierSession
import com.kandong.app.overlay.MagnifierControls

class KanDongAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var session = MagnifierSession()
    private var adapter: AndroidMagnifier? = null
    private var controls: MagnifierControls? = null
    private var connected = false
    private var supported = false
    private var registered = false
    private var resetting = false
    private var epoch = 0L
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            ServiceBridge.consent = false
            stopSession()
        }
    }
    private val listener = object : MagnificationController.OnMagnificationChangedListener {
        override fun onMagnificationChanged(controller: MagnificationController, region: Region,
            scale: Float, centerX: Float, centerY: Float) { refresh() }
        override fun onMagnificationChanged(controller: MagnificationController, region: Region,
            config: MagnificationConfig) { refresh() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        epoch++; handler.removeCallbacksAndMessages(null)
        session = MagnifierSession() // Reconnection never resumes a previous session.
        connected = true
        ServiceBridge.service = this
        ServiceBridge.consent = false
        supported = packageManager.hasSystemFeature(PackageManager.FEATURE_WINDOW_MAGNIFICATION)
        adapter = AndroidMagnifier(magnificationController)
        controls = MagnifierControls(this, ::setScale, ::stopSession) {
            session.feedback("控制面板无法显示，正在关闭放大镜。")
            stopSession()
        }
        try {
            if (!registered) {
                registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
                registered = true
            }
            magnificationController.addListener(listener, handler)
        } catch (_: RuntimeException) { supported = false }
        session.feedback(if (supported) "服务已连接，请同意本次使用。" else "本设备暂不能使用系统局部放大镜。")
        publish()
    }

    internal fun startSession() {
        if (!connected || !supported || !ServiceBridge.consent || session.running) return
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            session.feedback("请先解锁屏幕，再打开放大镜。"); publish(); return
        }
        val token = session.begin(adapter?.read()) ?: run { publish(); return }
        val metrics = resources.displayMetrics
        session.requestResult(token, adapter?.open(2f, metrics.widthPixels / 2f, metrics.heightPixels / 2f))
        if (!session.running) ServiceBridge.clearConsent()
        refresh()
        if (session.running) {
            val serviceEpoch = epoch
            handler.postDelayed({
                if (connected && epoch == serviceEpoch && token == session.generation) {
                    session.timeout(token)
                    if (!session.active) { ServiceBridge.clearConsent(); clearControls() }
                    attemptCleanup(); publish()
                }
            }, 2500)
        }
        publish()
    }

    internal fun stopSession() {
        if (!connected) return
        ServiceBridge.clearConsent()
        session.stop()
        clearControls()
        attemptCleanup()
        publish()
        val token = session.generation; val serviceEpoch = epoch
        handler.postDelayed({
            if (connected && epoch == serviceEpoch && token == session.generation && session.running) {
                session.timeout(token); publish()
            }
        }, 1500)
    }

    internal fun setScale(scale: Float) {
        if (scale !in setOf(2f, 3f, 4f) || !canControl()) return
        if (scale == session.scale) return
        val result = adapter?.scale(scale)
        refresh()
        if (result != true) session.feedback("系统未确认倍率调整，请稍后重试。")
        else scheduleChangeCheck(scale)
        publish()
    }

    internal fun moveSourceCenter(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite() || !canControl()) return
        if (adapter?.center(x, y) != true) session.feedback("系统未确认位置调整，请使用镜框手柄移动。")
        refresh(); publish()
    }

    private fun canControl(): Boolean {
        if (!connected || !ServiceBridge.consent || !session.active) return false
        refresh()
        return session.active
    }

    private fun scheduleChangeCheck(expected: Float) {
        val token = session.generation; val serviceEpoch = epoch
        handler.postDelayed({
            if (connected && epoch == serviceEpoch && token == session.generation && session.active) {
                refresh()
                if (session.active && session.scale != expected) {
                    session.feedback("未能确认新倍率，请重试或关闭放大镜。"); publish()
                }
            }
        }, 1500)
    }

    private fun refresh() {
        if (!connected) return
        // Read current state, not a queued callback's potentially stale payload.
        val wasRunning = session.running
        session.observe(session.generation, adapter?.read())
        if (wasRunning && !session.running) ServiceBridge.clearConsent()
        if (session.phase == MagnifierSession.Phase.STOPPING) attemptCleanup()
        if (session.active && ServiceBridge.consent) {
            try { controls?.show(); controls?.update(session.scale) }
            catch (_: RuntimeException) { stopSession(); session.feedback("控制面板无法显示，请确认放大镜已关闭。") }
        } else clearControls()
        publish()
    }

    private fun attemptCleanup() {
        if (resetting || session.phase != MagnifierSession.Phase.STOPPING) return
        val current = adapter?.read()
        session.observe(session.generation, current)
        if (!session.mayReset(current)) {
            if (session.running) session.feedback("尚未确认放大已关闭，请重试；必要时在系统中手动关闭。")
            return
        }
        resetting = true
        try {
            session.resetResult(adapter?.reset() == true)
            session.observe(session.generation, adapter?.read())
        } finally { resetting = false }
    }

    private fun clearControls() {
        try { controls?.close() }
        catch (_: RuntimeException) { session.feedback("控制面板未能移除，请在系统设置中关闭看懂服务。") }
    }
    // No event text, source, node or page metadata is read.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stopSession()
    }
    override fun onInterrupt() { stopSession() }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    private fun disconnect() {
        if (!connected) return
        stopSession()
        val incomplete = session.running
        connected = false; epoch++
        handler.removeCallbacksAndMessages(null)
        try { magnificationController.removeListener(listener) } catch (_: RuntimeException) { }
        if (registered) {
            try { unregisterReceiver(screenOff) } catch (_: RuntimeException) { }
            registered = false
        }
        clearControls(); controls = null; adapter = null
        if (ServiceBridge.service === this) {
            ServiceBridge.service = null
            ServiceBridge.consent = false
            ServiceBridge.status = ServiceStatus(message = if (incomplete)
                "服务已断开，无法确认放大已关闭。请在系统中手动关闭放大。" else "服务已断开，放大镜已关闭。")
        }
    }
    private fun publish() {
        if (connected && ServiceBridge.service === this) ServiceBridge.status = ServiceStatus(
            connected, supported, session.running, session.active, session.scale, session.message)
    }
    internal val sourceBoundsForTest: Rect? get() = session.source?.let { Rect(it.left, it.top, it.right, it.bottom) }
    internal val hasControlsForTest: Boolean get() = controls?.visible == true
}
