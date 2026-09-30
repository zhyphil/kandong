package com.kandong.modelprobe

import com.kandong.ocrlab.context.*
import com.kandong.ocrlab.context.capture.CaptureCheckpoint
import com.kandong.ocrlab.context.capture.CaptureVersion
import java.util.UUID

/** Main/owning-thread, synchronous synthetic display session. Geometry edits never load a page. */
internal class RegionVisualSession(private val now: () -> Long) {
    enum class Mode { EXPANDED, COLLAPSED, MENU, STOPPED }
    data class State(val mode: Mode, val pageIndex: Int, val roi: ContextRect, val scale: Double,
        val panX: Double, val panY: Double, val loads: Int, val frame: FullPageRegionController.Frame?, val status: String)
    private val owner = Thread.currentThread()
    private var value = State(Mode.EXPANDED,0,ContextRect(60.0,340.0,900.0,480.0),2.0,0.0,0.0,0,null,"等待展示样例")
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
    fun state(): State { own(); return value }
    fun showSample() {
        own()
        if (!foreground || value.mode == Mode.MENU || value.mode == Mode.COLLAPSED) return
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
        controller.clear(reason); value=value.copy(frame=null,status=status)
    }
    private fun normalize() {
        val t=ContextGeometry.transform(value.roi,MirrorTransform(value.scale,mirrorWidth,mirrorHeight,value.panX,value.panY))
        value=value.copy(panX=t.panX,panY=t.panY)
    }
    private fun render(force: Boolean = false) {
        normalize()
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
    fun viewport(width: Double, height: Double) {
        own(); if (!width.isFinite() || !height.isFinite() || width<=0 || height<=0 || width>10_000 || height>10_000) return
        mirrorWidth=width; mirrorHeight=height; render()
    }
    /** UI maps the 48dp target into source units; pure session always has a >=48px minimum. */
    fun minimumRegion(sourcePixels: Double) {
        own(); if (!sourcePixels.isFinite()) return
        minimum=sourcePixels.coerceIn(48.0,800.0)
        val r=value.roi; val w=maxOf(r.width,minimum); val h=maxOf(r.height,minimum)
        val l=r.left.coerceAtMost(1200.0-w); val t=r.top.coerceAtMost(800.0-h)
        value=value.copy(roi=ContextRect(l,t,l+w,t+h)); render()
    }
    fun move(dx: Double, dy: Double) {
        own(); if (!dx.isFinite() || !dy.isFinite()) return
        val r=value.roi; val l=(r.left+dx).coerceIn(0.0,1200.0-r.width); val t=(r.top+dy).coerceIn(0.0,800.0-r.height)
        value=value.copy(roi=ContextRect(l,t,l+r.width,t+r.height)); render()
    }
    fun resize(dx: Double, dy: Double) {
        own(); if (!dx.isFinite() || !dy.isFinite()) return
        val r=value.roi
        value=value.copy(roi=ContextRect(r.left,(r.top+dy).coerceIn(0.0,r.bottom-minimum),
            (r.right+dx).coerceIn(r.left+minimum,1200.0),r.bottom)); render()
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
        value=value.copy(pageIndex=1-value.pageIndex); generation++
        version=CaptureVersion(generation,generation,value.pageIndex.toLong(),generation,0,0); controller.observePage(version)
    }
    fun refresh() { own(); render() }
}
