package com.rphone.v3.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    private const val PREF_THEME_MODE = "theme_mode"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_AUTO = "auto"

    fun setTheme(context: Context, mode: String) {
        val prefs = context.getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_THEME_MODE, mode).apply()
        applyTheme(mode)
    }

    fun getTheme(context: Context): String {
        val prefs = context.getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_THEME_MODE, THEME_AUTO) ?: THEME_AUTO
    }

    fun applyTheme(mode: String) {
        when (mode) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_AUTO -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun toggleTheme(context: Context) {
        val currentTheme = getTheme(context)
        val newTheme = when (currentTheme) {
            THEME_LIGHT -> THEME_DARK
            THEME_DARK -> THEME_AUTO
            else -> THEME_LIGHT
        }
        setTheme(context, newTheme)
    }

    fun getThemeDisplayName(mode: String): String {
        return when (mode) {
            THEME_LIGHT -> "Light Mode"
            THEME_DARK -> "Dark Mode"
            else -> "Auto Mode"
        }
    }
}
