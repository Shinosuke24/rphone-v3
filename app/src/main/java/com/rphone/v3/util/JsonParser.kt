package com.rphone.v3.util

import android.util.Log
import com.rphone.v3.model.BootSample
import com.rphone.v3.model.ProbeData
import com.rphone.v3.model.PsuData
import com.rphone.v3.model.UsbData
import org.json.JSONObject

object JsonParser {

    fun parseMode(json: String): String? {
        return try {
            JSONObject(json).optString("mode", null)
        } catch (e: Exception) {
            null
        }
    }

    fun parseUsbData(json: String): UsbData? {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("mode") != "USB") return null
            if (!obj.has("volt") || !obj.has("curr")) return null

            val dp = obj.optDouble("dp", 0.0).toFloat()
            val dm = obj.optDouble("dm", 0.0).toFloat()

            UsbData(
                volt       = obj.optDouble("volt", 0.0).toFloat(),
                curr       = obj.optDouble("curr", 0.0).toFloat(),
                dp         = dp,
                dm         = dm,
                charge     = detectChargeProtocol(dp, dm),
                ocpEnabled = obj.optBoolean("ocp_en", true)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parsePsuData(json: String): PsuData? {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("mode") != "PSU") return null
            if (!obj.has("volt") || !obj.has("curr")) return null

            PsuData(
                volt       = obj.optDouble("volt", 0.0).toFloat(),
                curr       = obj.optDouble("curr", 0.0).toFloat(),
                ocpEnabled = obj.optBoolean("ocp_en", false),
                ocpTripped = obj.optBoolean("ocp", false),
                pwmEnabled = obj.optBoolean("pwm_en", false),
                pwmDur     = obj.optInt("pwm_dur", 2000)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse OCP event JSON dari ESP32.
     * Format: {"ocp":"trip","curr":3.245,"threshold":3.0}
     *         {"ocp":"reset","curr":0.123}
     *         {"ocp":"auto_reset","curr":0.123}
     *         {"ocp":"on","threshold":3.000}
     *         {"ocp":"off"}
     * Return null jika JSON tidak mengandung field "ocp".
     */
    fun parseOcpEvent(json: String): Pair<String, Float>? {
        return try {
            val obj = JSONObject(json)
            if (!obj.has("ocp")) return null
            val event = obj.optString("ocp", "")
            val curr  = obj.optDouble("curr", 0.0).toFloat()
            Pair(event, curr)
        } catch (e: Exception) {
            null
        }
    }

    fun parseBootSample(json: String): BootSample? {
        return try {
            val obj = JSONObject(json)
            if (!obj.has("t") || !obj.has("curr")) return null
            BootSample(
                t    = obj.optLong("t", 0L),
                curr = obj.optDouble("curr", 0.0).toFloat()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse response probe meter dari ESP32.
     * Mendeteksi field "probe" untuk menentukan mode.
     *
     * Return null jika JSON tidak mengandung field "probe".
     */
    fun parseProbeData(json: String): ProbeData? {
        return try {
            val obj = JSONObject(json)
            if (!obj.has("probe")) return null
            val mode = obj.optString("probe")

            when (mode) {
                "VOLT" -> {
                    val volt = obj.optDouble("volt", 0.0).toFloat()
                    val display = if (volt < 0.01f) "0.00 V" else String.format(java.util.Locale.US, "%.2f V", volt)
                    ProbeData(mode = "VOLT", volt = volt, display = display)
                }
                "DIODE" -> {
                    val vdrop = obj.optDouble("vdrop", 0.0).toFloat()
                    val display = obj.optString("display", "${(vdrop * 1000).toInt()}mV")
                    ProbeData(mode = "DIODE", vdrop = vdrop, display = display)
                }
                "OHM" -> {
                    val ohm = obj.optDouble("ohm", 0.0).toFloat()
                    ProbeData(mode = "OHM", ohm = ohm, display = obj.optString("display", "OL"))
                }
                "OPEN" -> {
                    ProbeData(mode = "OPEN", display = "OL")
                }
                "SHORT" -> {
                    val display = if (obj.has("vdrop")) "0mV" else "0Ω"
                    ProbeData(mode = "SHORT",
                        vdrop = obj.optDouble("vdrop", 0.0).toFloat(),
                        ohm = obj.optDouble("ohm", 0.0).toFloat(),
                        display = display
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isBootLogStart(json: String): Boolean =
        try { JSONObject(json).optBoolean("boot_log_start", false) } catch (e: Exception) { false }

    fun isBootLogEnd(json: String): Boolean =
        try { JSONObject(json).optBoolean("boot_log_end", false) } catch (e: Exception) { false }

    fun isInfoMessage(json: String): Boolean =
        try { JSONObject(json).has("info") } catch (e: Exception) { false }

    fun isErrorMessage(json: String): Boolean =
        try { JSONObject(json).has("error") } catch (e: Exception) { false }

    fun parseInfoMessage(json: String): String {
        return try {
            JSONObject(json).optString("info", "")
        } catch (e: Exception) { "" }
    }

    /**
     * Deteksi protokol charging dari nilai tegangan D+ dan D-.
     */
    private fun detectChargeProtocol(dp: Float, dm: Float): String {
        return when {
            dp < 0.3f && dm < 0.3f -> "SDP 500mA"
            dp in 3.0f..3.6f && dm in 0.4f..0.8f -> "QC 3.0"
            dp in 3.0f..3.6f && dm < 0.3f -> "QC 2.0"
            dp >= 2.5f && dm >= 2.5f -> "QC 4.0+ / DCP"
            dp in 2.4f..3.0f && dm in 1.7f..2.3f -> "Apple 12W"
            dp in 1.7f..2.3f && dm in 1.7f..2.3f -> "CDP / Apple 5W"
            dp in 0.9f..1.5f && dm < 0.5f -> "Samsung AFC"
            dp in 0.5f..0.9f && dm < 0.3f -> "MTK PE"
            dp >= 0.3f || dm >= 0.3f -> "Fast Charging"
            else -> "Unknown"
        }
    }
}
