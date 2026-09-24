package com.kandong.ocrlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import java.security.MessageDigest

internal data class SyntheticCase(val id: String, val language: String, val source: String, val fontPx: Int)
internal data class RenderedCase(val bitmap: Bitmap, val lineCount: Int, val textHeight: Int)

internal object SyntheticFixtures {
    const val SHA256 = "1f638fe9922fc979db040cb1d12cbee17dc66d950e62ec7b2872fe5544cf6553"
    const val WIDTH = 640
    const val PADDING = 32
    const val FONT = "sans-serif / NORMAL"
    const val TOTAL = 37

    fun load(context: Context): List<SyntheticCase> {
        val bytes = context.assets.open("translation-cases.json").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(hash == SHA256) { "fixture_hash_mismatch" }
        val rows = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("cases")
        check(rows.length() == 12) { "fixture_count_mismatch" }
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                for (size in listOf(16, 24, 32)) {
                    add(SyntheticCase(row.getString("id"), row.getString("language"), row.getString("source"), size))
                }
            }
            add(SyntheticCase("blank-negative", "none", "", 24))
        }
    }

    fun render(case: SyntheticCase): RenderedCase {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = case.fontPx.toFloat() // Pixels, never sp/dp or device-scaled.
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isSubpixelText = true
        }
        val layout = StaticLayout.Builder.obtain(case.source, 0, case.source.length, paint, WIDTH - PADDING * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        val bitmap = Bitmap.createBitmap(WIDTH, layout.height + 2 * PADDING, Bitmap.Config.ARGB_8888)
        try {
            bitmap.density = Bitmap.DENSITY_NONE
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                save()
                translate(PADDING.toFloat(), PADDING.toFloat())
                layout.draw(this)
                restore()
            }
            return RenderedCase(bitmap, layout.lineCount, layout.height)
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }
}
