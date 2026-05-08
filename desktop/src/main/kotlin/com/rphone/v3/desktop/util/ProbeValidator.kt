package com.rphone.v3.desktop.util

import java.util.logging.Logger

/**
 * ProbeValidator — Validates probe mode commands and data
 * Matches APK ProbeValidator behavior
 */
object ProbeValidator {
    private val logger = Logger.getLogger(ProbeValidator::class.java.name)

    enum class ProbeMode {
        VOLT, DIODE, OHM, AUTO
    }

    data class ProbeData(
        val mode: ProbeMode,
        val value: Double,
        val unit: String,
        val isValid: Boolean,
        val errorMessage: String = ""
    )

    /**
     * Validate probe mode command before sending
     */
    fun validateModeCommand(mode: ProbeMode): Pair<Boolean, String> {
        return when (mode) {
            ProbeMode.VOLT -> Pair(true, "PROBE_MODE:VOLT")
            ProbeMode.DIODE -> Pair(true, "PROBE_MODE:DIODE")
            ProbeMode.OHM -> Pair(true, "PROBE_MODE:OHM")
            ProbeMode.AUTO -> Pair(true, "PROBE_MODE:AUTO")
            else -> Pair(false, "Unknown mode")
        }
    }

    /**
     * Parse probe data response
     */
    fun parseProbeData(response: String, currentMode: ProbeMode): ProbeData {
        return try {
            val parts = response.split(":")
            if (parts.size < 2) {
                return ProbeData(
                    mode = currentMode,
                    value = 0.0,
                    unit = "?",
                    isValid = false,
                    errorMessage = "Invalid response format"
                )
            }

            val valueStr = parts[1].trim()
            val value = valueStr.toDoubleOrNull() ?: 0.0

            val (unit, isValid) = when (currentMode) {
                ProbeMode.VOLT -> Pair("V", value >= 0 && value <= 1000)
                ProbeMode.DIODE -> Pair("Ω", value >= 0 && value <= 5.0)
                ProbeMode.OHM -> Pair("Ω", value >= 0 && value <= 20000000)
                ProbeMode.AUTO -> Pair("?", true)
            }

            ProbeData(
                mode = currentMode,
                value = value,
                unit = unit,
                isValid = isValid,
                errorMessage = if (isValid) "" else "Value out of range"
            )
        } catch (e: Exception) {
            logger.severe("parseProbeData error: ${e.message}")
            ProbeData(
                mode = currentMode,
                value = 0.0,
                unit = "?",
                isValid = false,
                errorMessage = e.message ?: "Parse error"
            )
        }
    }

    /**
     * Validate data point before recording
     */
    fun validateDataPoint(value: Double, mode: ProbeMode): Boolean {
        return when (mode) {
            ProbeMode.VOLT -> value >= 0 && value <= 1000
            ProbeMode.DIODE -> value >= 0 && value <= 5.0
            ProbeMode.OHM -> value >= 0 && value <= 20000000
            ProbeMode.AUTO -> true
        }
    }

    /**
     * Format value for display
     */
    fun formatValue(value: Double, mode: ProbeMode): String {
        return when {
            value < 0.001 -> "0"
            value.isInfinite() || value.isNaN() -> "OL"
            mode == ProbeMode.VOLT -> String.format("%.2f V", value)
            mode == ProbeMode.DIODE -> String.format("%.2f Ω", value)
            mode == ProbeMode.OHM -> {
                when {
                    value > 1_000_000 -> String.format("%.2f MΩ", value / 1_000_000)
                    value > 1_000 -> String.format("%.2f kΩ", value / 1_000)
                    else -> String.format("%.2f Ω", value)
                }
            }
            else -> String.format("%.2f", value)
        }
    }
}
