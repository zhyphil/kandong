package com.kandong.compat

import android.media.Image
import android.widget.FrameLayout

/** Release keeps the accepted magnifier local-only, without OCR or network code. */
internal class TranslationFeature(host: TranslationHost) {
    val enabled = false
    val label: String? = null
    val showing = false
    val active = false
    fun bind(layer: FrameLayout) { }
    fun tap() { }
    fun frame(image: Image): Boolean = false
    fun render(crop: Box, viewport: MagnifierViewport) { }
    fun pause() { }
    fun invalidate() { }
    fun close() { }
    fun diagnostics() = "translation=unavailable"
    companion object {
        const val AVAILABLE = false
        const val DISCLOSURE = ""
        const val PRIVACY = ""
    }
}
