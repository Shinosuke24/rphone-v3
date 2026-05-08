package com.rphone.v3.desktop.model

/**
 * Probe measurement mode enum — parity with APK com.rphone.v3.model.ProbeMode
 */
enum class ProbeMode(val displayName: String, val commandName: String) {
    VOLT("TEGANGAN", "GET_VOLT"),
    DIODE("DIODA", "GET_DIODE"),
    OHM("OHM", "GET_OHM");

    companion object {
        fun fromString(str: String?): ProbeMode = when (str?.uppercase()) {
            "DIODE" -> DIODE
            "OHM" -> OHM
            else -> VOLT
        }
    }
}
