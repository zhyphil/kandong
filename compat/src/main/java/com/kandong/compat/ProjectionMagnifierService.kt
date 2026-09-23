package com.kandong.compat

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.roundToInt

/** Opt-in, local-only screen stream. No frame is saved or sent to any service. */
class ProjectionMagnifierService : Service() {
    companion object { @Volatile var running = false; private set }
    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var imageView: ImageView? = null
    private var message: TextView? = null
    private var panel: LinearLayout? = null
    private var handle: TextView? = null
    private var resizeHandle: TextView? = null
    private var outline: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var resizeParams: WindowManager.LayoutParams? = null
    private var outlineParams: WindowManager.LayoutParams? = null
    private var latest: Bitmap? = null
    private var source = Rect()
    private var screenWidth = 0
    private var screenHeight = 0
    private lateinit var geometry: MagnifierLayout
    private var crop = Box(0, 0, 1, 1)
    private var controls: Controls? = null
    private var gesture: SourceGesture? = null
    private var streamCancelled = false
    private var placementAvailable = false
    private var renderedFrames = 0L
    private var panelAtBottom = true
    private var closing = false
    private var receiverRegistered = false
    private var lastFrameAt = 0L
    private var frameSeen = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopSelf() }
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width != screenWidth || height != screenHeight) {
                // This spike stops instead of risking a stale crop/second virtual display.
                stopSelf()
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        if (intent == null || !android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            startNotification()
            @Suppress("DEPRECATION")
            val consent = intent.getParcelableExtra<Intent>("projectionConsent") ?: error("No consent")
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels
            val safeInsets = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) else null
            val layout = MagnifierLayout.create(screenWidth, screenHeight, resources.displayMetrics.density,
                safeInsets?.top ?: 0, safeInsets?.bottom ?: 0)
            if (layout == null) {
                Toast.makeText(this, "屏幕空间不足，请停止后在较大的屏幕上重试。", Toast.LENGTH_LONG).show()
                stopSelf(); return START_NOT_STICKY
            }
            geometry = layout
            val (width, height) = proportionalSize(layout.imageWidth, layout.imageHeight, layout.imageWidth / 2f,
                layout.minWidth..layout.imageWidth, layout.minHeight..layout.maxHeight) ?: error("No initial crop")
            crop = Box((screenWidth - width) / 2, (screenHeight * .32f).toInt() - height / 2, width, height)
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra("resultCode", Activity.RESULT_CANCELED), consent)
            projection!!.registerCallback(projectionCallback, main)
            showControls()
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ capture(it) }, main)
            display = projection!!.createVirtualDisplay("KanDong local magnifier", screenWidth, screenHeight,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
                else @Suppress("DEPRECATION") registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            receiverRegistered = true
            running = true
            main.postDelayed({ if (!frameSeen && !closing) message?.text = "暂未收到画面；请停止后重试。" }, 4000)
        } catch (_: RuntimeException) {
            Toast.makeText(this, "无法开启屏幕放大，请检查授权后重试。", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun startNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("magnifier", "看懂屏幕放大", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "magnifier").setSmallIcon(com.kandong.compat.R.drawable.ic_magnifier)
            .setContentTitle("看懂正在本机放大屏幕").setContentText("点开看懂或悬浮窗的停止可结束屏幕共享")
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
    private fun params(width: Int, height: Int, secure: Boolean) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (secure) WindowManager.LayoutParams.FLAG_SECURE else 0), PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }
    private fun showControls() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2)); setBackgroundColor(Color.rgb(7, 94, 84))
        }
        imageView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "取景框区域的原文放大画面"
        }.also {
            root.addView(it, LinearLayout.LayoutParams(geometry.imageWidth, geometry.imageHeight))
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateStatus() }
        }
        val row = LinearLayout(this).also { root.addView(it, LinearLayout.LayoutParams(-1, dp(48))) }
        message = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); maxLines = 2; gravity = Gravity.CENTER_VERTICAL
        }.also { row.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
        row.addView(Button(this).apply {
            text = "停止"; textSize = 17f; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            setOnClickListener { stopSelf() }
        }, LinearLayout.LayoutParams(dp(64), -1))
        panel = root
        panelParams = params(geometry.bottomPanel.width, geometry.bottomPanel.height, true).apply {
            title = "看懂放大显示窗"; x = geometry.bottomPanel.left; y = geometry.bottomPanel.top
        }
        wm.addView(root, panelParams)
        val mark = object : View(this) {
            val paint = Paint().apply { color = Color.rgb(220, 60, 20); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val location = outlineParams ?: return
                val outside = dp(2).toFloat()
                // At screen edges, clip the OUTSIDE border instead of shifting the crop.
                canvas.drawRect(source.left - location.x - outside, source.top - location.y - outside,
                    source.right - location.x + outside, source.bottom - location.y + outside, paint)
            }
        }
        outline = mark
        outlineParams = params(1,1,false).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Android12+ evaluates WINDOW alpha, not transparent pixels inside our border.
            alpha = if (Build.VERSION.SDK_INT >= 31) minOf(.7f,
                getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch * .9f)
                else .7f
            title = "看懂取景边框"
        }
        wm.addView(mark, outlineParams)
        fun grip(label: String, description: String) = TextView(this).apply {
            text = label; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(130, 45, 10)); contentDescription = description
        }
        handle = grip("移动", "拖动此处移动取景框；提示松手贴边时松开可看屏幕边缘")
        resizeHandle = grip("↘", "向内拖动角柄放大，向外拖动看更多内容")
        handleParams = params(geometry.moveWidth, geometry.grip, true).apply { title = "看懂取景拖动柄" }
        resizeParams = params(geometry.grip, geometry.grip, true).apply { title = "看懂取景缩放角柄" }
        handle!!.setOnTouchListener { _, event -> onGripTouch(GestureMode.MOVE, event) }
        resizeHandle!!.setOnTouchListener { _, event -> onGripTouch(GestureMode.RESIZE, event) }
        wm.addView(handle, handleParams)
        wm.addView(resizeHandle, resizeParams)
        renderGeometry()
    }
    private fun onGripTouch(mode: GestureMode, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idle = controls ?: return true
                if (gesture != null || event.pointerCount != 1) return true
                streamCancelled = false
                gesture = SourceGesture(mode, event.getPointerId(0), idle.side, crop,
                    if (mode == GestureMode.MOVE) idle.move else idle.resize, event.rawX, event.rawY, geometry)
                renderGeometry()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> cancelStream()
            MotionEvent.ACTION_MOVE -> {
                val active = gesture ?: return true
                if (!streamCancelled) {
                    if (event.pointerCount != 1 || event.getPointerId(0) != active.pointerId) cancelStream()
                    else { crop = active.update(event.getPointerId(0), event.pointerCount, event.rawX, event.rawY).source; renderGeometry() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val active = gesture ?: return true
                val up = event.actionMasked == MotionEvent.ACTION_UP && !streamCancelled &&
                    event.pointerCount == 1 && event.getPointerId(0) == active.pointerId
                if (up) active.update(event.getPointerId(0), 1, event.rawX, event.rawY)
                crop = active.finish(up)
                gesture = null; streamCancelled = false
                renderGeometry()
            }
        }
        return true // Consume the entire stream, including all remaining events after cancellation.
    }
    private fun cancelStream() {
        streamCancelled = true; gesture?.cancel(); updateStatus()
    }
    private fun visibleControls(): List<Box> {
        val active = gesture
        return if (active != null) listOf(active.result.grip)
            else controls?.let { listOf(it.move, it.resize) } ?: emptyList()
    }
    private fun placeGrip(view: View?, p: WindowManager.LayoutParams?, box: Box?, visible: Boolean) {
        if (view == null || p == null) return
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        p.flags = if (visible) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (box != null) { p.x = box.left; p.y = box.top }
        wm.updateViewLayout(view, p)
    }
    private fun renderGeometry() {
        clearFrame()
        source = Rect(crop.left, crop.top, crop.right, crop.bottom)
        val active = gesture
        if (active == null) controls = geometry.idleControls(crop, panelAtBottom)
        val visible = visibleControls()
        val selected = geometry.selectPanel(panelAtBottom, crop, visible)
        placementAvailable = selected != null && (active != null || controls != null)
        if (selected != null) panelAtBottom = selected
        panelParams?.apply {
            val box = geometry.panel(panelAtBottom); x = box.left; y = box.top
            panel?.let { wm.updateViewLayout(it, this) }
        }
        val move = if (active?.mode == GestureMode.MOVE) active.result.grip else controls?.move
        val resize = if (active?.mode == GestureMode.RESIZE) active.result.grip else controls?.resize
        placeGrip(handle, handleParams, move, move != null && (active == null || active.mode == GestureMode.MOVE))
        placeGrip(resizeHandle, resizeParams, resize, resize != null && (active == null || active.mode == GestureMode.RESIZE))
        val side = active?.side ?: controls?.side
        resizeHandle?.text = when {
            side == null -> "↘"
            side.below && side.right -> "↘"
            side.below -> "↙"
            side.right -> "↗"
            else -> "↖"
        }
        outlineParams?.apply {
            x = (source.left - dp(3)).coerceAtLeast(0); y = (source.top - dp(3)).coerceAtLeast(0)
            width = (source.right + dp(3)).coerceAtMost(screenWidth) - x
            height = (source.bottom + dp(3)).coerceAtMost(screenHeight) - y
            outline?.let { wm.updateViewLayout(it, this); it.invalidate() }
        }
        // Discard queued old-layout images. Image timestamps have producer-specific timebases;
        // comparing them with System.nanoTime can permanently blank an OEM's capture stream.
        // The next compositor image is cropped using the current screen-pixel geometry.
        try { reader?.acquireLatestImage()?.close() } catch (_: IllegalStateException) { }
        updateStatus()
    }
    private fun sourceOverlapsProtectedControls(): Boolean = !placementAvailable ||
        !crop.inside(screenWidth, screenHeight) || !geometry.fits(panelAtBottom, crop, visibleControls()) ||
        visibleControls().any { crop.expanded(dp(3)).intersects(it) }
    private fun updateStatus() {
        val active = gesture?.result
        val view = imageView
        val scale = actualScale((view?.width ?: 0) - (view?.paddingLeft ?: 0) - (view?.paddingRight ?: 0),
            (view?.height ?: 0) - (view?.paddingTop ?: 0) - (view?.paddingBottom ?: 0), crop)
        message?.text = when {
            !placementAvailable -> "空间不足，请移动取景框"
            active != null && (active.snapX != 0 || active.snapY != 0) -> "松手贴边"
            scale > 0 -> String.format(java.util.Locale.ROOT, "%.2f× · 本机", scale)
            else -> "拖动取景框，看清原文"
        }
    }
    private fun clearFrame() { imageView?.setImageDrawable(null); latest?.recycle(); latest = null }
    private fun capture(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        try {
            if (closing || !running) return
            frameSeen = true
            val now = SystemClock.elapsedRealtime()
            // After clearing a crop, the next compositor frame may be the last on a static page.
            // Always show that first refresh; throttle only while an image is already visible.
            if (latest != null && now-lastFrameAt < 120) return
            lastFrameAt = now
            if (sourceOverlapsProtectedControls()) {
                clearFrame(); message?.text = "空间不足，请移动取景框"; return
            }
            val plane = image.planes[0]
            // Copy ONLY crop rows out of the full-screen system buffer; never make a full-screen bitmap.
            val bytes = CropPixels.copy(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride,
                source.left, source.top, source.width(), source.height())
            val bitmap = Bitmap.createBitmap(source.width(),source.height(),Bitmap.Config.ARGB_8888)
            bitmap.density = Bitmap.DENSITY_NONE // Crop dimensions and viewport are physical pixels.
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
            val previous = latest
            latest = bitmap; imageView?.setImageBitmap(bitmap); previous?.recycle()
            renderedFrames++
            updateStatus()
        } catch (_: RuntimeException) {
            clearFrame(); message?.text = "画面暂不可用，请移开遮挡或停止后重试。"
        } finally { image.close() }
    }
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        // Android's existing adb/service diagnostics, debug builds only. No text or image content.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!::geometry.isInitialized) { writer.println("geometry=unavailable"); return }
        fun box(value: Box?) = value?.let { "${it.left},${it.top},${it.width},${it.height}" } ?: "none"
        val view = imageView
        writer.println("crop=${box(crop)} panelBottom=$panelAtBottom available=$placementAvailable")
        writer.println("move=${box(controls?.move)} resize=${box(controls?.resize)}")
        writer.println("gesture=${gesture?.mode ?: "none"} renderedFrames=$renderedFrames bitmap=${latest?.width ?: 0},${latest?.height ?: 0}")
        writer.println("viewport=${view?.width ?: 0},${view?.height ?: 0} scale=${actualScale(view?.width ?: 0, view?.height ?: 0, crop)}")
        val matrix = FloatArray(9)
        view?.imageMatrix?.getValues(matrix)
        val drawable = view?.drawable
        writer.println("drawable=${drawable?.intrinsicWidth ?: 0},${drawable?.intrinsicHeight ?: 0} " +
            "matrixScale=${matrix[Matrix.MSCALE_X]},${matrix[Matrix.MSCALE_Y]}")
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation invalidates capture coordinates; require a fresh user-authorized session.
        stopSelf()
    }
    override fun onDestroy() {
        closing = true; running = false; main.removeCallbacksAndMessages(null)
        reader?.setOnImageAvailableListener(null,null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.unregisterCallback(projectionCallback); projection?.stop(); projection = null
        clearFrame()
        for (view in listOfNotNull(panel,handle,resizeHandle,outline)) if (view.isAttachedToWindow) wm.removeViewImmediate(view)
        gesture?.cancel(); gesture = null
        panel = null; handle = null; resizeHandle = null; outline = null; imageView = null; message = null
        if (receiverRegistered) { unregisterReceiver(screenOff); receiverRegistered = false }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    private fun dp(n: Int) = (n*resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}
