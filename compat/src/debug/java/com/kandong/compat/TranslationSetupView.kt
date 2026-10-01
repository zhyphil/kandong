package com.kandong.compat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup

/** A single explicit start covers this page's OCR and DeepL request. No preview step. */
internal object TranslationSetupView {
    const val DISCLOSURE_VERSION="deepl-cloudflare-direct-v3"
    fun body(context: Context, initialLanguage: String, start: (String)->Unit, close: ()->Unit): LinearLayout {
        fun dp(n:Int)=CompatUi.dp(context,n)
        var selected=if(initialLanguage=="FR") "FR" else "EN"
        val root=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(24),dp(20),dp(24),dp(24))
            setBackgroundColor(CompatUi.background)
        }
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(CompatUi.text(context,"翻译成中文",26f).apply { setTypeface(typeface,Typeface.BOLD) },
            LinearLayout.LayoutParams(0,-2,1f))
        header.addView(IconView(context,LineIcon.CLOSE,CompatUi.ink).apply {
            contentDescription="关闭翻译"; isFocusable=true; background=CompatUi.ripple(context,CompatUi.soft)
            setOnClickListener { close() }
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        root.addView(header)
        root.addView(CompatUi.text(context,"看整屏上下文，在放大镜里读译文。",17f,CompatUi.muted))
        root.addView(CompatUi.text(context,"原文语言",17f).apply { setPadding(0,dp(24),0,dp(12)) })
        val languages=RadioGroup(context).apply { orientation=RadioGroup.HORIZONTAL }
        listOf("英语" to "EN","法语" to "FR").forEachIndexed { index,(title,value) ->
            val radio=RadioButton(context).apply {
                id=View.generateViewId(); text=title; textSize=20f
                setTextColor(CompatUi.ink); buttonTintList=ColorStateList.valueOf(CompatUi.teal)
                background=CompatUi.ripple(context,CompatUi.soft)
                minHeight=dp(64); setPadding(dp(12),dp(8),dp(12),dp(8))
                setOnCheckedChangeListener { _,checked -> if(checked) selected=value }
            }
            languages.addView(radio,LinearLayout.LayoutParams(0,-2,1f).apply {
                if(index==0) marginEnd=dp(8) else marginStart=dp(8)
            })
            if(value==selected) radio.isChecked=true
        }
        root.addView(languages)
        root.addView(CompatUi.text(context,"DeepL 翻译 · 需联网",16f,CompatUi.muted).apply { setPadding(0,dp(20),0,dp(4)) })
        root.addView(CompatUi.text(context,
            "点“开始翻译”即同意将本页整屏可读文字经 Cloudflare 中转发送给 DeepL（含红框外）。仅用于公开页面；图片不上传。DeepL 可能保留文字用于改进服务。",15f,CompatUi.muted))
        val button=CompatUi.button(context,"开始翻译",true) { start(selected) }
        // A fast second tap cannot create another capture or request.
        button.setOnClickListener { if(button.isEnabled) { button.isEnabled=false; start(selected) } }
        root.addView(button,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
        val details=CompatUi.text(context,TranslationFeature.PROVIDER_DISCLOSURE,15f,CompatUi.muted).apply { visibility=View.GONE }
        val disclosure=CompatUi.text(context,"隐私说明",16f,CompatUi.teal).apply {
            contentDescription="隐私说明"; minHeight=dp(48); gravity=Gravity.CENTER_VERTICAL
            isFocusable=true; background=CompatUi.ripple(context,android.graphics.Color.TRANSPARENT)
            setOnClickListener { details.visibility=if(details.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        }
        root.addView(disclosure,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        root.addView(details)
        return root
    }
}
