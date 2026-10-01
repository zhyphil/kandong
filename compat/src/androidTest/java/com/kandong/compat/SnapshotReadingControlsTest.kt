package com.kandong.compat

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic bitmaps only; no screen sharing, OCR, provider call or phone configuration. */
@RunWith(AndroidJUnit4::class)
class SnapshotReadingControlsTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()

    @Test fun explicitLiveActionClearsRetainedImageAndHasReadableLargeTextTouchTarget() {
        instrumentation.runOnMainSync {
            for(scale in listOf(1f,1.5f)) {
                val config=Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale=scale }
                val context=instrumentation.targetContext.createConfigurationContext(config)
                val state=LiveTranslationState<Bitmap> { it.recycle() }
                val old=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
                try {
                    val token=state.begin(); state.captured(token,10,old); state.complete(token,20)
                    val button=SnapshotReadingControls.liveButton(context,state::invalidate)
                    button.measure(View.MeasureSpec.makeMeasureSpec(CompatUi.dp(context,64),View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(CompatUi.dp(context,48),View.MeasureSpec.EXACTLY))
                    button.layout(0,0,button.measuredWidth,button.measuredHeight)
                    assertEquals("实时",button.text.toString())
                    assertTrue(button.contentDescription.contains("实时放大镜"))
                    assertTrue(button.measuredHeight>=CompatUi.dp(context,48))
                    assertTrue(button.paint.measureText(button.text.toString())<=button.width-button.paddingLeft-button.paddingRight)
                    assertSame(old,state.displayed); assertFalse(old.isRecycled)
                    button.performClick()
                    assertNull(state.displayed); assertTrue(old.isRecycled)
                } finally { state.invalidate(); if(!old.isRecycled) old.recycle() }
            }
        }
    }

    @Test fun cancelledUpdateLeavesDrawableOldPixelsAndSuccessfulUpdateReleasesThem() {
        instrumentation.runOnMainSync {
            val state=LiveTranslationState<Bitmap> { it.recycle() }
            val old=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val draft=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val next=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val target=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            try {
                val first=state.begin(); state.captured(first,10,old); state.complete(first,20)
                val failed=state.begin(); state.captured(failed,500_000,draft)
                assertFalse(state.complete(failed,560_000)); state.cancel()
                assertTrue(draft.isRecycled); assertFalse(old.isRecycled)
                Canvas(target).drawBitmap(state.displayed!!,0f,0f,null)
                assertEquals(Color.RED,target.getPixel(4,4))
                val success=state.begin(); state.captured(success,900_000,next); state.complete(success,900_010)
                assertTrue(old.isRecycled); assertFalse(next.isRecycled)
                Canvas(target).drawBitmap(state.displayed!!,0f,0f,null)
                assertEquals(Color.GREEN,target.getPixel(4,4))
            } finally {
                state.invalidate()
                listOf(old,draft,next,target).filterNot { it.isRecycled }.forEach { it.recycle() }
            }
        }
    }
}
