package com.kandong.compat

import android.content.Context

internal object ClaritySettings {
    private const val FILE = "local_display_preferences"
    const val KEY = "clarity_enabled"
    fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean(KEY, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY, enabled).apply()
    }
}
