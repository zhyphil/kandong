package com.kandong.compat

import android.app.*
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var consent: CheckBox
    private lateinit var consentCard: LinearLayout
    private lateinit var primary: Button
    private lateinit var end: Button
    private var snapshot = SessionSnapshot()
    private var dialog: Dialog? = null
    private var requesting = false
    private val listener: (SessionSnapshot) -> Unit = { next ->
        val ended = snapshot.id != null && next.id == null
        snapshot=next
        if(ended) consent.isChecked=false
        refresh()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        window.statusBarColor=CompatUi.background; window.navigationBarColor=CompatUi.background
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(24),dp(24),dp(32)) }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(IconView(this,LineIcon.MAGNIFY,CompatUi.teal),LinearLayout.LayoutParams(dp(40),dp(48)))
        header.addView(CompatUi.text(this,"看懂",28f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(CompatUi.icon(this,LineIcon.MENU,"打开菜单") {
            if(snapshot.id != null) command(ProjectionMagnifierService.ACTION_MENU) else openMenu(null)
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        body.addView(header)
        body.addView(CompatUi.text(this,"看清一小块，轻松一点",24f))
        body.addView(CompatUi.text(this,"放大想看的地方，原来的页面保持原样。",18f,CompatUi.muted))
        status=CompatUi.text(this,"",16f,CompatUi.muted).also(body::addView)
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(16)); background=CompatUi.shape(this@MainActivity,android.graphics.Color.WHITE) }
        card.addView(CompatUi.text(this,"只在您的手机内放大",20f))
        card.addView(CompatUi.text(this,CompatUi.disclosure,18f))
        consent=CheckBox(this).apply {
            text="我已了解，同意本次屏幕共享放大"; textSize=18f; setTextColor(CompatUi.ink)
            minimumHeight=dp(56); buttonTintList=android.content.res.ColorStateList.valueOf(CompatUi.teal)
            isSaveEnabled=false; isChecked=false
            setOnCheckedChangeListener { _, checked -> if(!checked && snapshot.id!=null) command(ProjectionMagnifierService.ACTION_STOP) }
        }.also(card::addView)
        consentCard=card
        body.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16); bottomMargin=dp(24) })
        primary=CompatUi.button(this,"打开放大镜",true) {
            when {
                snapshot.id!=null -> command(if(snapshot.mode==SessionMode.EXPANDED) ProjectionMagnifierService.ACTION_COLLAPSE else ProjectionMagnifierService.ACTION_RESUME)
                !Settings.canDrawOverlays(this) -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
                !consent.isChecked -> { status.text="请先阅读说明，并勾选本次同意。"; consent.requestFocus() }
                !requesting -> {
                    requesting=true
                    val manager=getSystemService(MediaProjectionManager::class.java)
                    val request=if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
                    @Suppress("DEPRECATION")
                    startActivityForResult(request,91)
                }
            }
        }.also { body.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        end=CompatUi.button(this,"关闭放大镜") { command(ProjectionMagnifierService.ACTION_STOP) }
            .also { body.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) }) }
        body.addView(CompatUi.button(this,"使用帮助") { openMenu("使用帮助") },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(24) })
        val scroll=ScrollView(this).apply { setBackgroundColor(CompatUi.background); isFillViewport=true; addView(body) }
        scroll.setOnApplyWindowInsetsListener { view,insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val safe=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            } else { @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }
            insets
        }
        setContentView(scroll); refresh()
    }
    private fun refresh() {
        if(!::primary.isInitialized) return
        val active=snapshot.id!=null
        primary.text=when { active && snapshot.mode==SessionMode.EXPANDED -> "收起放大镜"; active -> "恢复放大镜"; !Settings.canDrawOverlays(this) -> "允许悬浮窗"; else -> "打开放大镜" }
        consentCard.visibility=if(active) View.GONE else View.VISIBLE
        end.visibility=if(active) View.VISIBLE else View.GONE
        status.text=when { active && snapshot.mode==SessionMode.EXPANDED -> "正在放大 · 可切换到想看的页面"; active -> "画面采集已暂停 · 屏幕共享会话仍保留"; !Settings.canDrawOverlays(this) -> "首次使用，请允许显示悬浮窗后返回。"; else -> "悬浮窗已允许 · 每次开始需您确认屏幕共享" }
    }
    private fun command(action: String) {
        val id=snapshot.id ?: return
        startService(Intent(this,ProjectionMagnifierService::class.java).setAction(action).putExtra(ProjectionMagnifierService.SESSION,id))
    }
    private fun openMenu(page: String?) {
        if(snapshot.id!=null) { command(ProjectionMagnifierService.ACTION_MENU); return }
        dialog?.dismiss()
        dialog=Dialog(this,android.R.style.Theme_Material_Light_NoActionBar).apply {
            val content=FrameLayout(this@MainActivity).apply {
                setBackgroundColor(CompatUi.background)
                addView(CompatUi.menu(this@MainActivity,page,{dismiss()},{openMenu(it)},null),
                    FrameLayout.LayoutParams(-1,-1))
                setOnApplyWindowInsetsListener { view,insets ->
                    if(Build.VERSION.SDK_INT >= 30) {
                        val safe=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                        view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
                    } else {
                        @Suppress("DEPRECATION") val bars=insets.stableInsets
                        val cutout=insets.displayCutout
                        view.setPadding(maxOf(bars.left,cutout?.safeInsetLeft ?: 0),maxOf(bars.top,cutout?.safeInsetTop ?: 0),
                            maxOf(bars.right,cutout?.safeInsetRight ?: 0),maxOf(bars.bottom,cutout?.safeInsetBottom ?: 0))
                    }
                    insets
                }
            }
            setContentView(content)
            setCanceledOnTouchOutside(false)
            window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(CompatUi.background))
                attributes=attributes.apply {
                    title="看懂全屏菜单"
                    layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if(Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                @Suppress("DEPRECATION")
                statusBarColor=CompatUi.background
                @Suppress("DEPRECATION")
                navigationBarColor=CompatUi.background
            }
            setOnKeyListener { _, key, event ->
                if(Build.VERSION.SDK_INT < 33 && page != null && key == KeyEvent.KEYCODE_BACK) {
                    if(event.action == KeyEvent.ACTION_UP && isShowing && dialog === this) openMenu(null)
                    true
                } else false
            }
            show(); content.requestApplyInsets()
            if(Build.VERSION.SDK_INT >= 33 && page != null) {
                val dispatcher=onBackInvokedDispatcher
                val callback=android.window.OnBackInvokedCallback {
                    if(isShowing && dialog === this) openMenu(null)
                }
                dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
                setOnDismissListener { dispatcher.unregisterOnBackInvokedCallback(callback) }
            }
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
        }
    }
    override fun onStart() { super.onStart(); SessionBridge.add(listener) }
    override fun onResume() { super.onResume(); refresh() }
    override fun onStop() { SessionBridge.remove(listener); dialog?.dismiss(); dialog=null; super.onStop() }
    @Deprecated("Dependency-free platform consent result")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=91) return
        val fresh=requesting; requesting=false
        if(!fresh || resultCode!=RESULT_OK || data==null) { status.text="未获得屏幕共享授权，没有开始。"; return }
        if(!consent.isChecked || !Settings.canDrawOverlays(this) || SessionBridge.snapshot.id!=null) return
        val service = Intent(this,ProjectionMagnifierService::class.java).setAction(ProjectionMagnifierService.ACTION_START)
            .putExtra("resultCode",resultCode).putExtra("projectionConsent",data)
        if (Build.VERSION.SDK_INT == 29) {
            val insets = window.decorView.rootWindowInsets
            if (insets == null || isInMultiWindowMode) {
                status.text="请返回全屏看懂后重新开启。"; return
            }
            @Suppress("DEPRECATION") val bars = insets.stableInsets
            val cutout = insets.displayCutout
            service.putExtra("legacySafeInsets", intArrayOf(
                maxOf(bars.left, cutout?.safeInsetLeft ?: 0), maxOf(bars.top, cutout?.safeInsetTop ?: 0),
                maxOf(bars.right, cutout?.safeInsetRight ?: 0), maxOf(bars.bottom, cutout?.safeInsetBottom ?: 0)))
            @Suppress("DEPRECATION") val rotation = windowManager.defaultDisplay.rotation
            service.putExtra("legacyRotation", rotation)
        }
        startForegroundService(service)
    }
    private fun dp(value: Int)=CompatUi.dp(this,value)
}
