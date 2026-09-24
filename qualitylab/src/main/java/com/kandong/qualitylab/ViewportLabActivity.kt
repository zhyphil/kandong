package com.kandong.qualitylab

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.TextView
import com.kandong.graphics.GpuC
import com.kandong.graphics.GpuViewport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Future
import kotlin.math.*

/** Fixed synthetic viewport checks only. No input extras, capture, permissions or external pixels. */
class ViewportLabActivity : Activity() {
    private var job: Future<*>? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label = TextView(this).apply { text="清晰增强视窗验证中"; textSize=24f; setPadding(30,80,30,30) }
        setContentView(label)
        val id=java.util.UUID.randomUUID().toString()
        fun report(value: JSONObject) {
            val temporary=File(filesDir,"viewport-report.tmp")
            temporary.writeText(value.put("runId",id).toString(2))
            check(temporary.renameTo(File(filesDir,"viewport-report.json")))
        }
        job=GpuLabTasks.submit {
            try {
                report(JSONObject().put("state","running").put("ready",false))
                val cases=JSONArray(); val timings=JSONArray()
                val detail=JSONObject()
                GpuC().use { gpu ->
                    detail.put("renderer",gpu.renderer).put("shaderPath",gpu.arithmeticMode)
                    for ((w,h) in listOf(31 to 17,1 to 19,19 to 1,1 to 1)) {
                        val pixels=IntArray(w*h) { i ->
                            val x=i%w; val y=i/w
                            0xff000000.toInt() or (((x*17+y*31)%256) shl 16) or
                                (((x*29+y*7)%256) shl 8) or ((x*3+y*47)%256)
                        }
                        val input=GpuC.Input.pack(pixels,w,h)
                        for (scale in listOf(1f,1.01f,1.37f,1.99f,2f,2.64f,3f,4.99f,5f)) {
                            for ((tx,ty) in listOf(0f to 0f, -7.25f to -3.5f, 9.25f to 8.75f,
                                (47-w*scale)/2f to (29-h*scale)/2f)) {
                                val v=GpuViewport(47,29,scale,tx,ty)
                                val expected=reference(pixels,w,h,v)
                                val output=gpu.renderViewport(input,v).bitmap
                                val actual=IntArray(v.width*v.height)
                                output.getPixels(actual,0,v.width,0,0,v.width,v.height); output.recycle()
                                val metrics=GpuLabChecks.compare(expected,actual,v.width,v.height,v.width,v.height,false)
                                cases.put(JSONObject().put("source","${w}x$h").put("scale",scale)
                                    .put("translation",JSONArray(listOf(tx,ty))).put("passed",metrics.passed)
                                    .put("alpha255",metrics.alpha255).put("maxError",metrics.channels.maxOf { it.max })
                                    .put("maxMean",metrics.channels.maxOf { it.mean }))
                            }
                        }
                    }
                    // Target-size synthetic input/output. Never allocate a source*scale-sized result.
                    for ((w,h) in listOf(505 to 209,1096 to 384)) {
                        val bytes=ByteArray(w*h*4) { i -> if (i%4==3) 255.toByte() else ((i/4+i%4*19)%256).toByte() }
                        val input=GpuC.Input.fromRgba(bytes,w,h)
                        for(scale in listOf(2f,5f)) {
                            val v=GpuViewport(1096,384,scale,0f,0f)
                            val samples=mutableListOf<Double>()
                            repeat(12) { i ->
                                val start=System.nanoTime()
                                val output=gpu.renderViewport(input,v)
                                check(output.bitmap.width==1096 && output.bitmap.height==384)
                                output.bitmap.recycle()
                                if(i>=2) samples += (System.nanoTime()-start)/1e6
                            }
                            val stats=GpuLabChecks.stats(samples)
                            timings.put(JSONObject().put("source","${w}x$h").put("scale",scale)
                                .put("viewport","1096x384").put("medianMs",stats.median).put("p95Ms",stats.p95)
                                .put("samplesMs",JSONArray(samples)))
                        }
                    }
                }
                val reuse=ViewportReuseChecks.run()
                val passed=(0 until cases.length()).all { cases.getJSONObject(it).getBoolean("passed") } && reuse.getBoolean("passed")
                report(JSONObject().put("state","completed").put("ready",true).put("passed",passed)
                    .put("cases",cases).put("timings",timings).put("device",detail).put("liveReuse",reuse)
                    .put("scope","Synthetic viewport math and target-size render only; not actual capture FPS or energy"))
                runOnUiThread { label.text=if(passed) "视窗验证通过：${cases.length()}项" else "视窗验证未通过，请检查数值报告" }
            } catch (error: Exception) {
                report(JSONObject().put("state","failed").put("ready",false).put("error",error.javaClass.simpleName))
                runOnUiThread { label.text="验证未完成" }
            }
        }
    }
    override fun onStop() { job?.cancel(true); super.onStop() }

    /** Independent double-precision reference: published kernel, literal source sharpening. */
    private fun reference(pixels: IntArray, w: Int, h: Int, v: GpuViewport): IntArray {
        val sharpened=if(v.scale==1f) pixels else IntArray(pixels.size) { i ->
            val x=i%w; val y=i/w; var result=0xff000000.toInt()
            for(shift in listOf(16,8,0)) {
                var blur=0.0
                for(dy in -1..1) for(dx in -1..1) {
                    val p=pixels[(y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1)]
                    blur += ((p ushr shift) and 255) * (if(dx==0) 2 else 1) * (if(dy==0) 2 else 1)
                }
                val value=(pixels[i] ushr shift) and 255
                result=result or ((value+((value-blur/16)*.2).coerceIn(-12.0,12.0)).roundToInt().coerceIn(0,255) shl shift)
            }; result
        }
        fun kernel(value: Double): Double {
            val x=abs(value)
            return when { x<1 -> (7*x*x*x-12*x*x+16.0/3)/6
                x<2 -> (-7.0/3*x*x*x+12*x*x-20*x+32.0/3)/6; else -> 0.0 }
        }
        return IntArray(v.width*v.height) { i ->
            if(i%4096==0) GpuLabChecks.cancellation()
            val xx=(i%v.width+.5-v.translateX)/v.scale
            val yy=(i/v.width+.5-v.translateY)/v.scale
            if(xx<0 || yy<0 || xx>=w || yy>=h) 0xffffffff.toInt()
            else {
                val sx=xx-.5; val sy=yy-.5
                val bx=floor(sx).toInt(); val by=floor(sy).toInt()
                var result=0xff000000.toInt()
                for(shift in listOf(16,8,0)) {
                    var sum=0.0; var total=0.0
                    val range=if(v.scale==1f) 0..1 else -1..2
                    for(dy in range) for(dx in range) {
                        val wx=if(v.scale==1f) if(dx==0) 1-(sx-bx) else sx-bx else kernel(sx-bx-dx)
                        val wy=if(v.scale==1f) if(dy==0) 1-(sy-by) else sy-by else kernel(sy-by-dy)
                        val weight=wx*wy
                        val p=sharpened[(by+dy).coerceIn(0,h-1)*w+(bx+dx).coerceIn(0,w-1)]
                        sum += ((p ushr shift) and 255)*weight; total += weight
                    }
                    result=result or ((sum/total).roundToInt().coerceIn(0,255) shl shift)
                }; result
            }
        }
    }
}
