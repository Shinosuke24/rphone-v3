package com.rphone.v3.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object NumberFormatter {

    private val symbols = DecimalFormatSymbols(Locale.US)
    private val decimalFormat = DecimalFormat("0.00", symbols)
    private val decimalFormat3 = DecimalFormat("0.000", symbols)
    private val integerFormat = DecimalFormat("0", symbols)

    fun format(value: Double, decimals: Int = 2): String {
        return when (decimals) {
            2 -> decimalFormat.format(value)
            3 -> decimalFormat3.format(value)
            0 -> integerFormat.format(value)
            else -> {
                val pattern = if (decimals > 0) "0." + "0".repeat(decimals) else "0"
                DecimalFormat(pattern, symbols).format(value)
            }
        }
    }

    fun parse(value: String): Double {
        return value.trim().replace(",", ".").toDoubleOrNull() ?: 0.0
    }

    // FIX: handle nilai 0, negatif, dan magnitude kecil akibat noise INA3221
    // Sebelumnya: nilai -0.0007V masuk cabang else → -699.99µV → display "-699999.99µV"
    // Sekarang: nilai abs < 0.001V dianggap tidak valid → return "--"
    fun formatVoltage(voltage: Float): String {
        // Nilai 0 atau noise (abs < 1mV) → tidak valid, tampilkan "--"
        if (voltage == 0f || Math.abs(voltage) < 0.001f) return "--"

        val negative = voltage < 0f
        val prefix   = if (negative) "-" else ""
        val v        = Math.abs(voltage.toDouble())

        return when {
            v >= 1000.0  -> prefix + format(v / 1000.0, 2) + "kV"
            v >= 1.0     -> prefix + format(v, 3) + "V"
            v >= 0.001   -> prefix + format(v * 1000.0, 2) + "mV"
            else         -> prefix + format(v * 1000000.0, 2) + "µV"
        }
    }

    fun formatResistance(resistance: Float): String {
        val r = resistance.toDouble()
        return when {
            r >= 1000000 -> format(r / 1000000, 2) + "MΩ"
            r >= 1000 -> format(r / 1000, 2) + "kΩ"
            else -> format(r, 2) + "Ω"
        }
    }

    fun formatVdrop(vdrop: Float): String {
        return format(vdrop.toDouble(), 3) + "V"
    }
}