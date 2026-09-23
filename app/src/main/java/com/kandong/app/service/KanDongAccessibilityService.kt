package com.kandong.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.kandong.app.capture.AndroidNodeReader
import com.kandong.app.capture.UnsupportedScreen
import com.kandong.app.capture.WindowSelector
import com.kandong.app.domain.BoundedNodeReader
import com.kandong.app.domain.CandidateSelector
import com.kandong.app.domain.NodeSnapshot
import com.kandong.app.domain.ReadResult
import com.kandong.app.domain.ScreenContext
import com.kandong.app.domain.Session
import com.kandong.app.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class KanDongAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong(0)
    private val busy = AtomicBoolean(false)
    private val session = Session(SystemClock::elapsedRealtime)
    private val selector by lazy { WindowSelector(this) }
    private var overlays: OverlayController? = null
    internal var lastEventForTest = ""
        private set
    private var connected = false
    private var registered = false
    private val expiry = Runnable { invalidate("解释已过期，请重新读取页面。") }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopSession("屏幕已关闭，辅助已停止。") }
    }
    private val magnificationListener = object : MagnificationController.OnMagnificationChangedListener {
        override fun onMagnificationChanged(controller: MagnificationController, region: android.graphics.Region,
            scale: Float, centerX: Float, centerY: Float) {
            if (session.running) invalidate("屏幕放大状态变化，请重新读取。")
        }
        override fun onMagnificationChanged(controller: MagnificationController, region: android.graphics.Region,
            config: android.accessibilityservice.MagnificationConfig) {
            if (session.running) invalidate("屏幕放大状态变化，请重新读取。")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlays?.close()
        session.stop()
        epoch.set(session.generation)
        connected = true
        ServiceBridge.service = this
        ServiceBridge.consent = false
        overlays = OverlayController(this, ::captureOnce, { select(-1) }, { select(1) },
            { stopSession() }, { invalidate("面板已移动，请重新读取页面。") })
        if (!registered) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            magnificationController.addListener(magnificationListener, handler)
            registered = true
        }
        publish("服务已连接；请先阅读说明并同意，再开始辅助。")
    }

    internal fun startSession() {
        if (!connected || !ServiceBridge.consent) return
        session.start()
        epoch.set(session.generation)
        try {
            overlays?.showPanel()
            publish("辅助已开始。切换到其他应用，点悬浮面板的“读取页面”。")
        } catch (_: RuntimeException) {
            stopSession("无法显示控制面板，请停止后重试。")
        }
    }

    internal fun stopSession(message: String = "辅助已停止，页面信息已清除。系统无障碍开关仍需手动关闭。") {
        session.stop()
        epoch.set(session.generation)
        handler.removeCallbacksAndMessages(null)
        overlays?.close()
        publish(message)
    }

    /** Only panel action or same-process instrumentation calls this. No event-driven capture. */
    internal fun captureOnce() {
        if (!connected || !session.running || !ServiceBridge.consent) return
        if (busy.get()) { overlays?.update("上次读取正在结束，请稍后再试。"); return }
        val panel = overlays?.panelBounds()
        if (panel == null) { overlays?.update("控制面板正在准备，请稍后再试。"); return }
        invalidate("正在读取这一次页面…")
        val token = session.generation
        val started = SystemClock.elapsedRealtime()
        val ownIds = overlays?.ownWindowIds().orEmpty()
        busy.set(true)
        publish("正在读取这一次页面…", capturing = true)
        executor.execute {
            var context: ScreenContext? = null
            var result: ReadResult? = null
            var feedback = "页面读取失败，请回到普通页面后重试。"
            try {
                if (epoch.get() == token) {
                    val selected = selector.select(ownIds)
                    context = selected.context
                    result = BoundedNodeReader(SystemClock::elapsedRealtime,
                        cancelled = { epoch.get() != token || Thread.currentThread().isInterrupted })
                        .read(AndroidNodeReader(selected.root), selected.context)
                    if (result.rejected) feedback = "页面过于复杂或正在变化，本次未显示结果，请重试。"
                }
            } catch (unsupported: UnsupportedScreen) {
                feedback = unsupported.feedback
            } catch (_: RuntimeException) {
                // Deliberately never log exceptions with third-party content or window data.
            } finally {
                busy.set(false)
            }
            // Drop late results before enqueueing any main-thread work.
            if (epoch.get() == token) handler.post {
                if (!connected || !session.running || session.generation != token) return@post
                val capturedContext = context
                val capturedResult = result
                if (capturedContext == null || capturedResult == null || capturedResult.rejected) {
                    invalidate(feedback)
                    return@post
                }
                val current = currentContext()
                if (current != capturedContext) {
                    invalidate("页面已变化，请重新读取。")
                    return@post
                }
                if (!session.accept(token, capturedContext, capturedResult, started, overlays?.panelBounds())) {
                    invalidate("没有可安全解释的标签。可移动面板后重试；输入和密码区域会跳过。")
                    return@post
                }
                handler.postDelayed(expiry, maxOf(0, started + session.lifetimeMs - SystemClock.elapsedRealtime()))
                displaySelection(session.current(current))
            }
        }
    }

    private fun select(delta: Int) {
        val current = currentContext()
        displaySelection(session.move(delta, current))
    }

    private fun displaySelection(node: NodeSnapshot?) {
        val snapshot = session.snapshot
        if (node == null || snapshot == null) {
            invalidate("页面或解释已失效，请重新读取。")
            return
        }
        val token = session.generation
        fun valid(): Boolean {
            if (!connected || !session.running || session.generation != token ||
                SystemClock.elapsedRealtime() >= snapshot.expiresAt ||
                overlays?.panelBounds()?.intersects(node.bounds) != false) return false
            return try { selector.validateTarget(snapshot.context, node, overlays?.ownWindowIds().orEmpty()) }
            catch (_: RuntimeException) { false }
        }
        if (!valid()) { invalidate("目标已变化或被遮挡，请重新读取。"); return }
        overlays?.update("${session.selectedIndex + 1}/${session.candidates.size} ${CandidateSelector.explanation(node)}" +
            if (node.labelTruncated) "（标签已截短）" else "",
            session.selectedIndex > 0, session.selectedIndex < session.candidates.lastIndex)
        try {
            overlays?.showHighlight(node.bounds,
                mayDraw = { session.running && session.generation == token &&
                    SystemClock.elapsedRealtime() < snapshot.expiresAt },
                validateAfterLayout = {
                    val valid = valid()
                    if (!valid) invalidate("目标位置已变化，请重新读取。")
                    valid
                })
            publish("已解释第 ${session.selectedIndex + 1} 项。请在原应用中核对后亲自点击。")
        } catch (_: RuntimeException) {
            invalidate("无法显示高亮，请重新读取。")
        }
    }

    private fun currentContext(): ScreenContext? = try {
        selector.select(overlays?.ownWindowIds().orEmpty()).context
    } catch (_: RuntimeException) { null }

    private fun invalidate(message: String) {
        session.invalidate()
        epoch.set(session.generation)
        handler.removeCallbacks(expiry)
        overlays?.hideHighlight()
        overlays?.update(message)
        publish(message)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!session.running || event == null) return
        // Do not read event text or source. Unknown origins invalidate conservatively.
        val removalOnly = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.windowChanges == AccessibilityEvent.WINDOWS_CHANGE_REMOVED
        if (overlays?.isOwnEvent(event.windowId, event.packageName?.toString(), removalOnly) == true) return
        if (session.snapshot != null || ServiceBridge.status.capturing) {
            lastEventForTest = "type=${event.eventType} window=${event.windowId} package=${event.packageName}"
            invalidate("页面已变化，请重新读取。")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (session.running) invalidate("屏幕布局变化，请重新读取。")
    }

    override fun onInterrupt() { stopSession("服务被中断，辅助已停止。") }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun disconnect() {
        connected = false
        stopSession("服务已断开，页面信息已清除。")
        ServiceBridge.consent = false
        if (ServiceBridge.service === this) ServiceBridge.service = null
        if (registered) {
            unregisterReceiver(screenOff)
            magnificationController.removeListener(magnificationListener)
            registered = false
        }
        overlays = null
    }

    private fun publish(message: String, capturing: Boolean = false) {
        ServiceBridge.status = ServiceStatus(connected, session.running, capturing, message,
            session.candidates.size, session.snapshot?.truncated == true)
    }

    // Internal metadata for same-process instrumentation; no labels, bounds or exported IPC.
    internal val hasSnapshotForTest get() = session.snapshot != null
    internal val isRunningForTest get() = session.running
    internal val hasHighlightForTest get() = overlays?.hasHighlight == true
}
