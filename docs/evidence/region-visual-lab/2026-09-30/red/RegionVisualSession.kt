package com.kandong.modelprobe

import com.kandong.ocrlab.context.ContextRect

/** Temporary compilable behavior stub; replaced after verified red tests. */
internal class RegionVisualSession(private val now: () -> Long) {
    enum class Mode { EXPANDED, COLLAPSED, MENU, STOPPED }
    data class State(val mode: Mode, val pageIndex: Int, val roi: ContextRect, val scale: Double,
        val panX: Double, val panY: Double, val loads: Int, val frame: FullPageRegionController.Frame?, val status: String)
    fun state() = State(Mode.EXPANDED,0,ContextRect(60.0,340.0,900.0,480.0),2.0,0.0,0.0,0,null,"等待展示样例")
    fun showSample() {}
    fun viewport(width: Double, height: Double) {}
    fun move(dx: Double, dy: Double) {}
    fun resize(dx: Double, dy: Double) {}
    fun scale(value: Double) {}
    fun pan(dx: Double, dy: Double) {}
    fun collapse() {}
    fun restore() {}
    fun menu() {}
    fun closeMenu() {}
    fun stop() {}
    fun foreground(active: Boolean) {}
    fun nextPage() {}
    fun refresh() {}
}
