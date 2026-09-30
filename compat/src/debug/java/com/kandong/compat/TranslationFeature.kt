package com.kandong.compat

import android.app.AlertDialog
import android.graphics.*
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
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
    private val relay=LiveRelayClient(context)
    private var layer: FrameLayout?=null
    private var resultView: ResultView?=null
    private var dialog: AlertDialog?=null
    private var probe: Probe?=null
    private var snapshot: Bitmap?=null
    private var blocks=emptyList<OcrBlock>()
    private var translations=emptyMap<String,String>()
    private var language="EN"
    private var online=false
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
    private var blankFrames=0
    private var boundaryDiagnostics: com.kandong.liveocr.OcrBoundaryDiagnostics?=null
    private fun now()=SystemClock.elapsedRealtime()
    private fun current(token: Long)=!dead && state.current(token,now())

    fun bind(layer: FrameLayout) {
        this.layer=layer
        resultView=ResultView().also { layer.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        layer.visibility=View.GONE
    }
    fun tap() {
        if(!host.active || dead) return
        if(state.active) { invalidate(); toast("已回到原文；再次点翻译可读取新页面。"); return }
        if(busy.get()) { toast("正在结束上一项处理，请稍后再试。"); return }
        configure()
    }
    private fun line(value: String)=CompatUi.text(context,value,17f)
    private fun column()=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(24))
        setBackgroundColor(CompatUi.background)
    }
    private fun configure() {
        language="EN"; online=false
        val body=column()
        body.addView(line("翻译当前页 · 开发版"))
        body.addView(line("点下方按钮后，才临时读取当前整屏可见内容并在本机识字。包括红框外的文字；不保存图片或文字。请使用公开、无个人信息的单语页面，按下识别后会先隐藏本应用遮挡，再取得一张整页快照；后续页面变化不影响本次处理。"))
        val languages=RadioGroup(context)
        listOf("英语" to "EN","法语" to "FR","简体中文（保留原文）" to "ZH-HANS","繁体中文（保留原文）" to "ZH-HANT").forEachIndexed { index,(title,value) ->
            languages.addView(RadioButton(context).apply {
                id=View.generateViewId(); text=title; textSize=18f; minHeight=dp(48); isChecked=index==0
                setOnCheckedChangeListener { _, checked -> if(checked) language=value }
            })
        }
        body.addView(languages)
        val providers=RadioGroup(context)
        listOf("本机识字：显示原文（默认）","DeepL：翻译成中文（需 USB / Mac）").forEachIndexed { index,title ->
            providers.addView(RadioButton(context).apply {
                id=View.generateViewId(); text=title; textSize=18f; minHeight=dp(48); isChecked=index==0
                setOnCheckedChangeListener { _, checked -> if(checked) online=index==1 }
            })
        }
        body.addView(providers)
        body.addView(line("本机英法翻译尚未提供。联网不会自动发生：识字后还需查看整页文字并确认发送。中文页仅保留原文。"))
        val consent=CheckBox(context).apply { text="同意本次在手机内识别当前整页"; textSize=18f; minHeight=dp(56); isSaveEnabled=false }
        body.addView(consent)
        val start=CompatUi.button(context,"识别本页",true) {
            if(!consent.isChecked || busy.get()) return@button
            dismissDialog()
            val token=state.begin()
            captureSamples="none"; witnessedSamples="none"
            rawCandidates=-1; recognizedBlocks=-1; eligibleBlocks=-1
            blankFrames=0
            boundaryDiagnostics=null
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
                recognize(token,bytes)
            }
        }.apply { isEnabled=false }
        consent.setOnCheckedChangeListener { _, checked -> start.isEnabled=checked }
        body.addView(start)
        body.addView(CompatUi.button(context,"取消") { invalidate() })
        openDialog(body)
    }
    private fun openDialog(body: View) {
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
        d.setOnCancelListener { invalidate() }
        d.show()
        d.window?.apply { setLayout(-1,-1); decorView.requestApplyInsets() }
    }
    private fun dismissDialog() { dialog?.setOnCancelListener(null); dialog?.dismiss(); dialog=null }
    private fun recognize(token: Long, bytes: ByteArray) {
        if(!busy.compareAndSet(false,true)) { bytes.fill(0); fail("上一项处理尚未结束。","busy"); return }
        status("本次快照 · 正在识字…","ocr"); ocrRuns++
        val lang=language
        val safe=Rect(host.safeBounds)
        worker.execute {
            try {
                val page=LiveOcrEngine(context).recognize(bytes,host.screenWidth,host.screenHeight,lang) { current(token) }
                val eligible=page.blocks.filter { it.left>=safe.left && it.top>=safe.top && it.right<=safe.right && it.bottom<=safe.bottom }
                main.post { if(current(token)) {
                    rawCandidates=page.rawCandidateCount; recognizedBlocks=page.blocks.size; eligibleBlocks=eligible.size
                } }
                check(eligible.isNotEmpty()) { "NO_TEXT" }
                check(eligible.map { it.id }.distinct().size == eligible.size) { "OCR_FAILED" }
                check(!TranslationTextPolicy.sensitive(eligible.joinToString("\n") { it.text })) { "SENSITIVE_PAGE" }
                main.post {
                    if(!current(token)) return@post
                    blocks=eligible.toList()
                    if(online && lang in listOf("EN","FR")) preview(token)
                    else { status("本次快照 · 已识别 ${blocks.size} 块","local"); published++; host.refreshTranslation() }
                }
            } catch(e: Exception) { main.post { if(current(token)) error(e) } }
            finally { bytes.fill(0); busy.set(false) }
        }
    }
    private fun preview(token: Long) {
        val body=column()
        body.addView(line("确认本次快照联网翻译"))
        body.addView(line(PROVIDER_DISCLOSURE))
        body.addView(line("以下是本次快照将发送的全部 ${blocks.size} 条文字（包括红框外）。请检查是否漏字、错字或含隐私。"))
        body.addView(line(blocks.joinToString("\n\n") { it.text }))
        val consent=CheckBox(context).apply {
            text="已检查：本页只有公开信息，无个人或保密内容；同意发送全部文字给 DeepL。"
            textSize=18f; minHeight=dp(56); isSaveEnabled=false
        }
        body.addView(consent)
        val send=CompatUi.button(context,"发送本页并翻译",true) {
            if(!consent.isChecked || !current(token)) return@button
            dismissDialog()
            host.hideControls(false)
            send(token)
        }.apply { isEnabled=false }
        consent.setOnCheckedChangeListener { _, checked -> send.isEnabled=checked && current(token) }
        body.addView(send)
        body.addView(CompatUi.button(context,"不发送，回到原文") { invalidate() })
        openDialog(body)
    }
    private fun send(token: Long) {
        if(!current(token) || !busy.compareAndSet(false,true)) return
        val selected=blocks.toList(); val lang=language
        status("本次快照 · 正在翻译…","sending"); sends++
        worker.execute {
            try {
                val result=relay.translate(selected,lang,{ LiveTranslationState.TTL-(now()-state.capturedAt) }) { current(token) }
                main.post {
                    if(current(token)) {
                        translations=result
                        status("本次快照 · 机译待核对","translated"); published++; host.refreshTranslation()
                    }
                }
            } catch(e: Exception) { main.post { if(current(token)) error(e) } }
            finally { busy.set(false) }
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
        resultView?.apply {
            this.crop=crop; scale=viewport.scale; tx=viewport.translateX; ty=viewport.translateY
            invalidate()
        }
    }
    private inner class ResultView: View(context) {
        var crop=Box(0,0,1,1); var scale=2f; var tx=0f; var ty=0f
        private val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=CompatUi.ink }
        private val fill=Paint().apply { color=Color.WHITE }
        private val imagePaint=Paint(Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val captured=snapshot ?: return
            canvas.save(); canvas.translate(tx,ty); canvas.scale(scale,scale)
            canvas.clipRect(0f,0f,crop.width.toFloat(),crop.height.toFloat())
            canvas.drawBitmap(captured,-crop.left.toFloat(),-crop.top.toFloat(),imagePaint)
            canvas.restore()
            if(reason !in listOf("local","translated")) return
            val selected=blocks.filter { it.left<crop.right && it.right>crop.left && it.top<crop.bottom && it.bottom>crop.top }
            if(selected.isEmpty()) {
                paint.textSize=dp(18).toFloat(); canvas.drawText("红框内没有识别到文字",dp(12).toFloat(),dp(36).toFloat(),paint); return
            }
            canvas.save(); canvas.translate(tx,ty); canvas.scale(scale,scale)
            canvas.clipRect(0f,0f,crop.width.toFloat(),crop.height.toFloat())
            selected.forEach { block ->
                val text=translations[block.id] ?: block.text
                val left=(block.left-crop.left).toFloat(); val top=(block.top-crop.top).toFloat()
                val w=(block.right-block.left).coerceAtLeast(1); val h=(block.bottom-block.top).coerceAtLeast(1)
                paint.textSize=(h*.85f).coerceIn(12f,48f)
                // Wrap within the identified source element. The development UI is
                // source-anchored text, not a claim of pixel-perfect webpage replacement.
                val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,w)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
                canvas.save(); canvas.translate(left,top)
                canvas.drawRect(0f,0f,w.toFloat(),maxOf(h,layout.height).toFloat(),fill)
                layout.draw(canvas); canvas.restore()
            }
            canvas.restore()
        }
    }
    fun invalidate() {
        state.invalidate(); relay.cancel(); probe=null
        snapshot?.recycle(); snapshot=null
        blocks=emptyList(); translations=emptyMap(); label=null
        main.removeCallbacksAndMessages(null)
        dismissDialog(); host.witness(null)
        layer?.visibility=View.GONE
        if(host.active) host.hideControls(false)
        reason="idle"; host.refreshTranslation()
    }
    fun close() { dead=true; invalidate(); worker.shutdown(); layer=null; resultView=null }
    private fun status(value: String, code: String) { label=value; reason=code; host.refreshTranslation() }
    private fun fail(value: String, code: String) { invalidate(); status(value,code); toast(value) }
    private fun error(e: Exception) {
        boundaryDiagnostics=(e as? com.kandong.liveocr.LiveOcrException)?.boundaryDiagnostics
        val value=when(e.message) {
            "SENSITIVE_PAGE" -> "检测到可能的敏感信息，已清除本页，未发送。"
            "NO_TEXT" -> "没有识别到可用文字，请换清晰的单语页面。"
            "RELAY_NOT_CONFIGURED","RELAY_UNAVAILABLE" -> "Mac 翻译连接不可用，请检查 USB 和转发服务。未自动重试。"
            "FREE_QUOTA_EXCEEDED","SESSION_LIMIT" -> "本次免费翻译额度不足，未继续请求。"
            "PAGE_TOO_LARGE" -> "本页文字超过开发版处理上限。"
            "RECOGNITION_WIDTH_BUDGET" -> "本页含过长的文字行，当前版本尚不能完整识别。"
            "STRIP_BOUNDARY_AMBIGUITY" -> "部分文字落在识别分段边缘，本次未显示不完整结果。"
            "UNREADABLE_BOX" -> "本次有文字块无法读清，未显示不完整结果。"
            else -> "本次处理未完成，请重新点翻译。不会自动重试或联网。"
        }
        val code=if(e is com.kandong.liveocr.LiveOcrException) "ocr_"+e.code.lowercase(java.util.Locale.ROOT)
            else if(e.message=="NO_TEXT") "no_text" else "processing_failed"
        fail(value,code)
    }
    private fun toast(value:String) { Toast.makeText(context,value,Toast.LENGTH_LONG).show() }
    private fun dp(n:Int)=CompatUi.dp(context,n)
    fun diagnostics()="translation=$reason liveCaptures=$captures ocrRuns=$ocrRuns sends=$sends published=$published blockCount=${blocks.size} workerBusy=${busy.get()} snapshot=${snapshot!=null}"+
        " samples(total/dark/light/opaque/edges)=$captureSamples witnessSamples=$witnessedSamples blankFrames=$blankFrames rawCandidates=$rawCandidates recognized=$recognizedBlocks eligible=$eligibleBlocks"+
        " boundary=$boundaryDiagnostics"
    companion object {
        const val AVAILABLE=true
        const val DISCLOSURE="放大镜临时读取整屏，仅在手机内显示选区。点“翻译”并另外同意后，才在本机识别整页文字。联网翻译需连接 Mac，并逐页确认发送给 DeepL；图片不上传，文字和图片不保存。收起暂停，关闭同时停止共享。"
        const val PROVIDER_DISCLOSURE="供应商：DeepL API Free。经 USB 和这台 Mac 发送本次整屏快照识别出的全部文字（含红框外上下文），不发送图片。DeepL 的免费服务条款允许临时保留内容用于改进服务；不能承诺零留存，请勿提交个人或保密信息。详见 deepl.com/en/privacy 第3、13节。开发版需保持 USB / Mac 连接。机器翻译可能出错，请对照原文。"
        const val PRIVACY=DISCLOSURE+"\n\n"+PROVIDER_DISCLOSURE+"\n\n只有点“翻译”后才识字；本机是默认选项，英法离线翻译尚不可用。按下识别后先隐藏自有遮挡，再取得一张快照；本次识字、翻译和镜面显示始终使用它，底层页面变化不会取消。移动红框、缩放和平移只改变这张快照的显示。60秒过期会清除快照和文字。收起、菜单、原文、锁屏或停止会清空本次文字和待返回结果。\n\n快照在内存中短暂保留，不写入相册或文件。按下按钮和取得无遮挡画面之间有短暂间隔；请在取图完成前保持页面不动。密码、银行、聊天等敏感页面请先关闭放大镜。发送后关闭功能无法撤回已经到达供应商的文字。"
        private fun rgb(b:ByteArray,offset:Int)=Color.rgb(b[offset].toInt() and 255,b[offset+1].toInt() and 255,b[offset+2].toInt() and 255)
        private fun rgb(b:ByteBuffer,offset:Int)=Color.rgb(b.get(offset).toInt() and 255,b.get(offset+1).toInt() and 255,b.get(offset+2).toInt() and 255)
        private fun distance(a:Int,b:Int)=maxOf(abs(Color.red(a)-Color.red(b)),abs(Color.green(a)-Color.green(b)),abs(Color.blue(a)-Color.blue(b)))
    }
}
