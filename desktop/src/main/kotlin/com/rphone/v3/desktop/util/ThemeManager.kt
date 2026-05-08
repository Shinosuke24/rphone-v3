package com.rphone.v3.desktop.util

import javafx.scene.paint.Color

/**
 * Theme and color management — parity with APK com.rphone.v3.util.ThemeManager
 */
object ThemeManager {

    // Core palette
    val background = Color.web("#050810")
    val surface = Color.web("#0D1423")
    val topBar = Color.web("#080C14")
    val border = Color.web("#131D2E")

    val textPrimary = Color.web("#E2E8F0")
    val textSecondary = Color.web("#94A3B8")
    val muted = Color.web("#64748B")

    // UI colors
    val green = Color.web("#10B981")
    val cyan = Color.web("#00D4FF")
    val purple = Color.web("#A78BFA")
    val amber = Color.web("#F59E0B")
    val red = Color.web("#EF4444")
    val blue = Color.web("#3B82F6")

    // Status colors
    val success = green
    val warning = amber
    val error = red
    val info = cyan

    fun toHex(color: Color): String {
        val r = (color.red * 255).toInt().coerceIn(0, 255)
        val g = (color.green * 255).toInt().coerceIn(0, 255)
        val b = (color.blue * 255).toInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X", r, g, b)
    }

    fun fromHex(hex: String): Color = Color.web(hex)
}
