package com.rphone.v3.desktop.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Number formatting utilities — parity with APK com.rphone.v3.util.NumberFormatter
 */
object NumberFormatter {

    private val US_LOCALE = DecimalFormatSymbols(Locale.US)

    fun formatDouble(value: Double, digits: Int): String {
        return String.format(Locale.US, "%.${digits}f", value)
    }

    fun formatInt(value: Int): String {
        return value.toString()
    }

    fun formatCurrent(value: Double): String = formatDouble(value, 3) + " A"
    fun formatVoltage(value: Double): String = formatDouble(value, 3) + " V"
    fun formatPower(value: Double): String = formatDouble(value, 2) + " W"
    fun formatCapacity(value: Double): String = formatDouble(value, 1) + " mAh"
    fun formatOhm(value: Double): String = when {
        value >= 1_000_000.0 -> formatDouble(value / 1_000_000.0, 2) + "MΩ"
        value >= 1_000.0 -> formatDouble(value / 1_000.0, 3) + "KΩ"
        else -> formatDouble(value, 1) + "Ω"
    }

    fun parseDouble(str: String): Double? = str.trim().toDoubleOrNull()
    fun parseInt(str: String): Int? = str.trim().toIntOrNull()
}
