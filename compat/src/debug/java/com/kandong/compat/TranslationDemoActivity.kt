package com.kandong.compat

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView

/** Public authored text rendered NOW by Android, with no prepared translation or OCR result.
 * Used to try the same actual screen capture path as any other foreground app.
 */
class TranslationDemoActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language=intent.getStringExtra("language") ?: "EN"
        val content=when(language) {
            "FR" -> listOf("Hôtel des Jardins", "Votre séjour", "Arrivée après 16 h 30.",
                "Le petit-déjeuner n’est pas compris dans le prix de la chambre.", "Départ avant 11 h.",
                "Bagages", "Vous pouvez laisser vos bagages à la réception avant l’arrivée et après le départ.",
                "Informations utiles", "La réception est ouverte de 7 h à 22 h.", "La gare se trouve à cinq minutes à pied.")
            "ZH-HANS" -> listOf("花园酒店", "入住须知", "下午四点半后可以入住。", "房费不含早餐。", "请在上午十一点前退房。",
                "行李寄存", "入住前和退房后，都可以在前台寄存行李。", "实用信息", "前台服务时间为早上七点至晚上十点。", "步行五分钟即可到达火车站。")
            else -> listOf("Garden Hotel", "Your stay", "Check-in is after 16:30.",
                "Breakfast is not included in the room price.", "Please check out before 11:00.",
                "Luggage", "You can leave your luggage at reception before check-in and after check-out.",
                "Useful information", "Reception is open from 07:00 to 22:00.", "The railway station is a five-minute walk away.")
        }
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(24,32,24,100)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        content.forEachIndexed { index,value ->
            body.addView(CompatUi.text(this,value,if(index==0) 28f else 20f).apply { setPadding(12,18,12,18) })
        }
        val scroll=ScrollView(this).apply { addView(body); setBackgroundColor(android.graphics.Color.WHITE) }
        scroll.setOnApplyWindowInsetsListener { view,insets ->
            if(android.os.Build.VERSION.SDK_INT>=30) {
                val safe=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            } else {
                @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
    }
}
