package com.kandong.compat

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kandong.liveocr.LiveOcrEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.Locale

/** The current app's authored demo view -> actual offline OCR -> full-text selection.
 * Draws ONLY this test-owned Activity, never captures other apps or calls a provider.
 * This checks translation inputs, not translated meaning or MediaProjection permission.
 */
@RunWith(AndroidJUnit4::class)
class LiveDemoOcrTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()

    @Test fun englishHotelKeepsCheckInBreakfastNegationAndCheckOut() = verify("EN", listOf(
        "after 16:30", "breakfast is not included in the room price", "before 11:00"))

    @Test fun frenchHotelKeepsCheckInBreakfastNegationAndCheckOut() = verify("FR", listOf(
        "arrivée après 16\\s*h\\s*30", "n'est pas compris dans le prix de la chambre", "départ avant 11 h"))

    private fun verify(language: String, facts: List<String>) {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,TranslationDemoActivity::class.java)
            .putExtra("language",language).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TranslationDemoActivity
        var bitmap: Bitmap?=null
        var rgba: ByteArray?=null
        try {
            instrumentation.waitForIdleSync()
            val deadline=SystemClock.elapsedRealtime()+5000
            while(bitmap==null && SystemClock.elapsedRealtime()<deadline) {
                instrumentation.runOnMainSync {
                    val view=activity.window.decorView
                    if(view.width>0 && view.height>0 && view.isLaidOut) {
                        bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).apply {
                            density=Bitmap.DENSITY_NONE
                            view.draw(Canvas(this))
                        }
                    }
                }
                if(bitmap==null) SystemClock.sleep(50)
            }
            val rendered=requireNotNull(bitmap) { "Demo did not lay out" }
            val buffer=ByteBuffer.allocate(rendered.width*rendered.height*4)
            rendered.copyPixelsToBuffer(buffer); rgba=buffer.array()
            val page=LiveOcrEngine(instrumentation.targetContext).recognize(rgba,rendered.width,rendered.height,language) { true }
            val entries=SnapshotTextContent.select(page.blocks,emptyMap(),Box(0,0,rendered.width,rendered.height))
            assertTrue("Actual OCR must return text",entries.isNotEmpty())
            assertTrue("No supplied or invented translation",entries.all { it.translation==null })
            fun normalized(value: String)=value.lowercase(Locale.ROOT).replace('’','\'').replace(Regex("\\s+")," ").trim()
            val recognized=normalized(page.blocks.joinToString(" ") { it.text })
            assertEquals("Full text must preserve the same order as the translation input",recognized,
                normalized(entries.joinToString(" ") { it.source }))
            // French time notation permits 16h30 / 16 h 30. Keep every digit,
            // accent, negation and direction word exact; do not use fuzzy matching.
            val missing=facts.mapIndexedNotNull { index,fact -> if(Regex(normalized(fact)).containsMatchIn(recognized)) null else index+1 }
            val orderDiagnostic=if(language=="FR" && missing.isNotEmpty()) {
                listOf("arrivée","après").mapIndexed { index,term ->
                    val matches=page.blocks.filter { term in normalized(it.text) }
                    "token${index+1}:index=${recognized.indexOf(term)},bounds="+
                        matches.joinToString { "${it.left},${it.top},${it.right},${it.bottom}" }
                }.joinToString(";")
            } else "none"
            instrumentation.sendStatus(0,Bundle().apply {
                putString("demoLanguage",language)
                putInt("recognizedBlocks",page.blocks.size)
                putInt("skippedBlocks",page.unreadable.size)
                putInt("matchedCriticalFacts",facts.size-missing.size)
            })
            // Report only fact indices/counts, never page content. Fixed source is in TranslationDemoActivity.
            assertEquals("$language missing critical input facts (1=check-in,2=breakfast negation,3=check-out); " +
                "recognized=${page.blocks.size}, skipped=${page.unreadable.size}; $orderDiagnostic",emptyList<Int>(),missing)
        } finally {
            rgba?.fill(0); bitmap?.recycle()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
