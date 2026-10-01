package com.kandong.compat

import android.content.Context
import android.graphics.Color
import android.widget.Button

/** Explicitly return to the live page without overloading the translate action. */
internal object SnapshotReadingControls {
    fun liveButton(context: Context, action: () -> Unit) = Button(context).apply {
        text="实时"; textSize=16f; isAllCaps=false
        contentDescription="切回实时放大镜并清除本次翻译结果"
        setTextColor(Color.WHITE); background=CompatUi.ripple(context,CompatUi.teal)
        minWidth=CompatUi.dp(context,64); minHeight=CompatUi.dp(context,48)
        setPadding(0,0,0,0)
        setOnClickListener { action() }
    }
}
