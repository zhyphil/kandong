package com.kandong.compat

import android.content.Context
import android.widget.LinearLayout

/** Complete in-memory strings only; no truncation, inference, or network entry point. */
internal object SnapshotTextDetails {
    fun body(context: Context,entries: List<SnapshotTextContent.Entry>,onClose: ()->Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(CompatUi.dp(context,20),CompatUi.dp(context,12),CompatUi.dp(context,20),CompatUi.dp(context,24))
            setBackgroundColor(CompatUi.background)
            isSaveEnabled=false
            addView(CompatUi.button(context,"返回放大镜",true,onClose))
            addView(CompatUi.text(context,"本次快照 · 红框内全文",22f))
            addView(CompatUi.text(context,"以下包含与红框相交的完整文字块，按页面位置排列。识别或机译可能有误；跳过的文字仍以原图为准。",16f))
            entries.forEachIndexed { index,entry ->
                fun text(value: String,size: Float=20f) {
                    addView(CompatUi.text(context,value,size).apply { isSaveEnabled=false })
                }
                text("${index+1} · 识别原文",16f)
                text(entry.source)
                if(entry.translation!=null) {
                    text("译文 · 待核对",16f)
                    text(entry.translation)
                }
            }
            addView(CompatUi.button(context,"返回放大镜",true,onClose))
        }
}
