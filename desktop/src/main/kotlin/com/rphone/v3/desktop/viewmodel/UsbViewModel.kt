package com.rphone.v3.desktop.viewmodel

import com.rphone.v3.core.platform.FileStorage
import javafx.application.Platform
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * USB mode ViewModel — parity with APK UsbViewModel
 * Handles USB data parsing, capacity tracking, and charge protocol voting
 */
class UsbViewModel(private val storage: FileStorage) {

    data class UsbData(
        val volt: Double = 0.0,
        val curr: Double = 0.0,
        val dp: Double = 0.0,
        val dm: Double = 0.0,
        val charge: String = "Standard Charging",
        val ocpEnabled: Boolean = true
    )

    var onDataUpdate: ((UsbData) -> Unit)? = null
    var onSendCommand: ((String) -> Unit)? = null

    private var capacityAccum = 0.0
    private var lastUpdateMs = 0L
    private val chargeVoteBuffer = ArrayDeque<String>(5)
    private var lastStableCharge = "Standard Charging"

    fun processJson(jsonText: String) {
        try {
            fun extractDouble(key: String): Double? = Regex("\"$key\"\\s*:\\s*([-+]?[0-9]*\\.?[0-9]+)").find(jsonText)?.groups?.get(1)?.value?.toDoubleOrNull()
            fun extractString(key: String): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(jsonText)?.groups?.get(1)?.value
            fun extractBoolean(key: String): Boolean? = Regex("\"$key\"\\s*:\\s*(true|false)").find(jsonText)?.groups?.get(1)?.value?.equals("true", ignoreCase = true)

            val mode = extractString("mode")?.uppercase()
            if (mode != null && mode != "USB") return

            val volt = extractDouble("volt") ?: 0.0
            val curr = extractDouble("curr") ?: 0.0
            val dp = extractDouble("dp") ?: 0.0
            val dm = extractDouble("dm") ?: 0.0
            val chargeRaw = extractString("charge") ?: detectChargeProtocol(dp, dm)
            val ocpEnabled = extractBoolean("ocp_en") ?: true

            val stableCharge = voteCharge(chargeRaw)
            val data = UsbData(volt, curr, dp, dm, stableCharge, ocpEnabled)

            // Calculate capacity accumulation
            val now = System.currentTimeMillis()
            if (lastUpdateMs > 0L) {
                val dtHours = (now - lastUpdateMs) / 3_600_000.0
                capacityAccum += curr * 1000.0 * dtHours
            }
            lastUpdateMs = now

            Platform.runLater {
                onDataUpdate?.invoke(data)
            }
        } catch (_: Exception) {
            // swallow
        }
    }

    private fun voteCharge(protocol: String): String {
        chargeVoteBuffer.addLast(protocol)
        if (chargeVoteBuffer.size > 5) chargeVoteBuffer.removeFirst()
        
        val counts = chargeVoteBuffer.groupingBy { it }.eachCount()
        return counts.maxByOrNull { it.value }?.key ?: "Standard Charging"
    }

    private fun detectChargeProtocol(dp: Double, dm: Double): String {
        return when {
            dp < 0.3 && dm < 0.3 -> "SDP 500mA"
            dp in 3.0..3.6 && dm in 0.4..0.8 -> "QC 3.0"
            dp in 3.0..3.6 && dm < 0.3 -> "QC 2.0"
            dp >= 2.5 && dm >= 2.5 -> "QC 4.0+ / DCP"
            dp in 2.4..3.0 && dm in 1.7..2.3 -> "Apple 12W"
            dp in 1.7..2.3 && dm in 1.7..2.3 -> "CDP / Apple 5W"
            dp in 0.9..1.5 && dm < 0.5 -> "Samsung AFC"
            dp in 0.5..0.9 && dm < 0.3 -> "MTK PE"
            dp >= 0.3 || dm >= 0.3 -> "Fast Charging"
            else -> "Unknown"
        }
    }

    fun getCapacityMah(): Double = capacityAccum

    fun resetCapacity() {
        capacityAccum = 0.0
        lastUpdateMs = 0L
    }
}
