package com.pitchmonitor.util

import android.content.Context
import com.pitchmonitor.ui.ThemeMode

/** Tiny SharedPreferences wrapper for app settings. */
object Prefs {
    private const val FILE = "pitch_monitor_prefs"
    private const val KEY_THEME = "theme_mode"

    fun theme(ctx: Context): ThemeMode =
        ThemeMode.from(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_THEME, null))

    fun setTheme(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode.name).apply()
    }
}
