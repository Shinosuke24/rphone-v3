package com.rphone.v3.desktop.viewmodel

import com.rphone.v3.core.platform.FileStorage
import javafx.application.Platform

/**
 * USB mode ViewModel — parity dengan APK UsbViewModel
 *
 * Perubahan dari APK:
 * - voteCharge() sekarang pakai VOTE_MIN_COUNT=3 threshold (sama dengan APK)
 * - OCP parsing support "ocp":"trip"/"reset" string + boolean ocp + ocp_en
 * - resetStats() ditambahkan untuk reset semua state (dipanggil saat RESET DATA)
 */
class UsbViewModel(private val storage: FileStorage) {

    data class UsbData(
        val volt: Double = 0.0,
        val curr: Double = 0.0,
        val dp: Double = 0.0,
        val dm: Double = 0.0,
        val charge: String = "Standard Charging",
        val ocpStatus: String = "OFF"
    ) {
        val ocpEnabled: Boolean
            get() = ocpStatus != "OFF"
    }

    var onDataUpdate: ((UsbData) -> Unit)? = null
    var onSendCommand: ((String) -> Unit)? = null

    private var capacityAccum = 0.0
    private var lastUpdateMs = 0L

    // ── Charge protocol voting — parity dengan APK ──────────────
    private val VOTE_BUFFER_SIZE = 5
    private val VOTE_MIN_COUNT   = 3      // APK: hanya stable jika >= 3 vote sama
    private val chargeVoteBuffer = ArrayDeque<String>(VOTE_BUFFER_SIZE)
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

            // OCP parsing — parity dengan APK UsbViewModel
            // APK: cek "ocp" string event ATAU boolean field
            val ocpStatus: String = run {
                val ocpStr = extractString("ocp")?.lowercase()
                val ocpBool = extractBoolean("ocp")
                val ocpEn   = extractBoolean("ocp_en")
                when {
                    ocpStr == "trip"   -> "TRIP"
                    ocpStr == "reset"  -> "OFF"
                    ocpBool == true     -> "ON"
                    ocpBool == false    -> "OFF"
                    ocpEn == true       -> "ON"
                    ocpEn == false      -> "OFF"
                    else               -> "OFF"
                }
            }

            val stableCharge = voteCharge(chargeRaw)
            val data = UsbData(volt, curr, dp, dm, stableCharge, ocpStatus)

            // Akumulasi kapasitas (sama persis dengan APK)
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

    /**
     * Vote charge protocol — parity dengan APK UsbViewModel.voteCharge().
     * Hanya ganti lastStableCharge jika ada protokol yang mendapat >= VOTE_MIN_COUNT (3) votes
     * dari buffer 5 sample terakhir. Ini mencegah flip-flop cepat.
     */
    private fun voteCharge(newValue: String): String {
        if (chargeVoteBuffer.size >= VOTE_BUFFER_SIZE) {
            chargeVoteBuffer.removeFirst()
        }
        chargeVoteBuffer.addLast(newValue)

        val freq   = chargeVoteBuffer.groupingBy { it }.eachCount()
        val winner = freq.entries
            .filter  { it.value >= VOTE_MIN_COUNT }
            .maxByOrNull { it.value }
            ?.key

        if (winner != null) lastStableCharge = winner
        return lastStableCharge
    }

    /**
     * Deteksi protokol charging dari tegangan D+ dan D-.
     * Identik dengan APK JsonParser.detectChargeProtocol().
     */
    private fun detectChargeProtocol(dp: Double, dm: Double): String {
        return when {
            dp < 0.3 && dm < 0.3           -> "SDP 500mA"
            dp in 3.0..3.6 && dm in 0.4..0.8 -> "QC 3.0"
            dp in 3.0..3.6 && dm < 0.3     -> "QC 2.0"
            dp >= 2.5 && dm >= 2.5          -> "QC 4.0+ / DCP"
            dp in 2.4..3.0 && dm in 1.7..2.3 -> "Apple 12W"
            dp in 1.7..2.3 && dm in 1.7..2.3 -> "CDP / Apple 5W"
            dp in 0.9..1.5 && dm < 0.5     -> "Samsung AFC"
            dp in 0.5..0.9 && dm < 0.3     -> "MTK PE"
            dp >= 0.3 || dm >= 0.3          -> "Fast Charging"
            else                            -> "Unknown"
        }
    }

    fun getCapacityMah(): Double = capacityAccum

    /**
     * Reset semua statistik — parity dengan APK UsbViewModel.resetStats()
     */
    fun resetStats() {
        capacityAccum    = 0.0
        lastUpdateMs     = 0L
        chargeVoteBuffer.clear()
        lastStableCharge = "Standard Charging"
    }

    // Alias untuk backward-compat dengan call site lama
    fun resetCapacity() = resetStats()
}
