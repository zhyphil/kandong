package com.kandong.compat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.res.Configuration
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.kandong.liveocr.OcrBlock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Authored strings only. No screen capture, OCR, network or phone permissions. */
@RunWith(AndroidJUnit4::class)
class SnapshotTextLayoutTest {
    private val longText="早餐不包含在房费内，请在上午十一点前退房。".repeat(4)

    @Test fun overlappingToBackgroundCannotEraseTheBeginningOfEnjoy() {
        // Tiny top-coordinate variation places the RIGHT word first in OCR reading order.
        val enjoy=OcrBlock("enjoy","enjoy",80,48,260,80,0.9)
        val to=OcrBlock("to","to",20,50,100,82,0.9)
        val reference=Bitmap.createBitmap(320,140,Bitmap.Config.ARGB_8888)
        val actual=Bitmap.createBitmap(320,140,Bitmap.Config.ARGB_8888)
        try {
            reference.eraseColor(Color.LTGRAY); actual.eraseColor(Color.LTGRAY)
            val crop=Box(0,0,320,140)
            SnapshotTextPainter(listOf(enjoy),emptyMap()).draw(Canvas(reference),crop)
            SnapshotTextPainter(listOf(enjoy,to),emptyMap()).draw(Canvas(actual),crop)
            for(y in 48 until 80) for(x in 80 until 260) {
                assertEquals("The next word must remain intact at $x,$y",reference.getPixel(x,y),actual.getPixel(x,y))
            }
        } finally { reference.recycle(); actual.recycle() }
    }

    @Test fun longTranslationCannotErasePixelsBelowItsSourceBox() {
        val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            val canvas=Canvas(bitmap)
            canvas.translate(30f,20f)
            SnapshotTextLayout(longText,180,32).draw(canvas)
            for(y in 52 until 320) for(x in 0 until 320) {
                assertEquals("Neighbor at $x,$y must remain unchanged",Color.MAGENTA,bitmap.getPixel(x,y))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun overflowingTextHasAnExplicitEllipsisAndFitsItsHeight() {
        val result=SnapshotTextLayout(longText,180,32)
        assertTrue(result.abbreviated)
        assertTrue("Layout must not extend below source box",result.layout.height<=32)
        assertTrue("Hidden words must be disclosed with ellipsis",(0 until result.layout.lineCount).any { result.layout.getEllipsisCount(it)>0 })
    }

    @Test fun shortFrenchAndChineseStayCompleteAndTinyBoxesRetainOriginalPixels() {
        listOf("Départ à 11 h.","退房时间11点").forEach { text ->
            val result=SnapshotTextLayout(text,400,48)
            assertFalse(result.abbreviated)
            assertEquals(text,result.layout.text.toString())
            assertEquals(0,result.layout.getEllipsisCount(0))
        }
        val tiny=SnapshotTextLayout(longText,100,3)
        assertFalse(tiny.drawable); assertTrue(tiny.abbreviated)
        val bitmap=Bitmap.createBitmap(120,30,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA); tiny.draw(Canvas(bitmap))
            for(y in 0 until 30) for(x in 0 until 120) assertEquals(Color.MAGENTA,bitmap.getPixel(x,y))
        } finally { bitmap.recycle() }
    }

    @Test fun sourceBoundsRemainClippedThroughZoomAndPan() {
        for(scale in listOf(1f,2f,5f)) {
            val bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.MAGENTA)
                val canvas=Canvas(bitmap)
                // The live mirror applies the same viewport transform before source-local layout.
                canvas.translate(-20f,-10f); canvas.scale(scale,scale); canvas.translate(30f,20f)
                SnapshotTextLayout(longText,80,32).draw(canvas)
                val left=(-20+30*scale).toInt(); val right=(-20+110*scale).toInt()
                val top=(-10+20*scale).toInt(); val bottom=(-10+52*scale).toInt()
                for(y in 0 until 400) for(x in 0 until 600) {
                    if(x !in left until right || y !in top until bottom) assertEquals(Color.MAGENTA,bitmap.getPixel(x,y))
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun detailsPreserveLongTextWithLargeFontsAndReturnWithoutChangingEntries() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val config=Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale=1.5f }
            val context=instrumentation.targetContext.createConfigurationContext(config)
            val source="Breakfast is not included. Check out before 11:00. ".repeat(8)
            val entries=listOf(SnapshotTextContent.Entry("one",source,longText))
            var returned=0
            val body=SnapshotTextDetails.body(context,entries) { returned++ }
            val texts=(0 until body.childCount).mapNotNull { body.getChildAt(it) as? TextView }
            for(value in listOf(source,longText)) {
                val view=texts.single { it.text.toString()==value }
                assertNull(view.ellipsize)
                assertFalse(view.isSaveEnabled)
            }
            val buttons=texts.filterIsInstance<Button>()
            assertEquals(2,buttons.size); buttons.forEach { it.performClick() }
            assertEquals(2,returned); assertEquals(source,entries.single().source)
            body.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            body.layout(0,0,body.measuredWidth,body.measuredHeight)
            assertTrue(body.height>1000)
            assertTrue(buttons.all { it.height>=CompatUi.dp(context,48) })
        }
    }

    @Test fun renderAuthoredPreviewForVisualReview() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val bitmap=Bitmap.createBitmap(720,520,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(bitmap); canvas.drawColor(Color.rgb(236,244,240))
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.DKGRAY; textSize=28f }
                canvas.drawText("Synthetic layout check · 2×",24f,42f,paint)
                canvas.save(); canvas.translate(24f,80f); canvas.scale(2f,2f)
                SnapshotTextLayout(longText,260,42).draw(canvas)
                canvas.translate(0f,58f); SnapshotTextLayout("退房时间 11:00",260,42).draw(canvas)
                canvas.restore()
                canvas.drawText("Both rows stay inside their original bounds.",24f,330f,paint)
                context.cacheDir.resolve("snapshot-layout-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { bitmap.recycle() }
            val body=SnapshotTextDetails.body(context,listOf(SnapshotTextContent.Entry("one",
                "Breakfast is not included in the room price. Please check out before 11:00.",
                "房费不含早餐。请在上午11点前退房。"))) { }
            body.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            body.layout(0,0,body.measuredWidth,body.measuredHeight)
            val details=Bitmap.createBitmap(body.width,body.height,Bitmap.Config.ARGB_8888)
            try {
                body.draw(Canvas(details))
                context.cacheDir.resolve("snapshot-details-preview.png").outputStream().use { details.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { details.recycle() }
        }
    }
}
