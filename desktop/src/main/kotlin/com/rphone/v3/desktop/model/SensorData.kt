package com.rphone.v3.desktop.model

/**
 * Sensor reading data model — parity with APK com.rphone.v3.model.SensorData
 */
data class SensorData(
    val type: String,          // "TEMP", "HUMIDITY", "PROXIMITY", etc
    val value: Double,
    val unit: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
