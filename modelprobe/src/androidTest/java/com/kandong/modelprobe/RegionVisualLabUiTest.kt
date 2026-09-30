package com.kandong.modelprobe

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Interacts only with our fixed fixture Activity. No global settings, real-page content or permissions. */
@RunWith(AndroidJUnit4::class)
class RegionVisualLabUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val opened=mutableListOf<RegionVisualLabActivity>()
    private lateinit var activity:RegionVisualLabActivity
    private lateinit var surface:RegionVisualLabSurface
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun launch(extraTask:Boolean=false):RegionVisualLabActivity {
        val intent=Intent(instrumentation.targetContext,RegionVisualLabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or if(extraTask) Intent.FLAG_ACTIVITY_MULTIPLE_TASK else 0)
        return (instrumentation.startActivitySync(intent) as RegionVisualLabActivity).also { opened.add(it) }
    }
    @Before fun start() {
        activity=launch(); instrumentation.waitForIdleSync()
        main { surface=activity.labSurface as RegionVisualLabSurface }
        waitForeground()
        assertNull(state().frame)
    }
    @After fun finish() {
        opened.asReversed().forEach { a -> main { if(!a.isFinishing) a.finish() } }
        instrumentation.waitForIdleSync()
    }
    private fun waitForeground() {
        repeat(100) {
            var ready=false
            main { ready=surface.isForeground() && surface.source.width>0 && surface.mirror.height>0 }
            if(ready) return
            SystemClock.sleep(30)
        }
        fail("Fixture Activity did not become foreground")
    }
    private fun state():RegionVisualSession.State {
        lateinit var result:RegionVisualSession.State
        main { result=surface.session.state() }; return result
    }
    private fun view(tag:String):View {
        lateinit var result:View
        main { result=requireNotNull(surface.getView().findViewWithTag<View>(tag)) { tag } }; return result
    }
    private fun click(tag:String) {
        main { check(surface.isForeground()); val v=surface.getView().findViewWithTag<View>(tag); check(v!=null && v.isShown); v.performClick() }
        instrumentation.waitForIdleSync()
    }
    private fun visibleText():String {
        val texts=mutableListOf<String>()
        fun visit(v:View) {
            if(v.visibility!=View.VISIBLE) return
            if(v is TextView) texts+=v.text.toString()
            if(v is ViewGroup) for(i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        main { visit(surface.getView()) }; return texts.joinToString("\n")
    }
    private fun event(v:View,down:Long,time:Long,action:Int,x:Float,y:Float) {
        main {
            check(surface.isForeground() && v.isShown)
            val e=MotionEvent.obtain(down,time,action,x,y,0); e.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(v.dispatchTouchEvent(e)) } finally { e.recycle() }
        }
    }
    private fun drag(v:View,x:Float,y:Float,dx:Float,dy:Float) {
        val start=SystemClock.uptimeMillis(); event(v,start,start,MotionEvent.ACTION_DOWN,x,y)
        for(i in 1..10) event(v,start,start+i*16L,MotionEvent.ACTION_MOVE,x+dx*i/10,y+dy*i/10)
        event(v,start,start+176,MotionEvent.ACTION_UP,x+dx,y+dy); instrumentation.waitForIdleSync()
    }
    private fun sourcePoint(x:Double,y:Double):Pair<Float,Float> {
        var p=0f to 0f
        main { p=(surface.source.leftPad+x*surface.source.factor).toFloat() to (surface.source.topPad+y*surface.source.factor).toFloat() }
        return p
    }
    @Test fun actualSourceDragAndCornerResizeKeepIndependentZoomAndEvidence() {
        click("lab_show"); val first=state(); val page=requireNotNull(first.frame).page
        val center=sourcePoint((first.roi.left+first.roi.right)/2,(first.roi.top+first.roi.bottom)/2)
        drag(view("lab_source"),center.first,center.second,28f,18f)
        val moved=state(); assertTrue(moved.roi.left>first.roi.left); assertTrue(moved.roi.top>first.roi.top)
        assertEquals(first.roi.width,moved.roi.width,.001); assertEquals(first.scale,moved.scale,0.0)
        val corner=sourcePoint(moved.roi.right,moved.roi.top)
        drag(view("lab_source"),corner.first,corner.second,18f,-15f)
        val resized=state(); assertTrue(resized.roi.width>moved.roi.width); assertTrue(resized.roi.height>moved.roi.height)
        assertEquals(first.scale,resized.scale,0.0); assertSame(page,resized.frame!!.page); assertEquals(1,resized.loads)
        assertTrue(visibleText().contains("冲突，未选择答案")); assertTrue(visibleText().contains("〔空字符串候选〕"))
        screenshot("drag-resize")
    }
    @Test fun cornerAtTopRightEdgeRetainsFullTouchAreaInsideView() {
        click("lab_show"); val initial=state(); val v=view("lab_source")
        var factor=1.0; var density=1f
        main { factor=surface.source.factor; density=activity.resources.displayMetrics.density }
        // Give the upper-right resize room above the 48dp minimum before testing a decrease.
        val initialCorner=sourcePoint(initial.roi.right,initial.roi.top)
        drag(v,initialCorner.first,initialCorner.second,0f,-20*density)
        val first=state(); assertTrue(first.roi.height>initial.roi.height)
        val center=sourcePoint((first.roi.left+first.roi.right)/2,(first.roi.top+first.roi.bottom)/2)
        drag(v,center.first,center.second,((1200-first.roi.right)*factor).toFloat(),(-first.roi.top*factor).toFloat())
        val edge=state(); assertEquals(1200.0,edge.roi.right,.01); assertEquals(0.0,edge.roi.top,.01)
        // The 48dp target must shift inside the View at the page edge, not lose its outer half.
        val corner=sourcePoint(edge.roi.right,edge.roi.top)
        val x=(v.width-46*density).coerceAtLeast(1f)
        val y=corner.second.coerceAtLeast(0f)+12*density
        drag(v,x,y,-10*density,10*density)
        assertEquals(edge.roi.left,state().roi.left,.01)
        assertTrue(state().roi.width<edge.roi.width); assertTrue(state().roi.height<edge.roi.height)
        assertEquals(edge.scale,state().scale,0.0); assertEquals(1,state().loads)
    }
    @Test fun realSliderAndTwoPointerPinchSynchronizeAndMirrorPanDoesNotReload() {
        click("lab_show"); val roi=state().roi
        val bar=view("lab_slider")
        drag(bar,bar.width*.30f,bar.height/2f,bar.width*.15f,0f)
        val beforePinch=state(); assertTrue(abs(beforePinch.scale-2.0)>.1)
        val mirror=view("lab_mirror"); pinch(mirror)
        val afterPinch=state(); assertTrue(afterPinch.scale>beforePinch.scale)
        assertEquals(roi,afterPinch.roi); assertEquals(1,afterPinch.loads)
        main { assertTrue(abs(((afterPinch.scale-1)*100).toInt()-(bar as SeekBar).progress)<=1) }
        assertTrue(visibleText().contains("相对整页预览"))
        val beforePan=state()
        drag(mirror,mirror.width*.7f,mirror.height*.55f,-mirror.width*.25f,0f)
        assertTrue(state().panX>beforePan.panX); assertEquals(1,state().loads)
        assertSame(beforePan.frame!!.page,state().frame!!.page)
        screenshot("pinch-pan")
    }
    private fun pinch(v:View) {
        val down=SystemClock.uptimeMillis(); val cx=v.width/2f; val cy=v.height/2f
        fun send(action:Int,span:Float,count:Int=2,step:Int) {
            main {
                check(surface.isForeground() && v.isShown)
                val properties=Array(count) { index -> MotionEvent.PointerProperties().apply { id=index; toolType=MotionEvent.TOOL_TYPE_FINGER } }
                val coords=Array(count) { index -> MotionEvent.PointerCoords().apply {
                    x=cx+(if(index==0) -span/2 else span/2); y=cy; pressure=1f; size=.05f
                } }
                val e=MotionEvent.obtain(down,down+step*20L,action,count,properties,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                try { assertTrue(v.dispatchTouchEvent(e)) } finally { e.recycle() }
            }
        }
        send(MotionEvent.ACTION_DOWN,v.width*.28f,1,0)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),v.width*.28f,2,1)
        for(i in 1..14) send(MotionEvent.ACTION_MOVE,v.width*(.28f+i*.035f),2,i+1)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),v.width*.77f,2,16)
        send(MotionEvent.ACTION_UP,v.width*.77f,1,17)
        instrumentation.waitForIdleSync()
    }
    @Test fun fullContentMenuAndCollapsedReturnClearTextWithoutReload() {
        click("lab_show"); val before=state(); click("lab_collapse")
        assertNull(state().frame); assertFalse(visibleText().contains("冲突，未选择答案"))
        click("lab_collapsed_menu"); val menu=view("lab_menu"); val root=view("lab_root")
        main {
            assertEquals(root.width-root.paddingLeft-root.paddingRight,menu.width)
            assertEquals(root.height-root.paddingTop-root.paddingBottom,menu.height)
        }
        screenshot("menu")
        click("lab_close_menu"); assertEquals(RegionVisualSession.Mode.COLLAPSED,state().mode)
        click("lab_restore"); assertNull(state().frame); assertEquals(before.roi,state().roi)
        assertEquals(before.loads,state().loads); assertFalse(visibleText().contains("〔空字符串候选〕"))
        click("lab_show"); assertEquals(2,state().loads)
        click("lab_open_menu"); click("lab_stop"); assertNull(state().frame)
        assertEquals(RegionVisualSession.Mode.STOPPED,state().mode)
        click("lab_show"); assertNotNull(state().frame); assertEquals(3,state().loads)
    }
    @Test fun switchingToBlankAndBackNeverKeepsOldCandidateText() {
        click("lab_show"); assertTrue(visibleText().contains("成人單程"))
        click("lab_page"); assertNull(state().frame); assertFalse(visibleText().contains("冲突，未选择答案"))
        click("lab_show"); assertTrue(state().frame!!.page.rawCandidates.isEmpty())
        assertTrue(visibleText().contains("有效空白样例")); screenshot("blank")
        click("lab_page"); assertNull(state().frame); assertFalse(visibleText().contains("有效空白样例"))
        assertEquals(2,state().loads)
    }
    @Test fun ownSecondActivityBackgroundClearsAndResumeRequiresExplicitShow() {
        click("lab_show"); val second=launch(true); instrumentation.waitForIdleSync()
        assertNull(state().frame)
        main { second.finish() }; instrumentation.waitForIdleSync(); waitForeground()
        assertNull(state().frame); assertEquals(1,state().loads)
        assertFalse(visibleText().contains("冲突，未选择答案"))
        click("lab_show"); assertNotNull(state().frame)
    }
    @Test fun actualMonotonicSixtySecondTimerRemovesDisplayedCandidates() {
        click("lab_show"); val acquired=state().frame!!.metadata.acquiredAtMillis
        screenshot("initial-conflict")
        // No injected clock or manual refresh: the real surface timer must withdraw all results.
        while(SystemClock.elapsedRealtime()-acquired<60_400) SystemClock.sleep(250)
        instrumentation.waitForIdleSync()
        assertNull(state().frame); assertEquals(1,state().loads)
        assertTrue(visibleText().contains("展示已失效")); assertFalse(visibleText().contains("冲突，未选择答案"))
        screenshot("expired")
    }
    private fun screenshot(name:String) {
        waitForeground()
        val pos=IntArray(2); var w=0; var h=0
        main {
            check(surface.isForeground())
            val root=surface.getView(); root.getLocationOnScreen(pos)
            pos[0]+=root.paddingLeft; pos[1]+=root.paddingTop
            w=root.width-root.paddingLeft-root.paddingRight; h=root.height-root.paddingTop-root.paddingBottom
        }
        val screen=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            // Persist only our foreground fixture content; system bars/other app pixels are excluded.
            val crop=Bitmap.createBitmap(screen,pos[0],pos[1],w.coerceAtMost(screen.width-pos[0]),h.coerceAtMost(screen.height-pos[1]))
            try {
                val directory=File(instrumentation.targetContext.filesDir,"region-visual-ui").apply { mkdirs() }
                File(directory,"$name.png").outputStream().use { assertTrue(crop.compress(Bitmap.CompressFormat.PNG,100,it)) }
            } finally { if(crop!==screen) crop.recycle() }
        } finally { screen.recycle() }
    }
}
