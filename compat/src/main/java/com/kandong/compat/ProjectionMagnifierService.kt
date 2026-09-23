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
    private var outline: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var outlineParams: WindowManager.LayoutParams? = null
    private var latest: Bitmap? = null
    private var source = Rect()
    private var screenWidth = 0
    private var screenHeight = 0
    private var lensWidth = 0
    private var lensHeight = 0
    private var centerX = 0f
    private var centerY = 0f
    private var scale = 2
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
            lensWidth = screenWidth - dp(24)
            lensHeight = minOf(dp(160), screenHeight / 4)
            centerX = screenWidth / 2f; centerY = screenHeight * .32f
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
            setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "取景框区域的原文放大画面"
        }.also { root.addView(it, LinearLayout.LayoutParams(lensWidth, lensHeight)) }
        message = TextView(this).apply { text = "拖动取景框，看清原文"; textSize = 16f; setTextColor(Color.WHITE); maxLines = 2 }
            .also { root.addView(it, LinearLayout.LayoutParams(-1, dp(44))) }
        val row = LinearLayout(this).also(root::addView)
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; textSize = 17f; minWidth = 0; minimumWidth = 0; setPadding(0,0,0,0)
            setOnClickListener { action() }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f)) }
        for (n in listOf(2, 3, 4)) button("$n×") { scale = n; updateSource(); clearFrame(); message?.text = "${scale}倍 · 拖动取景框移动" }
        button("换边") {
            panelAtBottom = !panelAtBottom
            positionPanel()
            centerY = screenHeight * if (panelAtBottom) .32f else .72f
            updateSource(); clearFrame()
        }
        button("停止") { stopSelf() }
        panel = root
        panelParams = params(lensWidth + dp(4), lensHeight + dp(100), true).apply { title = "看懂放大显示窗" }
        positionPanel(false)
        wm.addView(root, panelParams)
        val mark = object : View(this) {
            val paint = Paint().apply { color = Color.rgb(220, 60, 20); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val d = dp(1).toFloat()
                canvas.drawRect(d,d,width-d,height-d,paint)
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
        handle = TextView(this).apply {
            text = "移动取景框"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(130, 45, 10))
            contentDescription = "拖动此处移动取景框"
        }
        handleParams = params(dp(140),dp(48),true).apply { title = "看懂取景拖动柄" }
        var downX = 0f; var downY = 0f; var oldX = 0f; var oldY = 0f
        handle!!.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; oldX = centerX; oldY = centerY; true }
                MotionEvent.ACTION_MOVE -> {
                    centerX = oldX + event.rawX - downX; centerY = oldY + event.rawY - downY
                    updateSource(); clearFrame(); true
                }
                MotionEvent.ACTION_UP -> { handle?.performClick(); true }
                else -> true
            }
        }
        wm.addView(handle, handleParams)
        updateSource()
    }
    private fun positionPanel(update: Boolean = true) {
        panelParams?.apply {
            x = dp(10)
            y = if (panelAtBottom) screenHeight - height - dp(32) else dp(32)
            if (update) panel?.let { wm.updateViewLayout(it, this) }
        }
    }
    private fun updateSource() {
        val width = lensWidth / scale
        val height = lensHeight / scale
        centerX = centerX.coerceIn(width / 2f + dp(4), screenWidth - width / 2f - dp(4))
        centerY = centerY.coerceIn(height / 2f + dp(80), screenHeight - height / 2f - dp(32))
        source = Rect((centerX - width / 2).toInt(), (centerY - height / 2).toInt(), (centerX - width / 2).toInt() + width, (centerY - height / 2).toInt() + height)
        outlineParams?.apply {
            x = source.left - dp(3); y = source.top - dp(3); this.width = source.width()+dp(6); this.height = source.height()+dp(6)
            outline?.let { wm.updateViewLayout(it,this) }
        }
        handleParams?.apply {
            x = (centerX - this.width / 2f).toInt().coerceIn(0,screenWidth - this.width)
            y = source.top - this.height - dp(6)
            handle?.let { wm.updateViewLayout(it,this) }
        }
    }
    private fun sourceOverlapsPanel(): Boolean {
        val p = panelParams ?: return true
        return Rect.intersects(source, Rect(p.x,p.y,p.x+p.width,p.y+p.height))
    }
    private fun clearFrame() { imageView?.setImageDrawable(null); latest?.recycle(); latest = null }
    private fun capture(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        try {
            if (closing || !running) return
            frameSeen = true
            val now = SystemClock.elapsedRealtime()
            if (now-lastFrameAt < 120) return // bounded ~8 fps spike, not a performance promise
            lastFrameAt = now
            if (sourceOverlapsPanel()) {
                clearFrame(); message?.text = "取景框与放大窗重叠，请移开或点换边。"; return
            }
            val plane = image.planes[0]
            // Copy ONLY crop rows out of the full-screen system buffer; never make a full-screen bitmap.
            val bytes = CropPixels.copy(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride,
                source.left, source.top, source.width(), source.height())
            val bitmap = Bitmap.createBitmap(source.width(),source.height(),Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
            val previous = latest
            latest = bitmap; imageView?.setImageBitmap(bitmap); previous?.recycle()
            message?.text = "${scale}倍 · 仅本机显示 · 停止可结束共享"
        } catch (_: RuntimeException) {
            clearFrame(); message?.text = "画面暂不可用，请移开遮挡或停止后重试。"
        } finally { image.close() }
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
        for (view in listOfNotNull(panel,handle,outline)) if (view.isAttachedToWindow) wm.removeViewImmediate(view)
        panel = null; handle = null; outline = null; imageView = null; message = null
        if (receiverRegistered) { unregisterReceiver(screenOff); receiverRegistered = false }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
}
