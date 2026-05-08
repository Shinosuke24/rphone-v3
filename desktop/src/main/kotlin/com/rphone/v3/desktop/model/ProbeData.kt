package com.rphone.v3.desktop.model

/**
 * Probe reading data model — parity with APK com.rphone.v3.model.ProbeData
 */
data class ProbeData(
    val mode: String,           // "VOLT", "DIODE", "OHM"
    val display: String,        // "0.00 V", "0mV", "0Ω", "OL", etc
    val volt: Double = 0.0,
    val vdrop: Double = 0.0,
    val ohm: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)
