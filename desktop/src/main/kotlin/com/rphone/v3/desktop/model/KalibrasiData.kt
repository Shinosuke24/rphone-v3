package com.rphone.v3.desktop.model

/**
 * Calibration data model — parity with APK com.rphone.v3.model.KalibrasiData
 */
data class KalibrasiData(
    val id: String = "default",
    val name: String = "Standard",
    val probeOffsetVolt: Double = 0.0,
    val probeOffsetOhm: Double = 0.0,
    val probeOffsetDiode: Double = 0.0,
    val createdAtMs: Long = System.currentTimeMillis()
)
