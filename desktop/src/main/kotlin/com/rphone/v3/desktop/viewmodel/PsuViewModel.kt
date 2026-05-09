package com.rphone.v3.desktop.viewmodel

import javafx.application.Platform

/**
 * PSU mode ViewModel — parity with APK PsuViewModel
 * Handles PSU data parsing, PWM, OCP status, and relay commands
 */
class PsuViewModel {

    data class PsuData(
        val volt: Double = 0.0,
        val curr: Double = 0.0,
        val pwmEnabled: Boolean = false,
        val pwmDur: Int = 2000,
        val ocpStatus: String = "ON"  // ON, OFF, TRIP
    )

    var onDataUpdate: ((PsuData) -> Unit)? = null
    var onSendCommand: ((String) -> Unit)? = null

    private var psuPwmEnabled = false
    private var psuPwmDurationMs = 2000
    private var psuOcpStatus = "ON"
    private var capacityAccumMah = 0.0
    private var lastUpdateMs = 0L

    fun processJson(jsonText: String) {
        try {
            fun extractDouble(key: String): Double? = Regex("\"$key\"\\s*:\\s*([-+]?[0-9]*\\.?[0-9]+)").find(jsonText)?.groups?.get(1)?.value?.toDoubleOrNull()
            fun extractString(key: String): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(jsonText)?.groups?.get(1)?.value
            fun extractBoolean(key: String): Boolean? = Regex("\"$key\"\\s*:\\s*(true|false)").find(jsonText)?.groups?.get(1)?.value?.equals("true", ignoreCase = true)

            val mode = extractString("mode")?.uppercase()
            if (mode != null && mode != "PSU") return

            val volt = extractDouble("volt") ?: 0.0
            val curr = extractDouble("curr") ?: 0.0
            val pwmEnabled = extractBoolean("pwm_en") ?: false
            val pwmDur = extractDouble("pwm_dur")?.toInt() ?: 2000
            val ocpEvent = extractString("ocp")?.lowercase()
            val ocpEnabled = extractBoolean("ocp_en")
            val ocpTripped = extractBoolean("ocp")

            psuPwmEnabled = pwmEnabled
            psuPwmDurationMs = pwmDur
            psuOcpStatus = when {
                ocpTripped == true -> "TRIP"
                ocpEnabled == true -> "ON"
                ocpEnabled == false -> "OFF"
                else -> when (ocpEvent) {
                "trip" -> "TRIP"
                "reset" -> "ON"
                "auto_reset" -> "ON"
                "on" -> "ON"
                "off" -> "OFF"
                else -> psuOcpStatus
                }
            }

            val now = System.currentTimeMillis()
            if (lastUpdateMs > 0L) {
                val dtHours = (now - lastUpdateMs) / 3_600_000.0
                capacityAccumMah += curr * 1000.0 * dtHours
            }
            lastUpdateMs = now

            val data = PsuData(volt, curr, pwmEnabled, pwmDur, psuOcpStatus)
            Platform.runLater {
                onDataUpdate?.invoke(data)
            }
        } catch (_: Exception) {
            // swallow
        }
    }

    fun setPwm(enabled: Boolean) {
        psuPwmEnabled = enabled
        onSendCommand?.invoke(if (enabled) "PWM_ON" else "PWM_OFF")
    }

    fun setOcp(enabled: Boolean) {
        when {
            enabled -> {
                onSendCommand?.invoke("OCP_ON")
                psuOcpStatus = "ON"
            }
            else -> {
                onSendCommand?.invoke("OCP_OFF")
                psuOcpStatus = "OFF"
            }
        }
    }

    fun resetOcp() {
        onSendCommand?.invoke("RESET_OCP")
        psuOcpStatus = "ON"
    }

    fun setPwmDuration(ms: Int) {
        psuPwmDurationMs = ms
        onSendCommand?.invoke("SET_PWM_DUR:$ms")
    }

    fun getCapacityMah(): Double = capacityAccumMah

    fun resetCapacity() {
        capacityAccumMah = 0.0
        lastUpdateMs = 0L
    }
}
