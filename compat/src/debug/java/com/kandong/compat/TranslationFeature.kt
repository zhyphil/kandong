package com.kandong.compat

import android.app.AlertDialog
import android.graphics.*
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.view.*
import android.widget.*
import com.kandong.liveocr.LiveOcrEngine
import com.kandong.liveocr.OcrBlock
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** Development-only real-page integration. Neither screenshots nor OCR text are persisted. */
internal class TranslationFeature(private val host: TranslationHost) {
    val enabled=true
    var label: String?=null; private set
    val showing get() = snapshot != null
    val active get() = state.active
    private val context get()=host.context
    private val state=LiveTranslationState()
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val relay=LiveRelayClient()
    private var layer: FrameLayout?=null
    private var resultView: ResultView?=null
    private var fullTextButton: Button?=null
    private var dialog: AlertDialog?=null
    private var probe: Probe?=null
    private var snapshot: Bitmap?=null
    private var blocks=emptyList<OcrBlock>()
    private var unreadable=emptyList<OcrBlock>()
    private var translations=emptyMap<String,String>()
    private var language="EN"
    private var dead=false
    private var captures=0
    private var ocrRuns=0
    private var sends=0
    private var published=0
    private var reason="idle"
    private var captureSamples="none"
    private var witnessedSamples="none"
    private var rawCandidates=-1
    private var recognizedBlocks=-1
    private var eligibleBlocks=-1
    private var lastUnreadableCount=-1
    private var blankFrames=0
    private fun now()=SystemClock.elapsedRealtime()
    private fun current(token: Long)=!dead && state.current(token,now())

    fun bind(layer: FrameLayout) {
        this.layer=layer
        resultView=ResultView().also { layer.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        fullTextButton=Button(context).apply {
            text="全文"; textSize=16f; isAllCaps=false
            contentDescription="查看红框内完整原文和译文"
            setTextColor(Color.WHITE); background=CompatUi.ripple(context,CompatUi.teal)
            minWidth=0; minHeight=0; setPadding(0,0,0,0)
            setOnClickListener { showFullText() }
            visibility=View.GONE
        }.also { layer.addView(it,FrameLayout.LayoutParams(dp(64),dp(48),Gravity.TOP or Gravity.END)) }
        layer.visibility=View.GONE
    }
    fun tap() {
        if(!host.active || dead) return
        if(state.active) { invalidate(); toast("已回到原文；再次点翻译可读取新页面。"); return }
        if(busy.get()) { toast("正在结束上一项处理，请稍后再试。"); return }
        configure()
    }
    private fun configure() {
        openDialog(TranslationSetupView.body(context,language,::startTranslation,::invalidate))
    }
    private fun startTranslation(selectedLanguage: String) {
        if(busy.get() || !host.active || dead) return
        val config=try { CloudTranslationConfigStore(context).load() } catch(e:Exception) {
            invalidate(); toast(LiveTranslationError.from(e).message); return
        }
        language=selectedLanguage
        dismissDialog()
        val token=state.begin()
        captureSamples="none"; witnessedSamples="none"
        rawCandidates=-1; recognizedBlocks=-1; eligibleBlocks=-1
        lastUnreadableCount=-1; blankFrames=0
        status("正在读取当前页…","capture")
        captureClean { bytes ->
            if(!current(token)) { bytes.fill(0); return@captureClean }
            if(!state.captured(token,now())) { bytes.fill(0); return@captureClean }
            try {
                snapshot=Bitmap.createBitmap(host.screenWidth,host.screenHeight,Bitmap.Config.ARGB_8888).apply {
                    density=Bitmap.DENSITY_NONE
                    copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                }
            } catch(_: Exception) { bytes.fill(0); fail("本次快照未能打开，请重试。","snapshot_failed"); return@captureClean }
            main.postDelayed({ if(state.active && state.epoch==token) fail("本页结果已过期，请重新点翻译。","expired") },LiveTranslationState.TTL)
            recognize(token,bytes,config)
        }
    }
    private fun openDialog(body: View, onCancel: ()->Unit = { invalidate() }) {
        dismissDialog()
        host.hideControls(true)
        val wrapper=ScrollView(context).apply { isFillViewport=true; addView(body) }
        wrapper.setOnApplyWindowInsetsListener { view,insets ->
            if(android.os.Build.VERSION.SDK_INT >= 30) {
                val safe=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            }
            insets
        }
        val d=AlertDialog.Builder(ContextThemeWrapper(context,android.R.style.Theme_Material_Light_NoActionBar)).setView(wrapper).create()
        dialog=d
        d.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            attributes=attributes.apply { windowAnimations=0 }
        }
        d.setOnCancelListener { onCancel() }
        d.show()
        d.window?.apply { setLayout(-1,-1); decorView.requestApplyInsets() }
    }
    private fun dismissDialog() { dialog?.setOnCancelListener(null); dialog?.dismiss(); dialog=null }
    private fun showFullText() {
        val token=state.epoch
        if(!current(token) || snapshot==null || reason != "translated") return
        val crop=resultView?.crop ?: return
        val entries=SnapshotTextContent.select(blocks,translations,crop)
        if(entries.isEmpty()) return
        fun restore() {
            dismissDialog()
            if(!current(token)) { invalidate(); return }
            host.hideControls(false)
            host.refreshTranslation()
        }
        openDialog(SnapshotTextDetails.body(context,entries,::restore),::restore)
    }
    private fun recognize(token: Long, bytes: ByteArray, config: CloudTranslationConfig) {
        if(!busy.compareAndSet(false,true)) { bytes.fill(0); fail("上一项处理尚未结束。","busy"); return }
        status("本次快照 · 正在识字…","ocr"); ocrRuns++
        val lang=language
        val safe=Rect(host.safeBounds)
        worker.execute {
            var sending=false
            try {
                // Keep both stages on this worker. Posting a second worker job from
                // the OCR callback would race with the OCR finally/busy reset.
                val result=DirectPageTranslation.run(lang,
                    recognize={
                        try {
                            val page=LiveOcrEngine(context).recognize(bytes,host.screenWidth,host.screenHeight,lang) { current(token) }
                            val eligible=page.blocks.filter { it.left>=safe.left && it.top>=safe.top && it.right<=safe.right && it.bottom<=safe.bottom }
                            val unclear=page.unreadable.filter { it.left<safe.right && it.right>safe.left && it.top<safe.bottom && it.bottom>safe.top }
                            main.post { if(current(token)) {
                                rawCandidates=page.rawCandidateCount; recognizedBlocks=page.blocks.size; eligibleBlocks=eligible.size
                                lastUnreadableCount=unclear.size
                            } }
                            com.kandong.liveocr.OcrPage(eligible,page.rawCandidateCount,unclear)
                        } finally { bytes.fill(0) }
                    },
                    translate={ selected,source ->
                        relay.translate(config,selected,source,{ LiveTranslationState.TTL-(now()-state.capturedAt) }) { current(token) }
                    },
                    current={ current(token) },
                    onRecognized={ page -> main.post { if(current(token)) {
                        blocks=page.blocks.toList(); unreadable=page.unreadable.toList()
                    } } },
                    onSending={
                        sending=true
                        main.post { if(current(token)) { status("本次快照 · 正在翻译…","sending"); sends++ } }
                    })
                main.post {
                    if(!current(token)) return@post
                    if(result.page.blocks.isEmpty()) {
                        status("本次快照 · 暂未读到清晰文字，已保留原图","no_text")
                    } else {
                        translations=result.translations
                        status("本次快照 · 机译待核对"+unreadableLabel(),"translated"); published++
                    }
                    host.refreshTranslation()
                }
            } catch(e: Exception) { main.post { if(current(token)) error(e,sending=sending) } }
            finally { bytes.fill(0); busy.set(false) }
        }
    }
    private inner class Probe(val x:Int,val y:Int,val colors:IntArray,val token:Long,val done:(ByteArray)->Unit) {
        val gate=FreshFrameGate()
    }
    private fun captureClean(done: (ByteArray)->Unit) {
        if(!host.active || dead) return
        probe=null; host.witness(null)
        val footprint=host.obscuredRects().firstOrNull() ?: host.safeBounds
        val x=(footprint.left+24).coerceIn(host.safeBounds.left,(host.safeBounds.right-48).coerceAtLeast(host.safeBounds.left))
        val y=(footprint.top+24).coerceIn(host.safeBounds.top,(host.safeBounds.bottom-48).coerceAtLeast(host.safeBounds.top))
        val random=java.security.SecureRandom()
        val colors=IntArray(9) { Color.rgb(32+random.nextInt(192),32+random.nextInt(192),32+random.nextInt(192)) }
        val p=Probe(x,y,colors,state.epoch,done); probe=p
        host.hideControls(true)
        val witness=object: View(context) {
            val paint=Paint()
            override fun onDraw(canvas: Canvas) {
                colors.forEachIndexed { index,color ->
                    paint.color=color
                    canvas.drawRect((index%3*16).toFloat(),(index/3*16).toFloat(),(index%3*16+16).toFloat(),(index/3*16+16).toFloat(),paint)
                }
            }
        }
        host.witness(witness,x,y,48)
        main.postDelayed({ if(probe===p) {
            if(blankFrames>0) fail("未取得可见页面：本次画面为黑屏，未进行识字。","capture_blank")
            else fail("无法确认最新画面，请重新点翻译。","freshness_timeout")
        } },4500)
    }
    fun frame(image: Image): Boolean {
        if(dead) return false
        if(dialog != null) return true
        val p=probe
        if(p != null) {
            if(!current(p.token)) { invalidate(); return true }
            try {
                require(image.width == host.screenWidth && image.height == host.screenHeight)
                val plane=image.planes.single(); require(plane.pixelStride==4)
                val b=plane.buffer
                fun pixel(x:Int,y:Int)=rgb(b,y*plane.rowStride+x*4)
                val matched=p.colors.indices.count { i -> distance(pixel(p.x+(i%3)*16+8,p.y+(i/3)*16+8),p.colors[i])<=6 }
                val before=p.gate.step
                fun sample(): SnapshotSamples {
                    val safe=host.safeBounds
                    return SnapshotSamples.read(image.width,image.height,safe.left,safe.top,safe.right,safe.bottom) { x,y ->
                        val offset=y*plane.rowStride+x*4
                        ((b.get(offset+3).toInt() and 255) shl 24) or (pixel(x,y) and 0x00ffffff)
                    }
                }
                val cleanSamples=if(before==FreshFrameGate.Step.CLEAN && matched==0) sample() else null
                if(cleanSamples!=null) {
                    captureSamples=cleanSamples.toString()
                    if(!cleanSamples.pageVisible) blankFrames++
                }
                val next=p.gate.observe(image.timestamp,matched==9,matched==0,cleanSamples?.pageVisible ?: false)
                if(before==FreshFrameGate.Step.WITNESS && next==FreshFrameGate.Step.CLEAN) {
                    witnessedSamples=sample().toString(); host.witness(null)
                }
                if(next==FreshFrameGate.Step.COMPLETE) {
                    require(image.width.toLong()*image.height<=4_194_304)
                    val bytes=CropPixels.copy(b,image.width,image.height,plane.rowStride,plane.pixelStride,0,0,image.width,image.height)
                    probe=null; captures++
                    host.hideControls(false)
                    p.done(bytes)
                }
            } catch(_: Exception) { fail("无法读取完整画面，请重新点翻译。","capture_failed") }
            return true
        }
        // This operation owns the captured page, never a subsequent live frame.
        // Live magnification resumes only after explicitly leaving snapshot mode.
        if(state.active && state.capturedAt>=0 && !current(state.epoch)) {
            fail("本次快照已过期，请重新点翻译。","expired")
        }
        return state.showingSnapshot(now())
    }
    fun render(crop: Box, viewport: MagnifierViewport) {
        val visible=state.showingSnapshot(now()) && snapshot != null
        layer?.visibility=if(visible) View.VISIBLE else View.GONE
        fullTextButton?.visibility=if(visible && reason == "translated" &&
            blocks.any { SnapshotTextContent.intersects(it,crop) }) View.VISIBLE else View.GONE
        resultView?.apply {
            this.crop=crop; scale=viewport.scale; tx=viewport.translateX; ty=viewport.translateY
            invalidate()
        }
    }
    private inner class ResultView: View(context) {
        var crop=Box(0,0,1,1); var scale=2f; var tx=0f; var ty=0f
        private val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=CompatUi.ink }
        private var cachedBlocks: List<OcrBlock>?=null
        private var cachedTranslations: Map<String,String>?=null
        private var textPainter: SnapshotTextPainter?=null
        fun clear() { textPainter=null; cachedBlocks=null; cachedTranslations=null }
        private val imagePaint=Paint(Paint.FILTER_BITMAP_FLAG)
        private val unclearPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(183,98,0); style=Paint.Style.STROKE }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val captured=snapshot ?: return
            canvas.save(); canvas.translate(tx,ty); canvas.scale(scale,scale)
            canvas.clipRect(0f,0f,crop.width.toFloat(),crop.height.toFloat())
            canvas.drawBitmap(captured,-crop.left.toFloat(),-crop.top.toFloat(),imagePaint)
            canvas.restore()
            if(reason !in listOf("translated","no_text")) return
            if(cachedBlocks!==blocks || cachedTranslations!==translations) {
                clear(); cachedBlocks=blocks; cachedTranslations=translations
                textPainter=SnapshotTextPainter(blocks,translations)
            }
            val selected=blocks.filter { SnapshotTextContent.intersects(it,crop) }
            val unclear=unreadable.filter { SnapshotTextContent.intersects(it,crop) }
            if(selected.isEmpty() && unclear.isEmpty()) {
                paint.textSize=dp(18).toFloat(); canvas.drawText("红框内没有识别到文字",dp(12).toFloat(),dp(36).toFloat(),paint); return
            }
            canvas.save(); canvas.translate(tx,ty); canvas.scale(scale,scale)
            canvas.clipRect(0f,0f,crop.width.toFloat(),crop.height.toFloat())
            textPainter?.draw(canvas,crop)
            // Restore source pixels even if a neighboring text layout wrapped over this area.
            unclear.forEach { block ->
                val left=(block.left-crop.left).toFloat(); val top=(block.top-crop.top).toFloat()
                val right=(block.right-crop.left).toFloat(); val bottom=(block.bottom-crop.top).toFloat()
                canvas.save(); canvas.clipRect(left,top,right,bottom)
                canvas.drawBitmap(captured,-crop.left.toFloat(),-crop.top.toFloat(),imagePaint)
                canvas.restore()
                unclearPaint.strokeWidth=dp(2).toFloat()/scale
                canvas.drawRect(left,top,right,bottom,unclearPaint)
            }
            canvas.restore()
        }
    }
    fun invalidate() {
        state.invalidate(); relay.cancel(); probe=null
        snapshot?.recycle(); snapshot=null
        blocks=emptyList(); unreadable=emptyList(); translations=emptyMap(); label=null
        resultView?.clear(); fullTextButton?.visibility=View.GONE
        main.removeCallbacksAndMessages(null)
        dismissDialog(); host.witness(null)
        layer?.visibility=View.GONE
        if(host.active) host.hideControls(false)
        reason="idle"; host.refreshTranslation()
    }
    fun close() { dead=true; invalidate(); worker.shutdown(); layer=null; resultView=null }
    private fun status(value: String, code: String) { label=value; reason=code; host.refreshTranslation() }
    private fun unreadableLabel()=if(unreadable.isEmpty()) "" else " · 已跳过${unreadable.size}处"
    private fun fail(value: String, code: String, detail: String = value) { invalidate(); status(value,code); toast(detail) }
    private fun error(e: Exception, sending: Boolean = false) {
        val failure=LiveTranslationError.from(e,sending)
        fail(failure.message.substringBefore('。'),failure.code,failure.message)
    }
    private fun toast(value:String) { Toast.makeText(context,value,Toast.LENGTH_LONG).show() }
    private fun dp(n:Int)=CompatUi.dp(context,n)
    fun diagnostics()="translation=$reason liveCaptures=$captures ocrRuns=$ocrRuns sends=$sends published=$published blockCount=${blocks.size} unreadableCount=${unreadable.size} workerBusy=${busy.get()} snapshot=${snapshot!=null}"+
        " samples(total/dark/light/opaque/edges)=$captureSamples witnessSamples=$witnessedSamples blankFrames=$blankFrames rawCandidates=$rawCandidates recognized=$recognizedBlocks eligible=$eligibleBlocks lastUnreadable=$lastUnreadableCount"
    companion object {
        const val AVAILABLE=true
        const val DISCLOSURE="放大镜临时读取整屏，仅在手机内显示选区。点“开始翻译”后，本机会识别当前整屏文字并经 Cloudflare 中转自动交给 DeepL 翻译；图片不上传，看懂不保存页面文字和图片。收起暂停，关闭同时停止共享。"
        const val PROVIDER_DISCLOSURE="供应商：DeepL API Free。经 Cloudflare HTTPS 中转发送本次整屏快照识别出的全部文字（含红框外上下文），不发送图片。DeepL 的免费服务条款允许临时保留内容用于改进服务；不能承诺零留存，请勿提交个人或保密信息。详见 deepl.com/en/privacy 第3、13节。看懂云端只保留设备/请求标识、额度和到期时间等元数据，不保存页面文字或图片。手机需联网，无需连接电脑。机器翻译可能出错，请对照原文。"
        const val PRIVACY=DISCLOSURE+"\n\n"+PROVIDER_DISCLOSURE+"\n\n只有点“开始翻译”后才识字，并自动将整屏可读文字交给 DeepL 翻译。此按钮确认仅对当前公开页面有效，不显示识字预览或二次确认。先隐藏自有遮挡，再取得一张快照；本次识字、翻译和镜面显示始终使用它，底层页面变化不会取消。移动红框、缩放和平移只改变这张快照的显示。60秒过期会清除快照和文字。收起、菜单、原文、锁屏或停止会清空本次文字和待返回结果。\n\n快照在内存中短暂保留，不写入相册或文件。按下按钮和取得无遮挡画面之间有短暂间隔；请在取图完成前保持页面不动。密码、银行、聊天等敏感页面请先关闭放大镜。发送后关闭功能无法撤回已经到达供应商的文字。"
        private fun rgb(b:ByteArray,offset:Int)=Color.rgb(b[offset].toInt() and 255,b[offset+1].toInt() and 255,b[offset+2].toInt() and 255)
        private fun rgb(b:ByteBuffer,offset:Int)=Color.rgb(b.get(offset).toInt() and 255,b.get(offset+1).toInt() and 255,b.get(offset+2).toInt() and 255)
        private fun distance(a:Int,b:Int)=maxOf(abs(Color.red(a)-Color.red(b)),abs(Color.green(a)-Color.green(b)),abs(Color.blue(a)-Color.blue(b)))
    }
}
