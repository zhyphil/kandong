package com.kandong.capturelab

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference

internal data class LabReport(
    val session: Int, val reason: EndReason, val phases: List<PhaseResult>,
    val acquired: Int, val closed: Int, val drained: Int, val displays: Int
) {
    val protection get() = ProtectionControls.assess(phases)
}

/** Main-thread-only one-shot ownership capability; never survives an Activity departure. */
internal object CaptureOwner {
    private var serial = 0
    private var owner: WeakReference<CaptureLabActivity>? = null
    private var ticket = 0
    fun reserve(activity: CaptureLabActivity): Int {
        check(activity.eligible())
        serial = if (serial == Int.MAX_VALUE) 1 else serial + 1
        ticket = serial; owner = WeakReference(activity)
        return ticket
    }
    fun claim(id: Int): CaptureLabActivity? {
        if (id <= 0 || ticket != id) return null
        val activity = owner?.get()?.takeIf { it.sessionId == id && it.eligible() }
        // Claim is single-use, including failed/late starts.
        ticket = 0; owner = null
        return activity
    }
    fun revoke(id: Int) { if (ticket == id) { ticket = 0; owner = null } }
}

class CaptureLabActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    internal val gate = ConsentGate()
    internal lateinit var fixture: FixtureView; private set
    internal var report: LabReport? = null; private set
    internal var sessionId = 0; private set
    internal var service: CaptureLabService? = null
    private var resumed = false
    private var grant: Intent? = null
    private lateinit var start: Button
    private lateinit var permission: Button
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var resultsScroll: ScrollView
    private val grantDeadline = Runnable { cancel(EndReason.CONSENT_TIMEOUT) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately ignore all Intent extras and saved session state.
        window.setDecorFitsSystemWindows(false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(dp(12) + edges.left, dp(8) + edges.top, dp(12) + edges.right, dp(8) + edges.bottom)
            insets
        }
        root.addView(TextView(this).apply { text = "看懂采集实验"; textSize = 21f })
        root.addView(TextView(this).apply {
            text = "每次授权后短暂采集整屏缓冲区，仅检查本页固定色块。不保存、不上传、不做 OCR。离开本页或停止即结束。只验证本次合成页面，不代表其他应用隐私验收。"
            textSize = 14f
        })
        permission = Button(this).apply {
            text = "1. 授权悬浮窗"; tag = "overlay-permission"
            setOnClickListener {
                cancel(EndReason.USER_STOP)
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: RuntimeException) { status.text = "无法打开系统悬浮窗设置。" }
            }
        }
        start = Button(this).apply { text = "2. 开始采集验证"; tag = "capture-start"; setOnClickListener { requestCapture() } }
        root.addView(permission); root.addView(start)
        root.addView(Button(this).apply {
            text = "停止并清理"; tag = "capture-stop"
            setOnClickListener { cancel(EndReason.USER_STOP) }
        })
        status = TextView(this).apply { textSize = 14f; maxLines = 2; tag = "capture-status" }
        // CJK fallback font metrics differ between one line and an explicit newline.
        // Freeze this band's height so status updates cannot move sampled fixture pixels.
        root.addView(status, LinearLayout.LayoutParams(-1, (status.textSize * 3.5f).toInt()))
        fixture = FixtureView(this).apply { tag = "capture-fixture" }
        root.addView(fixture, LinearLayout.LayoutParams(-1, 0, 1f))
        results = TextView(this).apply { textSize = 14f; tag = "capture-results" }
        resultsScroll = ScrollView(this).apply { addView(results); visibility = View.GONE }
        root.addView(resultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        status.text = "尚未采集。先手动授权悬浮窗，再开始屏幕共享。"
        refreshButtons()
    }

    private fun requestCapture() {
        if (!resumed || !hasWindowFocus() || gate.state != ConsentGate.State.IDLE) return
        if (!Settings.canDrawOverlays(this)) { cancel(EndReason.OVERLAY_PERMISSION); return }
        if (isInMultiWindowMode || display?.displayId != Display.DEFAULT_DISPLAY) {
            cancel(EndReason.DISPLAY_CHANGED); return
        }
        report = null; resultsScroll.visibility = View.GONE; fixture.visibility = View.VISIBLE
        if (!gate.request()) return
        refreshButtons(); status.text = "等待本次系统屏幕共享授权；取消不会开始采集。"
        main.postDelayed(grantDeadline, 120_000L)
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val request = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(request, CONSENT_REQUEST)
        } catch (_: RuntimeException) { cancel(EndReason.SETUP_FAILURE) }
    }

    @Deprecated("Uses the platform consent result without additional libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONSENT_REQUEST) return
        main.removeCallbacks(grantDeadline)
        if (!gate.result(resultCode == RESULT_OK && data != null)) {
            grant = null
            if (gate.state != ConsentGate.State.RUNNING) cancel(EndReason.CONSENT_CANCELLED)
            return
        }
        grant = data
        main.postDelayed(grantDeadline, 10_000L)
        main.post { tryStart() }
    }

    private fun tryStart() {
        if (gate.state != ConsentGate.State.READY || !resumed || !hasWindowFocus()) return
        if (!Settings.canDrawOverlays(this)) { cancel(EndReason.OVERLAY_PERMISSION); return }
        val token = grant ?: run { cancel(EndReason.CONSENT_CANCELLED); return }
        if (!gate.consume(resumed, hasWindowFocus())) return
        grant = null; main.removeCallbacks(grantDeadline)
        if (!eligible()) { cancel(EndReason.OWNER_GONE); return }
        sessionId = CaptureOwner.reserve(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "开始验证；离开页面、失焦或锁屏会立即停止。"
        refreshButtons()
        try {
            startForegroundService(Intent(this, CaptureLabService::class.java)
                .setAction(CaptureLabService.ACTION_START)
                .putExtra(CaptureLabService.SESSION, sessionId)
                .putExtra(CaptureLabService.CONSENT, token))
            // Also bounds delayed service delivery; this never retries or reuses the token.
            main.postDelayed({
                if (gate.state == ConsentGate.State.RUNNING && service == null) cancel(EndReason.SETUP_FAILURE)
            }, 5_000L)
        } catch (_: RuntimeException) { cancel(EndReason.SETUP_FAILURE) }
    }

    internal fun eligible() = resumed && hasWindowFocus() && !isFinishing && !isDestroyed &&
        window.decorView.isAttachedToWindow && !isInMultiWindowMode && display?.displayId == Display.DEFAULT_DISPLAY &&
        gate.state == ConsentGate.State.RUNNING

    internal fun showPhase(phase: Phase, marker: Marker) {
        if (phase == Phase.SECURE_ACTIVITY) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        fixture.marker = marker
        status.text = "${phase.code}/7 ${phase.label}\n正在等待同帧标记证据（最多约 40 秒）"
    }

    internal fun finished(value: LabReport) {
        service = null; CaptureOwner.revoke(sessionId); grant = null; gate.cancel()
        main.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        report = value
        status.text = "${value.reason.label}。只代表本次合成页／当前设备观察。"
        results.text = buildString {
            append("图像取得/关闭：${value.acquired}/${value.closed}；排空：${value.drained}；显示创建：${value.displays}\n")
            if (value.phases.isEmpty()) append("没有采集证据。\n")
            for (r in value.phases) {
                append("${r.phase.code}. ${r.phase.label}：${r.verdict.label}\n")
                append("帧 ${r.frames}；当前标记 ${r.nonceFrames}；黑 ${r.blackFrames}；底色 ${r.baseFrames}；遮挡色 ${r.coverFrames}；不符 ${r.mismatchFrames}；旧标记 ${r.staleFrames}；时间戳非递增 ${r.nonMonotonicFrames}；连续 ${r.streak}\n")
            }
            append("安全悬浮层对照：${value.protection.cover.label}；本页安全窗口对照：${value.protection.activity.label}\n")
            append("黑色遮蔽、底层可见均为限定观察；不符或超时不算通过。没有通用安全内容检测结论。")
        }
        fixture.visibility = View.GONE; resultsScroll.visibility = View.VISIBLE
        refreshButtons()
    }

    internal fun cancel(reason: EndReason) {
        CaptureOwner.revoke(sessionId); grant = null; main.removeCallbacksAndMessages(null)
        val current = service
        if (current != null) current.endSession(reason)
        else finished(LabReport(sessionId, reason, emptyList(), 0, 0, 0, 0))
    }

    override fun onResume() {
        super.onResume(); resumed = true; refreshButtons()
        main.post { tryStart() }
    }
    override fun onPause() {
        resumed = false
        if (gate.state == ConsentGate.State.RUNNING || gate.state == ConsentGate.State.READY) cancel(EndReason.BACKGROUND)
        super.onPause()
    }
    override fun onStop() {
        if (gate.state != ConsentGate.State.IDLE) cancel(EndReason.CONSENT_ABANDONED)
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && gate.state == ConsentGate.State.RUNNING) cancel(EndReason.FOCUS_LOST)
        else if (hasFocus) main.post { tryStart() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (gate.state != ConsentGate.State.IDLE) cancel(EndReason.DISPLAY_CHANGED)
    }
    override fun onDestroy() {
        if (gate.state != ConsentGate.State.IDLE || service != null) cancel(EndReason.OWNER_GONE)
        main.removeCallbacksAndMessages(null); super.onDestroy()
    }
    private fun refreshButtons() {
        if (!::start.isInitialized) return
        start.isEnabled = gate.state == ConsentGate.State.IDLE
        permission.isEnabled = gate.state == ConsentGate.State.IDLE
        permission.text = if (Settings.canDrawOverlays(this)) "1. 悬浮窗已授权（系统设置）" else "1. 授权悬浮窗"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val CONSENT_REQUEST = 81 }
}
