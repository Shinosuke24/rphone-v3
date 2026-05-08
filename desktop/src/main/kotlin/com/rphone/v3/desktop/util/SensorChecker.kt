package com.rphone.v3.desktop.util

/**
 * Sensor checking utilities — parity with APK com.rphone.v3.util.SensorChecker
 * Desktop doesn't have actual sensors, but provides compatible interface
 */
object SensorChecker {

    fun hasTemperatureSensor(): Boolean = false
    fun hasHumiditySensor(): Boolean = false
    fun hasProximitySensor(): Boolean = false
    fun hasAccelerometer(): Boolean = false

    fun getTemperature(): Double? = null
    fun getHumidity(): Double? = null
    fun getProximity(): Double? = null

    fun canAccessSensors(): Boolean = false
}
