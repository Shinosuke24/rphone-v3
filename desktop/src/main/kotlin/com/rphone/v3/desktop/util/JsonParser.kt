package com.rphone.v3.desktop.util

import com.rphone.v3.desktop.model.ProbeData
import com.rphone.v3.desktop.model.ProbeMode
import org.json.JSONObject

/**
 * JSON parsing utilities — parity with APK com.rphone.v3.util.JsonParser
 */
object JsonParser {

    data class UsbData(
        val volt: Double = 0.0,
        val curr: Double = 0.0,
        val dp: Double = 0.0,
        val dm: Double = 0.0,
        val charge: String = "UNKNOWN",
        val ocpEnabled: Boolean = true
    )

    data class PsuData(
        val volt: Double = 0.0,
        val curr: Double = 0.0,
        val ocpEnabled: Boolean = false,
        val ocpTripped: Boolean = false,
        val pwmEnabled: Boolean = false,
        val pwmDur: Int = 2000
    )

    fun extractString(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groups?.get(1)?.value
    }

    fun extractDouble(json: String, key: String): Double? {
        val regex = Regex("\"$key\"\\s*:\\s*([-+]?[0-9]*\\.?[0-9]+)")
        return regex.find(json)?.groups?.get(1)?.value?.toDoubleOrNull()
    }

    fun extractInt(json: String, key: String): Int? {
        val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return regex.find(json)?.groups?.get(1)?.value?.toIntOrNull()
    }

    fun extractBoolean(json: String, key: String): Boolean? {
        val regex = Regex("\"$key\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
        return regex.find(json)?.groups?.get(1)?.value?.equals("true", ignoreCase = true)
    }

    fun extractArray(json: String, key: String): List<String> {
        val regex = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
        val match = regex.find(json) ?: return emptyList()
        val arrayContent = match.groups[1]?.value ?: return emptyList()
        return Regex("\"([^\"]*)\"").findAll(arrayContent).map { it.groups[1]?.value ?: "" }.toList()
    }

    fun parseUsbData(json: String): UsbData? {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("mode") != "USB") return null
            if (!obj.has("volt") || !obj.has("curr")) return null
            val dp = obj.optDouble("dp", 0.0)
            val dm = obj.optDouble("dm", 0.0)
            UsbData(
                volt = obj.optDouble("volt", 0.0),
                curr = obj.optDouble("curr", 0.0),
                dp = dp,
                dm = dm,
                charge = obj.optString("charge", detectChargeProtocol(dp, dm)),
                ocpEnabled = when {
                    obj.optString("ocp").equals("trip", ignoreCase = true) -> true
                    obj.optString("ocp").equals("reset", ignoreCase = true) -> false
                    obj.has("ocp") && obj.get("ocp") is Boolean -> obj.optBoolean("ocp", true)
                    else -> obj.optBoolean("ocp_en", true)
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parsePsuData(json: String): PsuData? {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("mode") != "PSU") return null
            if (!obj.has("volt") || !obj.has("curr")) return null
            PsuData(
                volt = obj.optDouble("volt", 0.0),
                curr = obj.optDouble("curr", 0.0),
                ocpEnabled = obj.optBoolean("ocp_en", false),
                ocpTripped = obj.optBoolean("ocp", false),
                pwmEnabled = obj.optBoolean("pwm_en", false),
                pwmDur = obj.optInt("pwm_dur", 2000)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseProbeData(json: String): ProbeData? {
        return try {
            val obj = JSONObject(json)
            if (!obj.has("probe")) return null
            when (ProbeMode.fromString(obj.optString("probe"))) {
                ProbeMode.VOLT -> {
                    val volt = obj.optDouble("volt", 0.0)
                    val display = if (volt < 0.01) "0.00 V" else String.format(java.util.Locale.US, "%.2f V", volt)
                    ProbeData(mode = "VOLT", display = display, volt = volt)
                }
                ProbeMode.DIODE -> {
                    val vdrop = obj.optDouble("vdrop", 0.0)
                    val display = obj.optString("display", if (vdrop > 0.0) String.format(java.util.Locale.US, "%.0fmV", vdrop * 1000.0) else "0mV")
                    ProbeData(mode = "DIODE", display = display, vdrop = vdrop)
                }
                ProbeMode.OHM -> {
                    val ohm = obj.optDouble("ohm", 0.0)
                    val display = obj.optString("display", if (ohm <= 0.0) "OL" else String.format(java.util.Locale.US, "%.1fΩ", ohm))
                    ProbeData(mode = "OHM", display = display, ohm = ohm)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseOcpEvent(json: String): Pair<String, Double>? {
        return try {
            val obj = JSONObject(json)
            if (!obj.has("ocp")) return null
            Pair(obj.optString("ocp", ""), obj.optDouble("curr", 0.0))
        } catch (_: Exception) {
            null
        }
    }

    fun isBootLogStart(json: String): Boolean = try { JSONObject(json).optBoolean("boot_log_start", false) } catch (_: Exception) { false }

    fun isBootLogEnd(json: String): Boolean = try { JSONObject(json).optBoolean("boot_log_end", false) } catch (_: Exception) { false }

    fun isInfoMessage(json: String): Boolean = try {
        val obj = JSONObject(json)
        obj.optBoolean("info", false) || obj.optString("level").equals("INFO", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    fun isErrorMessage(json: String): Boolean = try {
        val obj = JSONObject(json)
        obj.optBoolean("error", false) || obj.optString("level").equals("ERROR", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    fun parseInfoMessage(json: String): String = try {
        val obj = JSONObject(json)
        obj.optString("message", obj.optString("info", ""))
    } catch (_: Exception) {
        ""
    }

    fun toJson(map: Map<String, Any>): String {
        return buildString {
            append("{")
            map.entries.forEachIndexed { idx, (key, value) ->
                if (idx > 0) append(",")
                append("\"$key\":")
                when (value) {
                    is String -> append("\"$value\"")
                    is Number -> append(value)
                    is Boolean -> append(value)
                    else -> append("\"$value\"")
                }
            }
            append("}")
        }
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
}
