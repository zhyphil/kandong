package com.kandong.compat

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Test only our setup view with callbacks; no capture permission, OCR or network. */
@RunWith(AndroidJUnit4::class)
class TranslationSetupViewTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun exactlyTwoLanguagesAndOneStartWithoutExtraConfirmation() {
        instrumentation.runOnMainSync {
            for(initial in listOf("EN","FR")) {
                val calls=mutableListOf<String>()
                val body=TranslationSetupView.body(instrumentation.targetContext,initial,{calls+=it},{})
                val all=views(body)
                val radios=all.filterIsInstance<RadioButton>()
                assertEquals(listOf("英语","法语"),radios.map { it.text.toString() })
                assertEquals(if(initial=="EN") "英语" else "法语",radios.single { it.isChecked }.text.toString())
                assertTrue(all.none { it is CheckBox })
                radios.single { it.text=="法语" }.performClick()
                assertTrue(calls.isEmpty())
                val start=all.filterIsInstance<Button>().single { it.text=="开始翻译" }
                start.performClick(); start.performClick()
                assertEquals(listOf("FR"),calls)
            }
        }
    }

    @Test fun privacyAndClosingNeverStartARequest() {
        instrumentation.runOnMainSync {
            var closed=0
            val body=TranslationSetupView.body(instrumentation.targetContext,"EN",{fail("Unexpected start")},{closed++})
            val all=views(body)
            val details=all.filterIsInstance<TextView>().single { it.text.toString()==TranslationFeature.PROVIDER_DISCLOSURE }
            assertEquals(View.GONE,details.visibility)
            all.single { it.contentDescription=="隐私说明" }.performClick()
            assertEquals(View.VISIBLE,details.visibility)
            all.single { it.contentDescription=="关闭翻译" }.performClick()
            assertEquals(1,closed)
        }
    }

    @Test fun largeTextAndNarrowScreenKeepControlsReadableAndRenderOwnedPreview() {
        instrumentation.runOnMainSync {
            for(scale in listOf(1f,1.5f)) {
                val config=Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale=scale }
                val context=instrumentation.targetContext.createConfigurationContext(config)
                val body=TranslationSetupView.body(context,"EN",{},{})
                val width=CompatUi.dp(context,320)
                body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                body.layout(0,0,body.measuredWidth,body.measuredHeight)
                views(body).filter { it.visibility==View.VISIBLE && (it is RadioButton || it is Button || it.contentDescription=="关闭翻译") }.forEach {
                    assertTrue(it.height>=CompatUi.dp(context,48)); assertTrue(it.width>=CompatUi.dp(context,48))
                }
                val bitmap=Bitmap.createBitmap(body.width,body.height,Bitmap.Config.ARGB_8888)
                try {
                    views(body).forEach { it.jumpDrawablesToCurrentState() }
                    body.draw(Canvas(bitmap))
                    context.cacheDir.resolve("translation-setup-${if(scale==1f) "normal" else "large"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                } finally { bitmap.recycle() }
            }
        }
    }
}
