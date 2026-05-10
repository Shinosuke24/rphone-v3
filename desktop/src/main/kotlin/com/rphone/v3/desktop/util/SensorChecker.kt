package com.rphone.v3.desktop.util

import com.rphone.v3.desktop.platform.DesktopSerialConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sensor checking utilities — parity with APK com.rphone.v3.util.SensorChecker
 * Desktop uses the attached ESP32 sensor probe as the source of truth.
 */
object SensorChecker {
    data class SensorStatus(
        val ina3221: Boolean = false,
        val ads1115: Boolean = false,
        val rawMessage: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    fun hasTemperatureSensor(): Boolean = true
    fun hasHumiditySensor(): Boolean = true
    fun hasProximitySensor(): Boolean = true
    fun hasAccelerometer(): Boolean = true

    fun getTemperature(): Double? = 0.0
    fun getHumidity(): Double? = 0.0
    fun getProximity(): Double? = 0.0

    fun canAccessSensors(): Boolean = true

    fun shutdown() {
        currentJob?.cancel()
        currentJob = null
        scope.cancel()
    }

    fun checkSensors(
        serialConnection: DesktopSerialConnection,
        timeoutMs: Long = 8000L,
        onResult: (SensorStatus) -> Unit = {}
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            val status = withTimeoutOrNull(timeoutMs) {
                serialConnection.sendCommand("SENSOR_CHECK")
                serialConnection.receive()
                    .map { it.toString(Charsets.UTF_8).trim() }
                    .firstOrNull { it.isNotBlank() && it.contains("sensor", ignoreCase = true) }
                    ?.let { line ->
                        val ina = Regex("\"ina3221\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                            .find(line)?.groups?.get(1)?.value?.equals("true", ignoreCase = true) ?: false
                        val ads = Regex("\"ads1115\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                            .find(line)?.groups?.get(1)?.value?.equals("true", ignoreCase = true) ?: false
                        SensorStatus(ina3221 = ina, ads1115 = ads, rawMessage = line)
                    }
            } ?: SensorStatus(rawMessage = "timeout")

            onResult(status)
        }
    }
}
