package com.kandong.compat

import android.content.Context
import android.graphics.Rect
import android.view.View

/** Narrow lifecycle boundary; the release implementation never reads a full frame. */
internal interface TranslationHost {
    val context: Context
    val active: Boolean
    val screenWidth: Int
    val screenHeight: Int
    val safeBounds: Rect
    fun hideControls(hidden: Boolean)
    fun witness(view: View?, x: Int = 0, y: Int = 0, size: Int = 0)
    fun obscuredRects(): List<Rect>
    fun refreshTranslation()
}
