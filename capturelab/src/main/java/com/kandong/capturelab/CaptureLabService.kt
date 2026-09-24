package com.kandong.capturelab

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.random.Random

/** One token, one VD, main-thread ownership, bounded direct sampling of fixed synthetic markers. */
class CaptureLabService : Service() {
    companion object {
        internal const val ACTION_START = "com.kandong.capturelab.START"
        internal const val ACTION_STOP = "com.kandong.capturelab.STOP"
        internal const val SESSION = "session"
        internal const val CONSENT = "consent"
        private const val TAG = "KDCaptureLab"
        private const val NOTIFICATION_ID = 81
    }
    private val main = Handler(Looper.getMainLooper())
    private val dm by lazy { getSystemService(DisplayManager::class.java) }
    private var owner = WeakReference<CaptureLabActivity>(null)
    private var session = 0
    private var closing = false
    private var accepting = false
    private var proof: CaptureProof? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlayContext: Context? = null
    private var overlayManager: WindowManager? = null
    private var cover: View? = null
    private var witness: WitnessView? = null
    private var geometry: FixtureGeometry? = null
    private var expected = Marker.forPhase(0, Phase.BASELINE)
    private var salt = 0
    private var ticket = 0
    private var width = 0
    private var height = 0
    private var rotation = 0
    private var acquired = 0
    private var closed = 0
    private var drained = 0
    private var displays = 0
    private var receiverRegistered = false
    private var displayRegistered = false
    private var phaseDeadline: Runnable? = null

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) endSession(EndReason.SCREEN_OFF)
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) endSession(EndReason.DISPLAY_CHANGED) }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && !displayUnchanged()) endSession(EndReason.DISPLAY_CHANGED)
        }
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { endSession(EndReason.REVOKED) }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width != this@CaptureLabService.width || height != this@CaptureLabService.height) endSession(EndReason.DISPLAY_CHANGED)
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (!isVisible) endSession(EndReason.OWNER_GONE)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closing) return
            val activity = visibleOwner() ?: run { endSession(EndReason.OWNER_GONE); return }
            if (!Settings.canDrawOverlays(this@CaptureLabService)) { endSession(EndReason.OVERLAY_PERMISSION); return }
            if (!displayUnchanged()) { endSession(EndReason.DISPLAY_CHANGED); return }
            activity.fixture.tick(); witness?.tick()
            main.postDelayed(this, 100L)
        }
    }

    // There is no exported binder or data input. A one-shot in-process owner claim is required.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (proof != null && intent.getIntExtra(SESSION, 0) == session) endSession(EndReason.USER_STOP)
            else if (proof == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || closing) {
            if (proof == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // Never consume another token or replace an existing virtual display.
        if (proof != null) return START_NOT_STICKY
        val requestedSession = intent.getIntExtra(SESSION, 0)
        val activity = CaptureOwner.claim(requestedSession) ?: run {
            // Reject this delivery only. An old A must not poison a queued, valid B.
            intent.removeExtra(CONSENT)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        session = requestedSession
        owner = WeakReference(activity); activity.service = this
        if (!Settings.canDrawOverlays(this)) { endSession(EndReason.OVERLAY_PERMISSION); return START_NOT_STICKY }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { endSession(EndReason.SCREEN_OFF); return START_NOT_STICKY }
        try {
            val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(CONSENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(CONSENT)
            // Remove the only delivered reference immediately. No token persistence/retry.
            intent.removeExtra(CONSENT)
            if (consent == null) { endSession(EndReason.CONSENT_CANCELLED); return START_NOT_STICKY }
            foreground()
            val metrics = activity.windowManager.currentWindowMetrics
            val maximum = activity.windowManager.maximumWindowMetrics.bounds
            require(metrics.bounds == maximum && maximum.left == 0 && maximum.top == 0)
            width = maximum.width(); height = maximum.height()
            require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216)
            rotation = activity.display?.rotation ?: error("display")
            geometry = activity.fixture.screenGeometry().also { require(it.inside(width, height)) }
            val defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: error("display")
            overlayContext = createDisplayContext(defaultDisplay).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            overlayManager = overlayContext!!.getSystemService(WindowManager::class.java)
            proof = CaptureProof(SystemClock.elapsedRealtime()); salt = Random.nextInt(16_384)
            dm.registerDisplayListener(displayListener, main); displayRegistered = true
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            receiverRegistered = true
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
            checkNotNull(projection).registerCallback(projectionCallback, main)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ readFrame(it) }, main)
            // The sole VD creation for this consent/session; resize ends instead of recreating it.
            virtualDisplay = projection!!.createVirtualDisplay("KanDong owned synthetic capture", width, height,
                resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, main)
            checkNotNull(virtualDisplay); displays++
            main.postDelayed({ endSession(EndReason.OVERALL_TIMEOUT) }, CaptureProof.OVERALL_TIMEOUT_MS)
            main.post(tick)
            nextPhase()
        } catch (_: RuntimeException) { endSession(EndReason.SETUP_FAILURE) }
        catch (_: OutOfMemoryError) { endSession(EndReason.SETUP_FAILURE) }
        return START_NOT_STICKY
    }

    private fun foreground() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("capturelab", "合成页采集实验", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, session, Intent(this, CaptureLabService::class.java)
            .setAction(ACTION_STOP).putExtra(SESSION, session), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, CaptureLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "capturelab")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("看懂采集实验进行中")
            .setContentText("临时整屏缓冲区；仅验证自有色块；点停止结束")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止并清理", stop).build()).build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun visibleOwner(): CaptureLabActivity? = owner.get()?.takeIf { it.sessionId == session && it.eligible() }
    private fun displayUnchanged(): Boolean {
        val activity = owner.get() ?: return false
        val bounds = activity.windowManager.currentWindowMetrics.bounds
        return bounds == Rect(0, 0, width, height) && activity.display?.rotation == rotation &&
            dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation == rotation
    }

    private fun nextPhase() {
        if (closing) return
        val activity = visibleOwner() ?: run { endSession(EndReason.OWNER_GONE); return }
        val engine = proof ?: return
        if (engine.results.size == Phase.entries.size) { endSession(EndReason.COMPLETED); return }
        accepting = false
        try {
            drain()
            removeOverlays()
            val phase = Phase.entries[engine.results.size]
            expected = Marker.forPhase(salt, phase)
            activity.showPhase(phase, expected)
            val g = checkNotNull(geometry)
            when (phase) {
                Phase.COVER, Phase.SECURE_COVER, Phase.RESTORE -> {
                    val view = View(checkNotNull(overlayContext)).apply { setBackgroundColor(Marker.COVER) }
                    cover = view
                    overlayManager!!.addView(view, overlayParams(g.patch, phase == Phase.SECURE_COVER))
                }
                Phase.SECURE_ACTIVITY -> {
                    val view = WitnessView(checkNotNull(overlayContext), expected)
                    witness = view
                    overlayManager!!.addView(view, overlayParams(g.witness, false))
                }
                else -> Unit
            }
            ticket = engine.begin(phase, SystemClock.elapsedRealtime())
            val version = ticket
            phaseDeadline = Runnable {
                if (!closing) engine.timeout(version, SystemClock.elapsedRealtime())?.let { phaseFinished() }
            }.also { main.postDelayed(it, CaptureProof.PHASE_TIMEOUT_MS) }
            accepting = true
        } catch (_: RuntimeException) { endSession(EndReason.SETUP_FAILURE) }
        catch (_: OutOfMemoryError) { endSession(EndReason.SETUP_FAILURE) }
    }

    // These inert windows cover only fixture patches. They must not pass touches through:
    // Android caps NOT_TOUCHABLE application-overlay alpha (normally at 0.8), blending
    // the marker with its background. Nonfocusable keeps the Activity/stop button usable.
    private fun overlayParams(box: Box, secure: Boolean) = WindowManager.LayoutParams(
        box.width, box.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if (secure) WindowManager.LayoutParams.FLAG_SECURE else 0),
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT; x = box.left; y = box.top
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "KanDong fixed synthetic overlay"
    }

    private fun actualBox(view: View): Box {
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        return Box(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    }

    private fun readFrame(source: ImageReader) {
        if (closing) return
        var error: EndReason? = null
        var completed = false
        try {
            val image = source.acquireLatestImage() ?: return
            acquired++
            try {
                run frame@ {
                    if (!accepting) { drained++; return@frame }
                    val activity = visibleOwner() ?: run { error = EndReason.OWNER_GONE; return@frame }
                    val g = checkNotNull(geometry)
                    if (!displayUnchanged() || activity.fixture.screenGeometry() != g) {
                        error = EndReason.DISPLAY_CHANGED; return@frame
                    }
                    // Window attachment is not capture proof. Wait for actual layout, then check
                    // it covers exactly the owned patch before interpreting captured samples.
                    for ((view, expectedBox) in listOf(cover to g.patch, witness to g.witness)) {
                        if (view != null) {
                            if (!view.isLaidOut) { drained++; return@frame }
                            if (actualBox(view) != expectedBox) { error = EndReason.OVERLAY_GEOMETRY; return@frame }
                        }
                    }
                    require(image.width == width && image.height == height && image.format == PixelFormat.RGBA_8888)
                    require(image.cropRect == Rect(0, 0, width, height) && image.planes.size == 1)
                    val plane = image.planes[0]
                    val sampler = RgbaSampler(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
                    val cells = FixtureGeometry.witnessCells(g.witness.width, g.witness.height)
                    val evidence = FrameEvidence(
                        timestamp = image.timestamp,
                        nonce = sampler.matches(g.first, expected.first) && sampler.matches(g.second, expected.second),
                        witness = witness != null &&
                            sampler.matches(cells.first.shift(g.witness.left, g.witness.top), expected.first) &&
                            sampler.matches(cells.second.shift(g.witness.left, g.witness.top), expected.second),
                        markerBlack = sampler.black(g.first) && sampler.black(g.second),
                        patch = sampler.patch(g.patch)
                    )
                    completed = proof?.observe(ticket, evidence, SystemClock.elapsedRealtime()) != null
                }
            } finally {
                // No image, plane, buffer, or captured color escapes this scope.
                image.close(); closed++
            }
        } catch (_: RuntimeException) { error = EndReason.BUFFER_INVALID }
        catch (_: OutOfMemoryError) { error = EndReason.BUFFER_INVALID }
        if (error != null) endSession(checkNotNull(error))
        else if (completed) phaseFinished()
    }

    private fun phaseFinished() {
        accepting = false
        phaseDeadline?.let(main::removeCallbacks); phaseDeadline = null
        main.post { nextPhase() }
    }

    private fun drain() {
        repeat(2) {
            val image = reader?.acquireLatestImage() ?: return
            acquired++
            try { drained++ } finally { image.close(); closed++ }
        }
    }

    private fun removeOverlays() {
        cover?.let { overlayManager?.removeViewImmediate(it) }; cover = null
        witness?.let { overlayManager?.removeViewImmediate(it) }; witness = null
    }

    internal fun endSession(reason: EndReason) {
        if (closing) return
        closing = true; accepting = false
        proof?.cancel(SystemClock.elapsedRealtime())
        main.removeCallbacksAndMessages(null)
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        // Each release is independent: one platform exception cannot skip the other resources.
        runCatching { cover?.let { overlayManager?.removeViewImmediate(it) } }; cover = null
        runCatching { witness?.let { overlayManager?.removeViewImmediate(it) } }; witness = null
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }; projection = null
        if (receiverRegistered) { runCatching { unregisterReceiver(screenOff) }; receiverRegistered = false }
        if (displayRegistered) { runCatching { dm.unregisterDisplayListener(displayListener) }; displayRegistered = false }
        overlayManager = null; overlayContext = null
        CaptureOwner.revoke(session)
        val report = LabReport(session, reason, proof?.results.orEmpty(), acquired, closed, drained, displays)
        metadata(report)
        owner.get()?.takeIf { it.sessionId == session }?.finished(report)
        owner.clear(); geometry = null; proof = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun metadata(report: LabReport) {
        for (r in report.phases) Log.i(TAG, JSONObject().put("event", "PHASE").put("session", report.session)
            .put("phase", r.phase.name).put("verdict", r.verdict.name).put("frames", r.frames)
            .put("nonceFrames", r.nonceFrames).put("blackFrames", r.blackFrames).put("baseFrames", r.baseFrames)
            .put("coverFrames", r.coverFrames).put("mismatchFrames", r.mismatchFrames).put("staleFrames", r.staleFrames)
            .put("nonMonotonicFrames", r.nonMonotonicFrames).put("streak", r.streak).put("elapsedMs", r.elapsedMs).toString())
        Log.i(TAG, JSONObject().put("event", "END").put("session", report.session).put("reason", report.reason.name)
            .put("api", Build.VERSION.SDK_INT).put("width", width).put("height", height).put("rotation", rotation)
            .put("acquired", report.acquired).put("closed", report.closed).put("drained", report.drained)
            .put("displays", report.displays).put("phaseCount", report.phases.size)
            .put("coverControls", report.protection.cover.name)
            .put("activityControls", report.protection.activity.name).toString())
    }

    override fun onTaskRemoved(rootIntent: Intent?) { endSession(EndReason.OWNER_GONE); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { endSession(EndReason.OWNER_GONE); super.onDestroy() }
}
