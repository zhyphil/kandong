package com.kandong.compat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.*
import android.widget.*
import kotlin.math.roundToInt

internal enum class LineIcon { MAGNIFY, MENU, COLLAPSE, END, MOVE, RESIZE, CLOSE }
internal class IconView(context: Context, private val icon: LineIcon, private val ink: Int = Color.WHITE) : View(context) {
    var corner = GripSide(true, true)
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = CompatUi.dp(context, 26).toFloat()
        canvas.save(); canvas.translate((width - size) / 2, (height - size) / 2); canvas.scale(size / 24, size / 24)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = canvas.drawLine(x,y,xx,yy,paint)
        when (icon) {
            LineIcon.MAGNIFY -> { canvas.drawCircle(10f,10f,6.5f,paint); line(15f,15f,21f,21f); line(7f,10f,13f,10f); line(10f,7f,10f,13f) }
            LineIcon.MENU -> { line(4f,6f,20f,6f); line(4f,12f,20f,12f); line(4f,18f,20f,18f) }
            LineIcon.COLLAPSE -> { canvas.drawRoundRect(3f,3f,21f,21f,4f,4f,paint); line(8f,12f,16f,12f) }
            LineIcon.END -> canvas.drawRoundRect(5f,5f,19f,19f,2f,2f,paint)
            LineIcon.CLOSE -> { line(5f,5f,19f,19f); line(19f,5f,5f,19f) }
            LineIcon.MOVE -> {
                line(3f,12f,21f,12f); line(12f,3f,12f,21f)
                line(3f,12f,6f,9f); line(3f,12f,6f,15f); line(21f,12f,18f,9f); line(21f,12f,18f,15f)
                line(12f,3f,9f,6f); line(12f,3f,15f,6f); line(12f,21f,9f,18f); line(12f,21f,15f,18f)
            }
            LineIcon.RESIZE -> {
                canvas.scale(if (corner.right) 1f else -1f, if (corner.below) 1f else -1f,12f,12f)
                line(5f,5f,19f,19f); line(19f,11f,19f,19f); line(11f,19f,19f,19f)
                line(5f,5f,5f,10f); line(5f,5f,10f,5f)
            }
        }
        canvas.restore()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
internal object CompatUi {
    val background = Color.rgb(244,247,244)
    val ink = Color.rgb(18,51,47)
    val muted = Color.rgb(82,103,95)
    val teal = Color.rgb(18,105,95)
    val soft = Color.rgb(228,240,234)
    const val disclosure = "展开时会临时读取整个屏幕，只在手机内放大选区，不保存、不联网、不上传。收起暂停画面处理；点“结束共享”才会关闭系统共享。"
    const val help = "1. 允许悬浮窗，勾选本次同意，再按提示允许屏幕共享。\n\n2. 拖动十字手柄移动取景框；拖动箭头手柄分别调节宽和高。\n\n3. 下方滑杆单独调节1到5倍，每次新使用默认2倍。放大后可在画面内滑动查看。镜面会自动上下避让。\n\n4. 到达屏幕边缘时，看到“松手贴边”后松开。取景框内仍可操作原来的页面。\n\n5. 点收起可暂时放到屏幕边缘；轻点圆球恢复，拖动可换位置，长按打开菜单。\n\n6. 点方形结束按钮或菜单中的“结束共享”可关闭。锁屏或转动屏幕后需要重新开始。"
    const val privacy = "屏幕共享由您每次亲自授权。系统会提供整屏缓冲区，看懂只在手机内复制选区用于放大，不录制、不保存、不上传，也不采集页面文字。\n\n收起或打开菜单时暂停画面采集并清除已有画面，但系统屏幕共享会话仍保留。结束共享后释放本次投屏资源。\n\n查看密码、银行等私密页面前，请先结束共享。受保护的画面可能显示为空白。\n\n目前无需登录。区域翻译、账号和订阅尚未提供；屏幕共享授权不代表同意文字识别或AI处理。"
    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    fun shape(c: Context, color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(c,radius).toFloat() }
    fun ripple(c: Context, color: Int, radius: Int = 16) = RippleDrawable(ColorStateList.valueOf(0x337CA99A),shape(c,color,radius),shape(c,Color.WHITE,radius))
    fun text(c: Context, value: String, size: Float = 18f, color: Int = ink) = TextView(c).apply { text = value; textSize = size; setTextColor(color); setPadding(0,dp(c,8),0,dp(c,8)) }
    fun icon(c: Context, icon: LineIcon, label: String, action: () -> Unit) = IconView(c,icon).apply {
        contentDescription = label; tooltipText = label; isFocusable = true; background = ripple(c,teal)
        minimumWidth = dp(c,48); minimumHeight = dp(c,48); setOnClickListener { action() }
    }
    fun button(c: Context, title: String, primary: Boolean = false, action: () -> Unit) = Button(c).apply {
        text = title; textSize = 18f; isAllCaps = false; setTextColor(if(primary) Color.WHITE else ink)
        minHeight = dp(c,56); minimumHeight = dp(c,56); background = ripple(c,if(primary) teal else soft)
        setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10)); setOnClickListener { action() }
    }
    fun menu(c: Context, page: String?, close: () -> Unit, navigate: (String?) -> Unit, end: (() -> Unit)?): LinearLayout {
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c,20),dp(c,16),dp(c,20),dp(c,20)); background = shape(c,CompatUi.background) }
        body.addView(text(c,page ?: "看懂",24f))
        fun row(title: String, action: () -> Unit) { body.addView(button(c,title,action=action),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(c,8) }) }
        if(page == null) {
            row("使用帮助") { navigate("使用帮助") }; row("隐私说明") { navigate("隐私说明") }
            for(title in listOf("区域翻译设置","账号与订阅")) body.addView(text(c,"$title · 规划中，暂不可用",16f,muted).apply { isEnabled=false; minimumHeight=dp(c,48) })
            body.addView(text(c,"目前无需登录",14f,muted))
        } else { body.addView(text(c,if(page=="使用帮助") help else privacy)); row("返回菜单") { navigate(null) } }
        return LinearLayout(c).apply {
            orientation=LinearLayout.VERTICAL; background=shape(c,CompatUi.background)
            addView(ScrollView(c).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
            val footer=LinearLayout(c).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(c,20),dp(c,8),dp(c,20),dp(c,16))
                addView(button(c,"关闭菜单",action=close),LinearLayout.LayoutParams(-1,-2))
                if(end!=null) addView(button(c,"结束共享",action=end),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(c,8) })
            }
            addView(footer,LinearLayout.LayoutParams(-1,-2))
        }
    }
}
