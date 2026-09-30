package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import java.util.UUID
import java.util.concurrent.CancellationException

/** Main-thread geometry shared by explicit synthetic regressions and asynchronous fixed-page OCR.
 * Geometry only derives projections; it never acquires pixels or invokes a model. */
internal class RegionVisualSession(private val backend: Backend? = null, private val now: () -> Long) {
    data class Page(val id: String, val width: Int, val height: Int)
    data class Evidence(val metadata: RgbaFrameMetadata, val page: FullPageOcrAssociation.Result)
    interface Backend {
        val pages: List<Page>
        fun start(pageIndex: Int): Boolean
        fun evidence(): Evidence?
        fun busy(): Boolean
        fun error(): String?
        fun cancel()
        fun dispose()
    }
    val synthetic: Boolean get() = backend == null
    val pageWidth: Int get() = backend?.pages?.get(value.pageIndex)?.width ?: 1200
    val pageHeight: Int get() = backend?.pages?.get(value.pageIndex)?.height ?: 800
    val pageLabel: String get() = backend?.pages?.get(value.pageIndex)?.id ?: if (value.pageIndex == 0) "合成候选页" else "合成空白页"
    enum class Mode { EXPANDED, COLLAPSED, MENU, STOPPED }
    data class State(val mode: Mode, val pageIndex: Int, val roi: ContextRect, val scale: Double,
        val panX: Double, val panY: Double, val loads: Int, val frame: FullPageRegionController.Frame?, val status: String)
    private val owner = Thread.currentThread()
    private var value = State(Mode.EXPANDED,0,
        if (backend == null) ContextRect(60.0,340.0,900.0,480.0) else ContextRect(60.0,60.0,1100.0,800.0),
        2.0,0.0,0.0,0,null,if (backend == null) "等待展示样例" else "等待点击整页 OCR")
    private var foreground = true
    private var beforeMenu = Mode.EXPANDED
    private var generation = 0L
    private var version = CaptureVersion(0,0,0,0,0,0)
    private var mirrorWidth = 600.0
    private var mirrorHeight = 260.0
    private var minimum = 48.0
    private val controller = FullPageRegionController { CaptureCheckpoint(version,
        foreground && value.mode == Mode.EXPANDED,now()) }
    private fun own() = check(Thread.currentThread() === owner)
    init { if (backend != null) { require(backend.pages.isNotEmpty()); revalidateRegion() } }
    fun state(): State { own(); if (backend != null) render(); return value }
    fun showSample() {
        own()
        if (!foreground || value.mode == Mode.MENU || value.mode == Mode.COLLAPSED) return
        if (backend != null) {
            val started = backend.start(value.pageIndex)
            value = value.copy(mode=Mode.EXPANDED, frame=null, loads=value.loads+if(started) 1 else 0,
                status=if(started) "正在处理整页 OCR" else if(backend.busy()) "上次处理尚未结束，请稍后再次点击" else "本次 OCR 未启动")
            render(); return
        }
        value = value.copy(mode=Mode.EXPANDED,frame=null,loads=value.loads+1)
        generation++
        version = CaptureVersion(generation,generation,value.pageIndex.toLong(),generation,0,0)
        controller.observePage(version)
        val ticket = controller.click()
        if (ticket == null) { clear(ClearReason.CLOCK_INVALID,"时间异常，请重新打开实验"); return }
        val meta = RgbaFrameMetadata(RegionVisualFixture.WIDTH,RegionVisualFixture.HEIGHT,version,now(),RegionVisualFixture.TTL)
        try {
            val pending = RegionVisualFixture.stage(controller,ticket,meta,"visual-synthetic/${UUID.randomUUID()}",value.pageIndex == 1)
            val accepted = try { controller.accept(ticket,pending.claimForRegion(true,true)) } finally { pending.discard() }
            if (!accepted) { clear(ClearReason.INVALID_SNAPSHOT,"样例无效，请重新展示"); return }
            render(true)
        } catch (_: Exception) { clear(ClearReason.INVALID_SNAPSHOT,"样例无效，请重新展示") }
    }
    private fun clear(reason: ClearReason, status: String) {
        backend?.cancel(); controller.clear(reason); value=value.copy(frame=null,status=status)
    }
    private fun normalize() {
        val t=ContextGeometry.transform(value.roi,MirrorTransform(value.scale,mirrorWidth,mirrorHeight,value.panX,value.panY))
        value=value.copy(panX=t.panX,panY=t.panY)
    }
    private fun render(force: Boolean = false) {
        normalize()
        if (backend != null) { renderReal(); return }
        if (!force && value.frame == null) return
        val frame=controller.render(value.roi,MirrorTransform(value.scale,mirrorWidth,mirrorHeight,value.panX,value.panY))
        if (frame == null) { clear(ClearReason.EXPIRED,"展示已失效，请再次展示样例"); return }
        val message = when {
            frame.page.rawCandidates.isEmpty() -> "有效空白样例 · 没有文字候选"
            frame.selectedIds.isEmpty() -> "选区内没有候选 · 整页样例仍有效"
            else -> "选区 ${frame.selectedIds.size} 项 · 关联 ${frame.contextIds.size} 项 · 整页 ${frame.page.rawCandidates.size} 项"
        }
        value=value.copy(frame=frame,status=message)
    }
    private fun renderReal() {
        val source = checkNotNull(backend)
        if (!foreground || value.mode != Mode.EXPANDED) { value=value.copy(frame=null); return }
        val evidence = source.evidence()
        if (evidence == null) {
            val message = when (source.error()) {
                "CLEANUP_UNCERTAIN_RESTART" -> "原生清理状态不确定，请重启实验进程"
                "CLOCK_INVALID" -> "时间异常，请重新打开实验"
                "EXPIRED_OR_INVALID" -> "展示已失效，请再次点击整页 OCR"
                null -> if(source.busy()) "正在处理／清理整页 OCR" else value.status
                else -> "本机 OCR 失败，请检查实验安装包后重试"
            }
            value=value.copy(frame=null,status=message); return
        }
        if (evidence.metadata.width != pageWidth || evidence.metadata.height != pageHeight ||
            evidence.page.identity?.pageFixtureId != pageLabel) {
            clear(ClearReason.INVALID_SNAPSHOT,"页面证据不匹配，请重试"); return
        }
        val frame = try {
            FullPageRegionProjection.project(evidence.metadata,evidence.page,value.roi,
                MirrorTransform(value.scale,mirrorWidth,mirrorHeight,value.panX,value.panY)) {
                if (source.evidence() !== evidence) throw CancellationException("DISPLAY_REVOKED")
            }
        } catch (_: CancellationException) { null }
        if (frame == null || source.evidence() !== evidence) {
            value=value.copy(frame=null,status="展示已失效，请再次点击整页 OCR"); return
        }
        value=value.copy(frame=frame,status=if(frame.page.rawCandidates.isEmpty()) "整页 OCR 完成 · 没有文字候选"
            else "选区 ${frame.selectedIds.size} 项 · 关联 ${frame.contextIds.size} 项 · 整页 ${frame.page.rawCandidates.size} 项")
    }
    private fun revalidateRegion() {
        val width=pageWidth.toDouble(); val height=pageHeight.toDouble()
        require(width > 0 && height > 0)
        val r=value.roi
        val w=r.width.coerceIn(minOf(minimum,width),width)
        val h=r.height.coerceIn(minOf(minimum,height),height)
        val left=r.left.coerceIn(0.0,width-w); val top=r.top.coerceIn(0.0,height-h)
        value=value.copy(roi=ContextRect(left,top,left+w,top+h))
    }
    fun viewport(width: Double, height: Double) {
        own(); if (!width.isFinite() || !height.isFinite() || width<=0 || height<=0 || width>10_000 || height>10_000) return
        mirrorWidth=width; mirrorHeight=height; render()
    }
    /** UI maps the 48dp target into source units; pure session always has a >=48px minimum. */
    fun minimumRegion(sourcePixels: Double) {
        own(); if (!sourcePixels.isFinite()) return
        minimum=sourcePixels.coerceAtLeast(48.0)
        revalidateRegion(); render()
    }
    fun move(dx: Double, dy: Double) {
        own(); if (!dx.isFinite() || !dy.isFinite()) return
        val r=value.roi; val l=(r.left+dx).coerceIn(0.0,pageWidth-r.width); val t=(r.top+dy).coerceIn(0.0,pageHeight-r.height)
        value=value.copy(roi=ContextRect(l,t,l+r.width,t+r.height)); render()
    }
    fun resize(dx: Double, dy: Double) {
        own(); if (!dx.isFinite() || !dy.isFinite()) return
        val r=value.roi
        value=value.copy(roi=ContextRect(r.left,(r.top+dy).coerceIn(0.0,r.bottom-minOf(minimum,pageHeight.toDouble())),
            (r.right+dx).coerceIn(r.left+minOf(minimum,pageWidth.toDouble()),pageWidth.toDouble()),r.bottom)); render()
    }
    fun scale(value: Double) { own(); if (!value.isFinite()) return; this.value=this.value.copy(scale=value.coerceIn(1.0,5.0)); render() }
    fun pan(dx: Double, dy: Double) {
        own(); if (!dx.isFinite() || !dy.isFinite()) return
        val x=value.panX+dx; val y=value.panY+dy
        if (!x.isFinite() || !y.isFinite()) return
        value=value.copy(panX=x,panY=y); render()
    }
    fun collapse() { own(); clear(ClearReason.PAUSE,"已收起 · 恢复后请再次展示"); value=value.copy(mode=Mode.COLLAPSED) }
    fun restore() { own(); if (value.mode==Mode.COLLAPSED) value=value.copy(mode=Mode.EXPANDED,status="等待再次展示样例") }
    fun menu() {
        own(); if (value.mode==Mode.MENU) return
        beforeMenu=value.mode; clear(ClearReason.MENU,"菜单已清除展示"); value=value.copy(mode=Mode.MENU)
    }
    fun closeMenu() { own(); if (value.mode==Mode.MENU) value=value.copy(mode=beforeMenu,status="等待再次展示样例") }
    fun stop() { own(); clear(ClearReason.STOP,"已停止 · 点击展示样例可重新开始"); value=value.copy(mode=Mode.STOPPED) }
    fun foreground(active: Boolean) { own(); foreground=active; if (!active) clear(ClearReason.PAUSE,"离开页面已清除展示") }
    fun nextPage() {
        own(); clear(ClearReason.PAGE_CHANGE,"页面已切换，请展示样例")
        value=value.copy(pageIndex=(value.pageIndex+1)%(backend?.pages?.size ?: 2),
            status=if(synthetic) "页面已切换，请展示样例" else "页面已切换，请点击整页 OCR")
        revalidateRegion(); normalize(); generation++
        version=CaptureVersion(generation,generation,value.pageIndex.toLong(),generation,0,0); controller.observePage(version)
    }
    fun refresh() { own(); render() }
    fun dispose() { own(); stop(); backend?.dispose(); foreground=false }
}
