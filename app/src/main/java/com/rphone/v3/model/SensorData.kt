package com.rphone.v3.model

/**
 * Data dari ESP32 USB Mode:
 * {"mode":"USB","volt":5.063,"curr":0.132,"dp":0.0,"dm":0.0,"charge":"SDP","ocp_en":true}
 */
data class UsbData(
    val volt: Float = 0f,
    val curr: Float = 0f,
    val dp: Float = 0f,
    val dm: Float = 0f,
    val charge: String = "UNKNOWN",
    val ocpEnabled: Boolean = true
)

/**
 * Data dari ESP32 PSU Mode:
 * {"mode":"PSU","volt":5.063,"curr":0.132,"ocp_en":true}
 */
data class PsuData(
    val volt: Float = 0f,
    val curr: Float = 0f,
    val ocpEnabled: Boolean = false,
    val ocpTripped: Boolean = false,
    val pwmEnabled: Boolean = false,
    val pwmDur: Int = 2000
)

/**
 * Data boot log dari ESP32:
 * {"t":20,"curr":0.1234}
 */
data class BootSample(
    val t: Long = 0L,
    val curr: Float = 0f
)

/**
 * Status koneksi Bluetooth
 */
enum class BtStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    RETRY_EXHAUSTED,
    ERROR
}

/**
 * Mode aktif perangkat
 */
enum class DeviceMode {
    USB,
    PSU
}
