package com.kandong.compat

import com.kandong.liveocr.OcrBlock
import com.kandong.liveocr.OcrPage

/** One user start owns one OCR -> translation chain, on the existing worker. */
internal object DirectPageTranslation {
    data class Result(val page: OcrPage, val translations: Map<String,String>)

    fun run(
        language: String,
        recognize: () -> OcrPage,
        translate: (List<OcrBlock>,String) -> Map<String,String>,
        current: () -> Boolean,
        onRecognized: (OcrPage) -> Unit = {},
        onSending: () -> Unit = {}
    ): Result {
        require(language in listOf("EN","FR"))
        fun ensureCurrent() = check(current()) { "STALE" }
        ensureCurrent()
        val page=recognize()
        ensureCurrent()
        check(page.blocks.map { it.id }.distinct().size == page.blocks.size) { "OCR_FAILED" }
        check(!TranslationTextPolicy.sensitive(page.blocks.joinToString("\n") { it.text })) { "SENSITIVE_PAGE" }
        onRecognized(page)
        if(page.blocks.isEmpty()) return Result(page,emptyMap())
        ensureCurrent()
        onSending()
        ensureCurrent()
        val translated=translate(page.blocks,language)
        ensureCurrent()
        return Result(page,translated)
    }
}
