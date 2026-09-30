package com.kandong.modelprobe

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import com.kandong.ocrlab.context.*
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** The UI is shared; default mode performs actual OCR on fixed authenticated PNG pixels only. */
class RegionVisualLabSurface(private val activity: Activity, fixtureResources: Context) : RegionVisualLabActivity.Surface {
    companion object { const val PROTOCOL_VERSION = 2 }
    private var realBackend: RegionVisualOcrBackend? = RegionVisualOcrBackend(activity.applicationContext,fixtureResources.assets,recorded=true)
    internal var session = RegionVisualSession(checkNotNull(realBackend)) { SystemClock.elapsedRealtime() }
        private set
    private val notice: String get() = if(session.synthetic) "合成手势回归 · 不运行 OCR、不翻译、不读取屏幕"
        else "固定 PNG · 真实整页 OCR · 不读取屏幕\n$replyNotice"
    private val replyNotice:String get()=if(realBackend?.recorded==true) "已录制 DeepL 响应 · 本次未联网\n识别上下文含未核对内容；仅限开发实验" else "本地绑定演示，非真实翻译"
    private val translateLabel:String get()=if(realBackend?.recorded==true) "译文回放" else "翻译演示"
    private val showLabel: String get() = if(session.synthetic) "展示样例" else "整页 OCR"
    private val handler=Handler(Looper.getMainLooper())
    private var active=false
    private var disposed=false
    private val ink=Color.rgb(30,41,53)
    private val muted=Color.rgb(87,103,117)
    private val amber=Color.rgb(151,88,8)
    private val red=Color.rgb(201,50,56)
    private val root=FrameLayout(activity).apply { tag="lab_root"; setBackgroundColor(Color.rgb(241,244,247)) }
    private val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; tag="lab_content" }
    private val menu=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; tag="lab_menu"; setBackgroundColor(Color.WHITE); setPadding(dp(20),dp(16),dp(20),dp(16)) }
    private val collapsed=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; gravity=android.view.Gravity.CENTER; tag="lab_collapsed" }
    private val status=label("",13f,"lab_status")
    private val translationStatus=label("",14f,"lab_translation_status")
    private val demoControls=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; tag="lab_demo_controls" }
    private var demoRender: ContextRender?=null
    private var demoSourceMap: Map<String,FullPageOcrContract.Candidate> = emptyMap()
    private val zoom=label("",14f,"lab_zoom")
    private val slider=SeekBar(activity).apply { max=400; progress=100; tag="lab_slider"; contentDescription="放大倍率，1到5倍"; minimumHeight=dp(48) }
    internal val source=SourceView()
    internal val mirror=MirrorView()
    private val cards=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; tag="lab_cards" }
    private var cardKey=""
    private var realScroll: ScrollView?=null
    private var realChrome: LinearLayout?=null
    private var bodyView: LinearLayout?=null
    private val ticker=object:Runnable {
        override fun run() {
            if (!active || disposed) return
            // Always consult the current session; no captured old deadline can revoke a new run.
            session.refresh(); update(); handler.postDelayed(this,200)
        }
    }
    init {
        root.addView(content,FrameLayout.LayoutParams(-1,-1))
        content.setPadding(dp(12),dp(4),dp(12),dp(8))
        content.addView(label("选区实验",22f,"lab_title"))
        content.addView(label(notice,12f,"lab_notice"))
        val actions=LinearLayout(activity)
        actions.addView(button(showLabel,"lab_show") { session.showSample(); update() },LinearLayout.LayoutParams(0,dp(48),1.5f))
        actions.addView(button("换页","lab_page") { session.nextPage(); source.relayoutPage(); update() },LinearLayout.LayoutParams(0,dp(48),1f))
        actions.addView(button("收起","lab_collapse") { session.collapse(); update() },LinearLayout.LayoutParams(0,dp(48),1f))
        actions.addView(button("菜单","lab_open_menu") { session.menu(); update() },LinearLayout.LayoutParams(0,dp(48),1f))
        content.addView(actions)
        val demoRow=LinearLayout(activity)
        demoRow.addView(button(translateLabel,"lab_translate") {
            session.showSample(RegionVisualSession.StartMode.BINDING_DEMO); update()
        },LinearLayout.LayoutParams(dp(120),-2))
        demoRow.addView(button("取消","lab_cancel") { session.cancel(); update() },LinearLayout.LayoutParams(dp(72),-2))
        demoRow.addView(button("原文对照","lab_original") {
            realBackend?.translation?.choose(RegionVisualTranslationController.Display.ORIGINAL,SystemClock.elapsedRealtime()); update()
        },LinearLayout.LayoutParams(dp(88),-2))
        demoRow.addView(button(if(realBackend?.recorded==true) "录制译文" else "演示结果","lab_demo") {
            realBackend?.translation?.choose(RegionVisualTranslationController.Display.DEMO,SystemClock.elapsedRealtime()); update()
        },LinearLayout.LayoutParams(dp(88),-2))
        demoControls.addView(HorizontalScrollView(activity).apply { addView(demoRow); isFillViewport=true })
        demoControls.addView(translationStatus)
        content.addView(demoControls)
        val zoomRow=LinearLayout(activity).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        zoomRow.addView(zoom,LinearLayout.LayoutParams(dp(100),dp(48)))
        zoomRow.addView(slider,LinearLayout.LayoutParams(0,dp(48),1f)); content.addView(zoomRow)
        content.addView(status)
        val body=LinearLayout(activity)
        bodyView=body
        val landscape=activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp
        body.orientation=if(landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val sourcePane=pane("固定整页 · 拖红框，右上角改宽高",source)
        val mirrorPane=pane("原图放大与候选位置 · 双指缩放，单指平移",mirror)
        val scroll=ScrollView(activity).apply { tag="lab_candidates_scroll"; addView(cards); isFillViewport=false }
        val cardPane=pane("选区候选优先 · 整页上下文保留",scroll)
        if (landscape) {
            body.addView(sourcePane,LinearLayout.LayoutParams(0,-1,1f))
            val right=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            right.addView(mirrorPane,LinearLayout.LayoutParams(-1,0,1f))
            right.addView(cardPane,LinearLayout.LayoutParams(-1,0,1f))
            body.addView(right,LinearLayout.LayoutParams(0,-1,1f))
        } else {
            body.addView(sourcePane,LinearLayout.LayoutParams(-1,0,1.25f))
            body.addView(mirrorPane,LinearLayout.LayoutParams(-1,0,.70f))
            body.addView(cardPane,LinearLayout.LayoutParams(-1,0,.90f))
        }
        content.addView(body,LinearLayout.LayoutParams(-1,dp(if(landscape) 340 else 480)))
        root.removeView(content)
        realScroll=ScrollView(activity).apply { tag="lab_page_scroll"; isFillViewport=true; addView(content) }
        realChrome=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            addView(label(replyNotice,14f,"lab_binding_notice"))
            addView(realScroll,LinearLayout.LayoutParams(-1,0,1f))
        }
        root.addView(realChrome,FrameLayout.LayoutParams(-1,-1))
        menu.addView(label("实验菜单",24f,"lab_menu_title"))
        menu.addView(label(notice,14f,"lab_menu_notice"))
        menu.addView(label(helpText(),17f,"lab_help"),LinearLayout.LayoutParams(-1,0,1f))
        menu.addView(button("停止展示","lab_stop") { session.stop(); update() },LinearLayout.LayoutParams(-1,dp(48)))
        menu.addView(button("返回实验","lab_close_menu") { session.closeMenu(); update() },LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(menu,FrameLayout.LayoutParams(-1,-1))
        collapsed.addView(label(notice,14f,"lab_collapsed_notice"))
        collapsed.addView(label("已收起，样例结果已清除",20f,"lab_collapsed_status"))
        collapsed.addView(button("恢复选区","lab_restore") { session.restore(); update() },LinearLayout.LayoutParams(-1,dp(56)))
        collapsed.addView(button("菜单","lab_collapsed_menu") { session.menu(); update() },LinearLayout.LayoutParams(-1,dp(56)))
        root.addView(collapsed,FrameLayout.LayoutParams(-1,-1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                root.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                root.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            }
            insets
        }
        root.requestApplyInsets()
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar:SeekBar, progress:Int, fromUser:Boolean) { if(fromUser) { session.scale(1.0+progress/100.0); update() } }
            override fun onStartTrackingTouch(bar:SeekBar) {}
            override fun onStopTrackingTouch(bar:SeekBar) {}
        })
        update()
    }
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).roundToInt()
    private fun label(text:String,size:Float,tagName:String)=TextView(activity).apply {
        textSize=size; setTextColor(ink); this.text=text; tag=tagName; setPadding(dp(4),dp(3),dp(4),dp(3))
    }
    private fun button(text:String,tagName:String,action:()->Unit)=Button(activity).apply {
        this.text=text; tag=tagName; textSize=14f; isAllCaps=false; minWidth=0; minimumWidth=dp(48); minimumHeight=dp(48)
        setPadding(dp(4),0,dp(4),0); setOnClickListener { action() }
    }
    private fun pane(title:String,child:View)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(3),dp(2),dp(3),dp(2))
        addView(label(title,12f,"${child.tag}_heading"))
        addView(child,LinearLayout.LayoutParams(-1,0,1f))
    }
    override fun getView():View=root
    override fun onForeground(active:Boolean) {
        if(disposed) return
        this.active=active; handler.removeCallbacks(ticker); session.foreground(active); update()
        if(active) handler.post(ticker)
    }
    override fun dispose() {
        if(disposed) return
        active=false; handler.removeCallbacksAndMessages(null); session.dispose(); update(); disposed=true
        realBackend=null
    }
    internal fun isForeground()=active && !disposed && activity.hasWindowFocus()
    /** Local test API only. Never read from an Intent or a setting, and never a real-mode fallback. */
    internal fun selectSyntheticGestureMode() {
        check(!disposed)
        session.dispose(); realBackend=null
        session=RegionVisualSession { SystemClock.elapsedRealtime() }
        session.foreground(active)
        cardKey=""
        realScroll?.removeView(content); realScroll=null
        realChrome?.let { root.removeView(it) }; realChrome=null
        root.addView(content,0,FrameLayout.LayoutParams(-1,-1))
        bodyView?.layoutParams=LinearLayout.LayoutParams(-1,0,1f)
        demoControls.visibility=View.GONE
        for(tag in listOf("lab_notice","lab_menu_notice","lab_collapsed_notice"))
            root.findViewWithTag<TextView>(tag).text=notice
        root.findViewWithTag<Button>("lab_show").text=showLabel
        root.findViewWithTag<TextView>("lab_help").text=helpText()
        source.relayoutPage(); update()
    }
    internal fun realResult()=realBackend?.result()
    internal fun realError()=realBackend?.error()
    internal fun translationState(): RegionVisualTranslationController.State? {
        session.refresh(); return realBackend?.translation?.state(SystemClock.elapsedRealtime())
    }
    internal fun translationRender(): ContextRender? { update(); return demoRender }
    internal fun nativeBusy()=realBackend?.busy() ?: false
    /** Local instrumentation seam; no external Intent/config can inject a runner or callback. */
    internal fun installRealBackendForProbe(backend: RegionVisualOcrBackend) {
        check(!disposed && !session.synthetic && session.state().loads==0)
        session.dispose(); realBackend=backend
        session=RegionVisualSession(backend) { SystemClock.elapsedRealtime() }
        session.foreground(active); source.relayoutPage(); update()
    }
    private fun helpText()="仅操作本页固定素材与红框。\n从取得页面起有效 60 秒；离开、换页、收起或菜单会清除结果。\n恢复后须再次点击“$showLabel”。取消会立即撤下显示，后台 JNI 返回并清理后才能再次开始。"
    private fun update() {
        val s=session.state()
        menu.visibility=if(s.mode==RegionVisualSession.Mode.MENU) View.VISIBLE else View.GONE
        collapsed.visibility=if(s.mode==RegionVisualSession.Mode.COLLAPSED) View.VISIBLE else View.GONE
        content.visibility=if(s.mode==RegionVisualSession.Mode.MENU || s.mode==RegionVisualSession.Mode.COLLAPSED) View.GONE else View.VISIBLE
        realChrome?.visibility=content.visibility
        status.text="${session.pageLabel} · ${s.status} · 请求 ${s.loads} 次"
        zoom.text=String.format(Locale.ROOT,"%.2f 倍\n相对整页预览",s.scale)
        slider.progress=((s.scale-1)*100).roundToInt()
        demoControls.visibility=if(session.synthetic) View.GONE else View.VISIBLE
        root.findViewWithTag<TextView>("lab_translate").text=translateLabel
        root.findViewWithTag<TextView>("lab_demo").text=if(realBackend?.recorded==true) "录制译文" else "演示结果"
        for(tag in listOf("lab_notice","lab_menu_notice","lab_collapsed_notice"))
            root.findViewWithTag<TextView>(tag)?.text=notice
        root.findViewWithTag<TextView>("lab_binding_notice")?.text=replyNotice
        val backend=realBackend
        val now=SystemClock.elapsedRealtime()
        val presentation=backend?.translation?.state(now)
        val isDemo=backend?.startMode==RegionVisualSession.StartMode.BINDING_DEMO
        translationStatus.text=when(presentation?.phase) {
            RegionVisualTranslationController.Phase.READING -> "正在读取固定整页 · 可取消"
            RegionVisualTranslationController.Phase.WAITING -> if(backend.recorded) "正在核对整页与录制输入 · 可取消" else "等待本地绑定演示回复 · 可取消"
            RegionVisualTranslationController.Phase.READY -> if(backend.recorded && FullPageVisualOcrRunner.PAGES[s.pageIndex].language.startsWith("zh"))
                "中文原文就绪 · 不请求翻译" else if(backend.recorded) "录制结果就绪 · 本次未联网 · 可对照原文" else "绑定展示就绪 · ${if(presentation.display==RegionVisualTranslationController.Display.ORIGINAL) "原文" else "演示"} · 非真实翻译"
            RegionVisualTranslationController.Phase.ERROR -> "结果不可用或整页不匹配 · 请再次点击$translateLabel"
            RegionVisualTranslationController.Phase.EXPIRED -> "原始页面已过期 · 请再次点击$translateLabel"
            RegionVisualTranslationController.Phase.CANCELLED -> "已取消 · 恢复不会自动读取"
            else -> "点击$translateLabel 才读取固定整页"
        }
        demoRender=if(isDemo && s.frame!=null) backend?.translation?.render(s.roi,session.mirrorTransform(),now) else null
        demoSourceMap=if(isDemo && s.frame!=null) backend?.translation?.sourceMap(now) ?: emptyMap() else emptyMap()
        source.invalidate(); mirror.invalidate()
        val f=s.frame
        val key="${if(isDemo) presentation else null}:" + if(f==null) "none:${s.status}" else "${f.page.identity?.sourceBatch}:${f.selectedIds}:${f.contextIds}:${f.visible.map { it.candidateId }}"
        if(key==cardKey) return
        cardKey=key; cards.removeAllViews()
        if(f==null) { cards.addView(label("请点击“$showLabel”",15f,"lab_no_result")); return }
        if(f.page.rawCandidates.isEmpty()) { cards.addView(label(if(session.synthetic) "有效空白样例 · 没有文字候选" else "整页 OCR 完成 · 没有文字候选",16f,"lab_blank")); return }
        if(isDemo && demoRender!=null) {
            val ordinals=f.page.rawCandidates.withIndex().associate { it.value.provenance.id to it.index+1 }
            val rendered=checkNotNull(demoRender)
            if(rendered.cards.isEmpty()) cards.addView(label("选区内没有候选 · 整页上下文仍保留",16f,"lab_empty_region"))
            rendered.cards.forEach { card ->
                val candidate=demoSourceMap[card.targetId] ?: return@forEach
                val ordinal=ordinals[candidate.provenance.id] ?: return@forEach
                val raw=card.sourceText.ifEmpty { "〔空字符串候选〕" }
                val reason=when(card.reason) {
                    "CHECK_UNVERIFIED" -> "译文尚未通过核对，请先看原文"
                    "AWAITING_HANDCRAFTED_FIXTURE", "NO_RECORDED_RESULT", null -> "尚无对应结果"
                    else -> if(card.reason?.contains("CONFLICT")==true) "候选冲突，保留原文" else "候选信息不完整，保留原文"
                }
                val text=when {
                    card.reason=="ALREADY_CHINESE" -> "$ordinal  中文原文保留：$raw"
                    presentation?.display==RegionVisualTranslationController.Display.ORIGINAL -> "$ordinal  原文：$raw"
                    card.chinese!=null -> "$ordinal  ${card.chinese}\n原文：$raw"
                    presentation?.phase==RegionVisualTranslationController.Phase.WAITING && card.reason in setOf("NO_RECORDED_RESULT","AWAITING_HANDCRAFTED_FIXTURE") -> "$ordinal  等待绑定演示\n原文：$raw"
                    else -> "$ordinal  原文保留：$raw\n$reason"
                }
                cards.addView(label(text,18f,"lab_translation_candidate_$ordinal").apply {
                    setBackgroundColor(Color.WHITE); setPadding(dp(10),dp(8),dp(10),dp(8))
                },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(4) })
            }
            return
        }
        // Presentation priority only; original candidate IDs, numbers and whole-page evidence stay unchanged.
        val groupsById=f.page.groups.flatMap { g -> g.memberIds.map { it to g } }.toMap()
        val ordered=f.page.rawCandidates.withIndex().sortedBy { (_,c) ->
            val id=c.provenance.id
            when {
                id in f.contextIds && groupsById[id]?.text==FullPageOcrAssociation.Text.DIFFERENT_RAW -> 0
                id in f.contextIds -> 1
                else -> 2
            }
        }
        ordered.forEach { (i,c) ->
            val p=c.provenance
            val group=f.page.groups.single { p.id in it.memberIds }
            val conflict=group.text==FullPageOcrAssociation.Text.DIFFERENT_RAW
            val where=when { p.id !in f.selectedIds && p.id in f.contextIds -> "框外关联"
                p.id !in f.selectedIds -> "框外上下文"
                f.visible.none { it.candidateId==p.id } -> "选中 · 镜面外"
                else -> "选中 · 镜面内" }
            val pos="(${p.pageQuad.minOf { it.x }.toInt()}, ${p.pageQuad.minOf { it.y }.toInt()})"
            val raw=if(c.rawText.isEmpty()) "〔空字符串候选〕" else c.rawText
            val text="${i+1}  $raw\n$where · 来源 $pos${if(conflict) " · 冲突，未选择答案" else ""}"
            cards.addView(label(text,16f,"lab_candidate_${p.id.substringAfterLast('/')}").apply {
                setTextColor(if(conflict) amber else ink); setBackgroundColor(if(conflict) Color.rgb(255,246,224) else Color.WHITE)
                setPadding(dp(10),dp(7),dp(10),dp(7))
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(4) })
        }
    }
    private fun viewportChanged() {
        if(disposed || source.factor<=0 || mirror.width<=dp(16) || mirror.height<=dp(16)) return
        session.minimumRegion(dp(48)/source.factor)
        session.viewport((mirror.width-dp(16))/source.factor,(mirror.height-dp(16))/source.factor)
        update()
    }
    /** Real mode uses the same immutable decoded tiles sent to ImageWriter. No reference glyphs. */
    private fun drawSample(canvas:Canvas, paint:Paint) {
        if (!session.synthetic) {
            realBackend?.result()?.raster?.draw(canvas,paint)
            return
        }
        if (session.state().pageIndex != 0) return
        paint.style=Paint.Style.FILL; paint.color=ink
        for(line in RegionVisualFixture.lines) {
            if(line.text.isEmpty()) continue
            paint.textSize=(line.bounds.height*.82).toFloat()
            canvas.drawText(line.text,line.bounds.left.toFloat(),(line.bounds.bottom-5).toFloat(),paint)
        }
    }
    internal inner class SourceView:View(activity) {
        internal var factor=1.0; private set
        internal var leftPad=0.0; private set
        internal var topPad=0.0; private set
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastX=0f; private var lastY=0f; private var resizing=false; private var dragging=false
        init { tag="lab_source"; contentDescription="整页样例，直接拖动红框或右上角调整选区"; setLayerType(View.LAYER_TYPE_SOFTWARE,null) }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { relayoutPage() }
        internal fun relayoutPage() {
            factor=min((width-dp(12)).coerceAtLeast(1)/session.pageWidth.toDouble(),
                (height-dp(12)).coerceAtLeast(1)/session.pageHeight.toDouble())
            leftPad=(width-session.pageWidth*factor)/2; topPad=(height-session.pageHeight*factor)/2
            post { viewportChanged() }; invalidate()
        }
        override fun onDraw(c:Canvas) {
            super.onDraw(c); c.drawColor(Color.WHITE)
            val state=session.state(); c.save(); c.translate(leftPad.toFloat(),topPad.toFloat()); c.scale(factor.toFloat(),factor.toFloat())
            paint.style=Paint.Style.FILL; paint.color=Color.rgb(250,251,252); c.drawRect(0f,0f,session.pageWidth.toFloat(),session.pageHeight.toFloat(),paint)
            drawSample(c,paint)
            if(realBackend?.startMode==RegionVisualSession.StartMode.BINDING_DEMO) {
                state.frame?.page?.rawCandidates?.forEachIndexed { index,candidate ->
                    paint.color=amber; paint.textSize=(dp(13)/factor).toFloat()
                    val q=candidate.provenance.pageQuad
                    c.drawText((index+1).toString(),q.minOf { it.x }.toFloat(),q.minOf { it.y }.toFloat(),paint)
                }
            }
            val r=state.roi; paint.color=red; paint.strokeWidth=(dp(2)/factor).toFloat(); paint.style=Paint.Style.STROKE
            c.drawRect(r.left.toFloat(),r.top.toFloat(),r.right.toFloat(),r.bottom.toFloat(),paint)
            paint.style=Paint.Style.FILL
            val mark=(dp(24)/factor).toFloat()
            val half=min(dp(12).toDouble(),min(width,height)/2.0)
            val markerX=((leftPad+r.right*factor).coerceIn(half,width-half)-leftPad)/factor
            val markerY=((topPad+r.top*factor).coerceIn(half,height-half)-topPad)/factor
            c.drawRoundRect((markerX-mark/2).toFloat(),(markerY-mark/2).toFloat(),
                (markerX+mark/2).toFloat(),(markerY+mark/2).toFloat(),mark/6,mark/6,paint)
            paint.color=Color.WHITE; paint.strokeWidth=(dp(2)/factor).toFloat()
            // Northeast arrow communicates resize; keep the full marker visible at page edges.
            c.drawLine((markerX-mark/4).toFloat(),(markerY+mark/4).toFloat(),(markerX+mark/4).toFloat(),(markerY-mark/4).toFloat(),paint)
            c.drawLine((markerX-mark/4).toFloat(),(markerY-mark/4).toFloat(),(markerX+mark/4).toFloat(),(markerY-mark/4).toFloat(),paint)
            c.drawLine((markerX+mark/4).toFloat(),(markerY-mark/4).toFloat(),(markerX+mark/4).toFloat(),(markerY+mark/4).toFloat(),paint)
            c.restore()
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            if(!active) return false
            val r=session.state().roi
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val x=(e.x-leftPad)/factor; val y=(e.y-topPad)/factor
                    val hitWidth=min(dp(48).toDouble(),width.toDouble())
                    val hitHeight=min(dp(48).toDouble(),height.toDouble())
                    val hitLeft=(leftPad+r.right*factor-hitWidth/2).coerceIn(0.0,width-hitWidth)
                    val hitTop=(topPad+r.top*factor-hitHeight/2).coerceIn(0.0,height-hitHeight)
                    resizing=e.x>=hitLeft && e.x<=hitLeft+hitWidth && e.y>=hitTop && e.y<=hitTop+hitHeight
                    dragging=resizing || (x>=r.left-dp(8)/factor && x<=r.right+dp(8)/factor && y>=r.top-dp(8)/factor && y<=r.bottom+dp(8)/factor)
                    if(!dragging) return false
                    lastX=e.x; lastY=e.y; parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> dragging=false
                MotionEvent.ACTION_MOVE -> if(dragging && e.pointerCount==1) {
                    val dx=(e.x-lastX)/factor; val dy=(e.y-lastY)/factor
                    if(resizing) session.resize(dx,dy) else session.move(dx,dy)
                    lastX=e.x; lastY=e.y; update()
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { dragging=false; parent.requestDisallowInterceptTouchEvent(false); if(e.actionMasked==MotionEvent.ACTION_UP) performClick() }
            }
            return true
        }
        override fun performClick():Boolean { super.performClick(); return true }
    }
    internal inner class MirrorView:View(activity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastX=0f; private var lastY=0f; private var multi=false
        private val detector=ScaleGestureDetector(activity,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d:ScaleGestureDetector):Boolean { session.scale(session.state().scale*d.scaleFactor); update(); return true }
        })
        init { tag="lab_mirror"; contentDescription="镜面位置，双指缩放，单指平移"; detector.isQuickScaleEnabled=false
            setLayerType(View.LAYER_TYPE_SOFTWARE,null) }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { post { viewportChanged() } }
        override fun onDraw(c:Canvas) {
            super.onDraw(c); c.drawColor(Color.rgb(229,235,240)); val f=session.state().frame
            if(f==null || (session.synthetic && f.page.rawCandidates.isEmpty())) {
                paint.style=Paint.Style.FILL; paint.color=muted; paint.textSize=dp(14).toFloat()
                c.drawText(if(f==null) "等待$showLabel" else "有效空白样例",dp(12).toFloat(),dp(28).toFloat(),paint); return
            }
            c.save(); c.translate(dp(8).toFloat(),dp(8).toFloat())
            c.clipRect(0f,0f,(width-dp(16)).coerceAtLeast(0).toFloat(),(height-dp(16)).coerceAtLeast(0).toFloat())
            c.save()
            val previewZoom=(source.factor*f.transform.scale).toFloat()
            c.scale(previewZoom,previewZoom)
            c.translate(-(f.roi.left+f.transform.panX).toFloat(),-(f.roi.top+f.transform.panY).toFloat())
            c.clipRect(f.roi.left.toFloat(),f.roi.top.toFloat(),f.roi.right.toFloat(),f.roi.bottom.toFloat())
            drawSample(c,paint)
            c.restore()
            val anchors=demoRender?.anchors
            if(anchors!=null) {
                anchors.forEachIndexed { index,anchor ->
                    val candidate=demoSourceMap[anchor.sourceId] ?: return@forEachIndexed
                    val n=f.page.rawCandidates.indexOfFirst { it.provenance.id==candidate.provenance.id }+1
                    val b=anchor.rect; val factor=source.factor.toFloat()
                    paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(2).toFloat(); paint.color=amber
                    c.drawRect(RectF((b.left*factor).toFloat(),(b.top*factor).toFloat(),(b.right*factor).toFloat(),(b.bottom*factor).toFloat()),paint)
                    paint.style=Paint.Style.FILL; paint.textSize=dp(13).toFloat()
                    c.drawText(n.toString(),(b.left*factor+index%3*dp(18)).toFloat(),(b.top*factor+dp(16)).toFloat(),paint)
                }
                c.restore(); return
            }
            f.visible.forEachIndexed { index,p ->
                val b=p.mirrorRect; val s=source.factor.toFloat()
                paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(2).toFloat(); paint.color=amber
                c.drawRect(RectF(b.left.toFloat()*s,b.top.toFloat()*s,b.right.toFloat()*s,b.bottom.toFloat()*s),paint)
                // Raw strings stay in readable cards. Offset overlapping numeric badges without choosing a winner.
                paint.style=Paint.Style.FILL; paint.textSize=dp(13).toFloat()
                val n=f.page.rawCandidates.indexOfFirst { it.provenance.id==p.candidateId }+1
                val x=(b.left*s+index%3*dp(18)).toFloat().coerceIn(0f,(width-dp(32)).coerceAtLeast(0).toFloat())
                val y=(b.top*s+dp(16)+index%2*dp(17)).toFloat().coerceIn(dp(14).toFloat(),(height-dp(16)).coerceAtLeast(dp(14)).toFloat())
                c.drawText(n.toString(),x,y,paint)
            }; c.restore()
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            if(!active) return false
            if(e.actionMasked==MotionEvent.ACTION_DOWN) { multi=false; lastX=e.x; lastY=e.y; parent.requestDisallowInterceptTouchEvent(true) }
            if(e.pointerCount>1 || e.actionMasked==MotionEvent.ACTION_POINTER_DOWN) multi=true
            detector.onTouchEvent(e)
            if(e.actionMasked==MotionEvent.ACTION_MOVE && !multi && !detector.isInProgress) {
                val divisor=source.factor*session.state().scale
                session.pan((lastX-e.x)/divisor,(lastY-e.y)/divisor); lastX=e.x; lastY=e.y; update()
            }
            if(e.actionMasked==MotionEvent.ACTION_UP || e.actionMasked==MotionEvent.ACTION_CANCEL) {
                parent.requestDisallowInterceptTouchEvent(false); if(e.actionMasked==MotionEvent.ACTION_UP) performClick()
            }
            return true
        }
        override fun performClick():Boolean { super.performClick(); return true }
    }
}
