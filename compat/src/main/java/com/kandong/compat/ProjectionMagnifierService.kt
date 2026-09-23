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
    companion object {
        @Volatile var running = false; private set
        const val ACTION_START = "com.kandong.compat.START"
        const val ACTION_COLLAPSE = "com.kandong.compat.COLLAPSE"
        const val ACTION_RESUME = "com.kandong.compat.RESUME"
        const val ACTION_MENU = "com.kandong.compat.MENU"
        const val ACTION_STOP = "com.kandong.compat.STOP"
        const val SESSION = "session"
    }
    private val uiState = SessionUiState()
    private val sessionId = java.util.UUID.randomUUID().toString()
    private var surfaceAttached = false
    private var copyAttempts = 0L
    private var virtualDisplayCreates = 0
    private var generation = 0
    private var safeArea = Box(0,0,1,1)
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleGesture: BubbleGesture? = null
    private var menu: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuPage: String? = null
    private var menuBackDispatcher: android.window.OnBackInvokedDispatcher? = null
    private val menuBackCallback = if(Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { menuBack() } else null
    private var zoomLabel: TextView? = null
    private var longPressTask: Runnable? = null
    private val toolButtons = linkedMapOf<String, View>()
    private var displayListenerRegistered = false
    private var initialRotation = 0
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { }
        override fun onDisplayRemoved(id: Int) { if(id == Display.DEFAULT_DISPLAY) endSession() }
        override fun onDisplayChanged(id: Int) {
            if(id == Display.DEFAULT_DISPLAY && displayManager.getDisplay(id)?.rotation != initialRotation) endSession()
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var imageView: ImageView? = null
    private var message: TextView? = null
    private var zoomSlider: SeekBar? = null
    private val viewport = MagnifierViewport()
    private var panPointer = -1
    private var panLastX = 0f
    private var panLastY = 0f
    private var panel: LinearLayout? = null
    private var handle: IconView? = null
    private var resizeHandle: IconView? = null
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
        override fun onReceive(context: Context?, intent: Intent?) { endSession() }
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { endSession() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width != screenWidth || height != screenHeight) {
                // This spike stops instead of risking a stale crop/second virtual display.
                endSession()
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_START) {
            if (!closing && running && intent?.getStringExtra(SESSION) == sessionId) {
                when(action) {
                    ACTION_STOP -> endSession()
                    ACTION_COLLAPSE -> transition { uiState.collapse() }
                    ACTION_RESUME -> transition { uiState.resume() }
                    ACTION_MENU -> transition { uiState.menu() }
                }
            } else if (!running) endSession()
            return START_NOT_STICKY
        }
        if (running || closing) return START_NOT_STICKY
        val startIntent = intent ?: return START_NOT_STICKY
        if (!android.provider.Settings.canDrawOverlays(this) || getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            endSession(); return START_NOT_STICKY
        }
        try {
            updateNotification()
            @Suppress("DEPRECATION")
            val consent = startIntent.getParcelableExtra<Intent>("projectionConsent") ?: error("No consent")
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels
            val safeInsets = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) else {
                    // API29 lacks WindowMetrics. The visible consent Activity supplies its real insets.
                    val edges = startIntent.getIntArrayExtra("legacySafeInsets")
                    check(edges != null && edges.size == 4 && edges.all { it >= 0 })
                    check(startIntent.getIntExtra("legacyRotation", -1) == displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation)
                    Insets.of(edges[0], edges[1], edges[2], edges[3])
                }
            val layout = MagnifierLayout.create(screenWidth, screenHeight, resources.displayMetrics.density,
                safeInsets.top, safeInsets.bottom)
            if (layout == null) {
                Toast.makeText(this, "屏幕空间不足，请停止后在较大的屏幕上重试。", Toast.LENGTH_LONG).show()
                endSession(); return START_NOT_STICKY
            }
            geometry = layout
            safeArea = Box(safeInsets.left, safeInsets.top,
                screenWidth - (safeInsets.left) - (safeInsets.right),
                screenHeight - (safeInsets.top) - (safeInsets.bottom))
            check(safeArea.width >= dp(88) && safeArea.height >= dp(160))
            bubbleGesture = BubbleGesture(safeArea, dp(56), dp(4), maxOf(dp(8), ViewConfiguration.get(this).scaledTouchSlop).toFloat()).apply {
                place(Box(safeArea.right - dp(60), safeArea.top + safeArea.height / 2, dp(56),dp(56))); dock()
            }
            val width = (layout.imageWidth / 2).coerceAtLeast(layout.minWidth)
            val height = (layout.imageHeight / 2).coerceIn(layout.minHeight, layout.maxHeight)
            crop = Box((screenWidth - width) / 2, (screenHeight * .32f).toInt() - height / 2, width, height)
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(startIntent.getIntExtra("resultCode", Activity.RESULT_CANCELED), consent)
            projection!!.registerCallback(projectionCallback, main)
            showControls()
            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ capture(it) }, main)
            display = projection!!.createVirtualDisplay("KanDong local magnifier", screenWidth, screenHeight,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main)
            virtualDisplayCreates++; surfaceAttached = true
            initialRotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
            displayManager.registerDisplayListener(displayListener, main); displayListenerRegistered = true
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
                else @Suppress("DEPRECATION") registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            receiverRegistered = true
            running = true
            publish(); awaitFrame()
        } catch (_: RuntimeException) {
            Toast.makeText(this, "无法开启屏幕放大，请检查授权后重试。", Toast.LENGTH_LONG).show()
            endSession()
        }
        return START_NOT_STICKY
    }
    private fun publish() { SessionBridge.publish(SessionSnapshot(if(closing) null else sessionId, uiState.mode)) }
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("magnifier", "看懂屏幕放大", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, sessionId.hashCode(), Intent(this, ProjectionMagnifierService::class.java)
            .setAction(ACTION_STOP).putExtra(SESSION,sessionId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = !uiState.capturing
        val notification = Notification.Builder(this, "magnifier").setSmallIcon(R.drawable.ic_magnifier)
            .setContentTitle(if(paused) "看懂已暂停画面采集" else "看懂正在本机放大屏幕")
            .setContentText(if(paused) "屏幕共享会话仍保留，可恢复或结束共享" else "点开看懂可收起或结束共享")
            .setContentIntent(open).addAction(Notification.Action.Builder(null,"结束共享",stop).build()).setOngoing(true).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
    private fun awaitFrame() {
        val current = ++generation; frameSeen = false
        message?.text = "等待画面"
        main.postDelayed({ if(current == generation && uiState.capturing && !closing && !frameSeen)
            message?.text = "暂无画面，请结束后重试" },4000)
    }
    private fun drain() { reader?.acquireLatestImage()?.close() }
    private fun remove(view: View?) {
        if(view != null && view === menu && Build.VERSION.SDK_INT >= 33) {
            menuBackCallback?.let { menuBackDispatcher?.unregisterOnBackInvokedCallback(it) }; menuBackDispatcher=null
        }
        if(view?.isAttachedToWindow == true) wm.removeViewImmediate(view)
    }
    private fun cancelGestures() {
        gesture?.cancel(); gesture = null; streamCancelled = false; panPointer = -1
        longPressTask?.let(main::removeCallbacks); longPressTask = null; bubbleGesture?.cancel()
    }
    private fun removeExpanded() {
        listOf(panel,handle,resizeHandle,outline).forEach(::remove)
        panel=null; handle=null; resizeHandle=null; outline=null; imageView=null; message=null; zoomLabel=null; zoomSlider=null
        toolButtons.clear()
    }
    private fun pauseSurfaces() {
        // The state gate is already closed before any window or surface is changed.
        generation++; cancelGestures()
        display?.surface = null; surfaceAttached = false
        clearFrame(); drain(); removeExpanded(); remove(bubble); bubble=null; remove(menu); menu=null
    }
    private fun transition(change: () -> Boolean) {
        if(closing || !running) return
        if(getSystemService(KeyguardManager::class.java).isKeyguardLocked) { endSession(); return }
        try {
            if(!change()) return
            pauseSurfaces()
            when(uiState.mode) {
                SessionMode.EXPANDED -> {
                    check(projection != null && display != null && reader != null)
                    showControls(); drain(); awaitFrame()
                    display!!.surface = reader!!.surface; surfaceAttached = true
                }
                SessionMode.COLLAPSED -> showBubble()
                SessionMode.MENU -> { menuPage=null; showMenu() }
                SessionMode.TERMINAL -> { endSession(); return }
            }
            publish(); updateNotification()
        } catch (_: RuntimeException) { endSession() }
    }
    private fun showBubble() {
        val model = bubbleGesture ?: error("Missing bubble")
        val view = CompatUi.icon(this,LineIcon.MAGNIFY,"恢复放大镜") { transition { uiState.resume() } }.apply {
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x337CA99A),
                CompatUi.shape(this@ProjectionMagnifierService,CompatUi.teal,28).apply { setStroke(dp(1),Color.WHITE) },
                CompatUi.shape(this@ProjectionMagnifierService,Color.WHITE,28)); elevation=dp(6).toFloat()
            setOnLongClickListener { transition { uiState.menu() }; true }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info)
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01020001,"打开菜单"))
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01020002,"结束共享"))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when(action) {
                    0x01020001 -> { transition { uiState.menu() }; true }
                    0x01020002 -> { endSession(); true }
                    else -> super.performAccessibilityAction(host,action,args)
                }
            }
        }
        bubble=view
        val p=params(dp(56),dp(56),true).apply { x=model.position.left; y=model.position.top; title="看懂悬浮球" }
        bubbleParams=p
        fun cancelLong() { longPressTask?.let(main::removeCallbacks); longPressTask=null }
        view.setOnTouchListener { _, event ->
            if(closing || uiState.mode != SessionMode.COLLAPSED) return@setOnTouchListener true
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed=true
                    model.down(event.getPointerId(0),event.pointerCount,event.rawX,event.rawY)
                    longPressTask=Runnable { if(model.longPress()) view.performLongClick() }.also { main.postDelayed(it,ViewConfiguration.getLongPressTimeout().toLong()) }
                }
                MotionEvent.ACTION_MOVE -> {
                    val box=model.move(event.getPointerId(0),event.pointerCount,event.rawX,event.rawY)
                    if(model.dragged || event.pointerCount != 1) { cancelLong(); view.isPressed=false }
                    p.x=box.left; p.y=box.top
                    try { wm.updateViewLayout(view,p) } catch(_: RuntimeException) { endSession() }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> { cancelLong(); view.isPressed=false; model.cancel() }
                MotionEvent.ACTION_UP -> {
                    cancelLong(); view.isPressed=false; val tap=model.up(event.getPointerId(0),event.pointerCount,event.rawX,event.rawY)
                    p.x=model.position.left; p.y=model.position.top
                    try { wm.updateViewLayout(view,p); if(tap) view.performClick() } catch(_: RuntimeException) { endSession() }
                }
            }; true
        }
        wm.addView(view,p)
    }
    private fun canHandleMenu() = !closing && running && uiState.acceptsMenuCallbacks
    private fun showMenu() {
        if (!canHandleMenu()) return
        remove(menu)
        val wrapper=object : android.widget.FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if(event.keyCode == KeyEvent.KEYCODE_BACK) { if(event.action == KeyEvent.ACTION_UP) menuBack(); return true }
                return super.dispatchKeyEvent(event)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if(event.action == MotionEvent.ACTION_OUTSIDE) { menuBack(); return true }
                return super.onTouchEvent(event)
            }
        }
        wrapper.addView(CompatUi.menu(this,menuPage,{ transition { uiState.closeMenu() } },{ page ->
            if (canHandleMenu()) { menuPage=page; try { showMenu() } catch(_:RuntimeException) { endSession() } }
        },{endSession()}))
        val width=minOf(dp(320),safeArea.width-dp(32))
        val p=params(width,minOf(dp(560),safeArea.height-dp(32)),true).apply {
            flags=flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            x=safeArea.left+(safeArea.width-width)/2; y=safeArea.top+(safeArea.height-height)/2; title="看懂菜单"
        }
        wrapper.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if(canHandleMenu() && view === menu && Build.VERSION.SDK_INT >= 33 && menuBackCallback != null) {
                    menuBackDispatcher=view.findOnBackInvokedDispatcher()
                    menuBackDispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,menuBackCallback)
                }
            }
            override fun onViewDetachedFromWindow(view: View) { }
        })
        menu=wrapper; menuParams=p; wm.addView(wrapper,p); wrapper.isFocusableInTouchMode=true; wrapper.requestFocus()
    }
    private fun menuBack() {
        if (!canHandleMenu()) return
        if(menuPage!=null) { menuPage=null; try { showMenu() } catch(_:RuntimeException) { endSession() } }
        else transition { uiState.closeMenu() }
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
            setPadding(dp(MagnifierLayout.BORDER_DP), dp(MagnifierLayout.BORDER_DP), dp(MagnifierLayout.BORDER_DP), dp(MagnifierLayout.BORDER_DP))
            background = CompatUi.shape(this@ProjectionMagnifierService,CompatUi.teal,16)
        }
        val header = LinearLayout(this).also { root.addView(it,LinearLayout.LayoutParams(-1,dp(MagnifierLayout.CONTROL_DP))) }
        fun tool(icon: LineIcon, label: String, action: () -> Unit) {
            val button=CompatUi.icon(this,icon,label,action); toolButtons[label]=button
            header.addView(button,LinearLayout.LayoutParams(dp(MagnifierLayout.CONTROL_DP),-1))
        }
        tool(LineIcon.MENU,"打开菜单") { transition { uiState.menu() } }
        message=TextView(this).apply { textSize=14f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; maxLines=2 }
            .also { header.addView(it,LinearLayout.LayoutParams(0,-1,1f)) }
        tool(LineIcon.COLLAPSE,"收起放大镜") { transition { uiState.collapse() } }
        tool(LineIcon.END,"结束共享") { endSession() }
        imageView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.MATRIX
            contentDescription = "取景框区域的原文放大画面；可在画面内滑动查看"
            setOnTouchListener { _, event -> onImageTouch(event) }
        }.also {
            root.addView(it, LinearLayout.LayoutParams(geometry.imageWidth, geometry.imageHeight))
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyViewportTransform() }
        }
        val row = LinearLayout(this).also { root.addView(it, LinearLayout.LayoutParams(-1, dp(MagnifierLayout.CONTROL_DP))) }
        zoomLabel = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }.also { row.addView(it, LinearLayout.LayoutParams(dp(56), -1)) }
        zoomSlider = SeekBar(this).apply {
            max = 400; progress = ((viewport.scale - 1f) * 100).roundToInt(); keyProgressIncrement = 10
            contentDescription = "放大倍数，范围1到5倍，当前2倍"
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(110, 165, 155))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 224, 138))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!uiState.capturing || closing || !fromUser) return
                    viewport.setScale(1f + progress / 100f)
                    applyViewportTransform()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { panPointer = -1 }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { }
            })
        }.also { row.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
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
        handle = CompatUi.icon(this,LineIcon.MOVE,"拖动以移动取景框；松手贴边可查看屏幕边缘") { }
        resizeHandle = CompatUi.icon(this,LineIcon.RESIZE,"拖动箭头，自由调整取景框宽度和高度") { }
        handleParams = params(geometry.moveWidth, geometry.grip, true).apply { title = "看懂取景拖动柄" }
        resizeParams = params(geometry.grip, geometry.grip, true).apply { title = "看懂取景缩放角柄" }
        handle!!.setOnTouchListener { view, event ->
            view.isPressed=event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount==1
            onGripTouch(GestureMode.MOVE,event)
        }
        resizeHandle!!.setOnTouchListener { view, event ->
            view.isPressed=event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount==1
            onGripTouch(GestureMode.RESIZE,event)
        }
        wm.addView(handle, handleParams)
        wm.addView(resizeHandle, resizeParams)
        renderGeometry()
    }
    private fun onGripTouch(mode: GestureMode, event: MotionEvent): Boolean {
        if(closing || !uiState.capturing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idle = controls ?: return true
                if (gesture != null || event.pointerCount != 1) return true
                streamCancelled = false
                panPointer = -1
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
        val nextSource = Rect(crop.left, crop.top, crop.right, crop.bottom)
        if (source != nextSource) viewport.selectSource(crop.width, crop.height)
        source = nextSource
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
        resizeHandle?.corner = side ?: GripSide(true,true)
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
    private fun onImageTouch(event: MotionEvent): Boolean {
        if(closing || !uiState.capturing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panPointer = if (gesture == null && latest != null && event.pointerCount == 1)
                    event.getPointerId(0) else -1
                panLastX = event.rawX; panLastY = event.rawY
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> panPointer = -1
            MotionEvent.ACTION_MOVE -> {
                if (panPointer != -1 && event.pointerCount == 1 && event.getPointerId(0) == panPointer) {
                    viewport.dragBy(event.rawX - panLastX, event.rawY - panLastY)
                    panLastX = event.rawX; panLastY = event.rawY
                    applyViewportTransform()
                } else panPointer = -1
            }
            MotionEvent.ACTION_UP -> { panPointer = -1; imageView?.performClick() }
        }
        return true // Only scroll the local image; never forward a gesture to the underlying app.
    }
    private fun applyViewportTransform() {
        val view = imageView ?: return
        if (closing || !uiState.capturing || view.width <= 0 || view.height <= 0) return
        viewport.setViewport(view.width - view.paddingLeft - view.paddingRight,
            view.height - view.paddingTop - view.paddingBottom)
        view.imageMatrix = Matrix().apply {
            setScale(viewport.scale, viewport.scale)
            postTranslate(viewport.translateX, viewport.translateY)
        }
        updateStatus()
    }
    private fun updateStatus() {
        val active = gesture?.result
        val label = String.format(java.util.Locale.ROOT, "%.2f×", viewport.scale)
        message?.text = when {
            !placementAvailable -> "空间不足，请移动取景框"
            active != null && (active.snapX != 0 || active.snapY != 0) -> "松手贴边"
            !frameSeen -> "等待画面"
            else -> if (viewport.canPan) "可滑动查看" else "正在放大"
        }
        zoomLabel?.text = label
        zoomSlider?.contentDescription = "放大倍数，范围1到5倍，当前${label}"
        if (Build.VERSION.SDK_INT >= 30) zoomSlider?.stateDescription = label
    }
    private fun clearFrame() { imageView?.setImageDrawable(null); latest?.recycle(); latest = null }
    private fun capture(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        try {
            if (closing || !running || !uiState.capturing || !surfaceAttached) return
            frameSeen = true
            val now = SystemClock.elapsedRealtime()
            // After clearing a crop, the next compositor frame may be the last on a static page.
            // Always show that first refresh; throttle only while an image is already visible.
            if (latest != null && now-lastFrameAt < 120) return
            lastFrameAt = now
            if (sourceOverlapsProtectedControls()) {
                clearFrame(); message?.text = "空间不足，请移动取景框"; return
            }
            copyAttempts++
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
            applyViewportTransform()
        } catch (_: RuntimeException) {
            endSession()
        } finally { image.close() }
    }
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        // Android's existing adb/service diagnostics, debug builds only. No text or image content.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!::geometry.isInitialized) { writer.println("geometry=unavailable"); return }
        fun box(value: Box?) = value?.let { "${it.left},${it.top},${it.width},${it.height}" } ?: "none"
        fun bounds(p: WindowManager.LayoutParams?) = p?.let { "${it.x},${it.y},${it.width},${it.height}" } ?: "none"
        writer.println("uiState=${uiState.mode} returnTo=${uiState.returnTo} session=$sessionId terminal=$closing surfaceAttached=$surfaceAttached capturing=${uiState.capturing && surfaceAttached && !closing} copyAttempts=$copyAttempts virtualDisplayCreates=$virtualDisplayCreates")
        writer.println("bubble=${if(bubble!=null) bounds(bubbleParams) else "none"} menu=${if(menu!=null) bounds(menuParams) else "none"} menuPage=${menuPage ?: "root"}")
        writer.println("expandedWindows=${listOf(panel,handle,resizeHandle,outline).count { it?.isAttachedToWindow == true }} safeArea=${box(safeArea)}")
        toolButtons.forEach { (label,button) ->
            val location=IntArray(2); button.getLocationOnScreen(location)
            writer.println("tool[$label]=${location[0]},${location[1]},${button.width},${button.height}")
        }
        fun menuActions(view: View) {
            if(view is Button) {
                val location=IntArray(2); view.getLocationOnScreen(location)
                writer.println("menuAction[${view.text}]=${location[0]},${location[1]},${view.width},${view.height} enabled=${view.isEnabled}")
            }
            if(view is ViewGroup) for(index in 0 until view.childCount) menuActions(view.getChildAt(index))
        }
        menu?.let(::menuActions)
        val view = imageView
        writer.println("crop=${box(crop)} panelBottom=$panelAtBottom available=$placementAvailable")
        writer.println("move=${box(controls?.move)} resize=${box(controls?.resize)}")
        writer.println("gesture=${gesture?.mode ?: "none"} renderedFrames=$renderedFrames bitmap=${latest?.width ?: 0},${latest?.height ?: 0}")
        writer.println("viewport=${view?.width ?: 0},${view?.height ?: 0} scale=${viewport.scale}")
        val matrix = FloatArray(9)
        view?.imageMatrix?.getValues(matrix)
        val drawable = view?.drawable
        writer.println("drawable=${drawable?.intrinsicWidth ?: 0},${drawable?.intrinsicHeight ?: 0} " +
            "matrixScale=${matrix[Matrix.MSCALE_X]},${matrix[Matrix.MSCALE_Y]}")
        writer.println("pan=${viewport.panX},${viewport.panY} maxPan=${viewport.maxPanX},${viewport.maxPanY} " +
            "translation=${matrix[Matrix.MTRANS_X]},${matrix[Matrix.MTRANS_Y]} panPointer=$panPointer")
        zoomSlider?.let {
            val location = IntArray(2); it.getLocationOnScreen(location)
            writer.println("slider=${location[0]},${location[1]},${it.width},${it.height} progress=${it.progress}")
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); endSession()
    }
    private fun endSession() {
        if(closing) return
        closing=true; uiState.end(); running=false; surfaceAttached=false; menuPage=null; generation++
        publish(); main.removeCallbacksAndMessages(null); cancelGestures()
        fun safely(block: () -> Unit) { try { block() } catch(_: RuntimeException) { } }
        safely { reader?.setOnImageAvailableListener(null,null) }
        safely { display?.surface=null }; safely { clearFrame() }
        listOf(panel,handle,resizeHandle,outline,bubble,menu).forEach { safely { remove(it) } }
        panel=null; handle=null; resizeHandle=null; outline=null; bubble=null; menu=null; imageView=null; zoomSlider=null; message=null; zoomLabel=null; toolButtons.clear()
        safely { display?.release() }; display=null
        safely { drain() }; safely { reader?.close() }; reader=null
        safely { projection?.unregisterCallback(projectionCallback) }; safely { projection?.stop() }; projection=null
        if(receiverRegistered) { safely { unregisterReceiver(screenOff) }; receiverRegistered=false }
        if(displayListenerRegistered) { safely { displayManager.unregisterDisplayListener(displayListener) }; displayListenerRegistered=false }
        safely { stopForeground(STOP_FOREGROUND_REMOVE) }; stopSelf()
    }
    override fun onDestroy() { endSession(); super.onDestroy() }
    private fun dp(n: Int) = (n*resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}
