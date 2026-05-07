package com.rphone.v3.desktop.engine

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Desktop port of APK's BootRecorder.kt
 * Handles waveform recording with multi-mode support and auto-detection.
 */
class BootRecorder(
    private val modeRekam: String = "USB" // USB or PSU
) {
    private var startTimeMs: Long = 0
    private var isRecording = false

    // Recording buffers (for USB mode)
    private val currentBuffer = mutableListOf<Double>()
    private val voltageBuffer = mutableListOf<Double>()
    private val powerBuffer = mutableListOf<Double>()
    private val dpBuffer = mutableListOf<Double>()
    private val dmBuffer = mutableListOf<Double>()

    // Statistics
    private var peakCurrent = 0.0
    private var minCurrent = Double.MAX_VALUE
    private var idleCount = 0
    private val idleThreshold = 0.05 // 50mA
    private val idleCountThreshold = 25 // Stop after 25 idle samples (5 sec at 200ms interval)

    data class RecordingStats(
        val durasiMs: Long,
        val peakCurrent: Double,
        val avgCurrent: Double,
        val minCurrent: Double,
        val peakVoltage: Double,
        val peakPower: Double,
        val dpAvg: Double,
        val dmAvg: Double,
        val sampleCount: Int,
        val idleCount: Int
    )

    fun startRecording() {
        startTimeMs = System.currentTimeMillis()
        isRecording = true
        currentBuffer.clear()
        voltageBuffer.clear()
        powerBuffer.clear()
        dpBuffer.clear()
        dmBuffer.clear()
        peakCurrent = 0.0
        minCurrent = Double.MAX_VALUE
        idleCount = 0
    }

    fun stopRecording(): RecordingStats {
        isRecording = false
        val durationMs = System.currentTimeMillis() - startTimeMs
        return calculateStats(durationMs)
    }

    fun addUsbSample(
        current: Double,
        voltage: Double,
        power: Double,
        dp: Double = 0.0,
        dm: Double = 0.0
    ): Boolean {
        if (!isRecording) return false

        currentBuffer.add(current.coerceAtLeast(0.0))
        voltageBuffer.add(voltage.coerceAtLeast(0.0))
        powerBuffer.add(power.coerceAtLeast(0.0))
        dpBuffer.add(dp.coerceAtLeast(0.0))
        dmBuffer.add(dm.coerceAtLeast(0.0))

        // Update peak/min
        if (current > peakCurrent) peakCurrent = current
        if (current < minCurrent) minCurrent = current

        // Track idle samples (auto-stop strategy)
        if (current <= idleThreshold) {
            idleCount++
            // Stop after 5 seconds of idle
            if (idleCount >= idleCountThreshold) {
                return false // Signal auto-stop
            }
        } else {
            idleCount = 0 // Reset idle counter on activity
        }

        return true // Continue recording
    }

    fun addPsuSample(voltage: Double, mode: String = "PWM") {
        if (!isRecording) return

        voltageBuffer.add(voltage.coerceAtLeast(0.0))
        
        if (voltage > peakCurrent) peakCurrent = voltage
        if (voltage < minCurrent) minCurrent = voltage

        // PSU idle detection
        if (voltage <= idleThreshold) {
            idleCount++
            if (idleCount >= idleCountThreshold) {
                // Auto-stop for PSU too
            }
        } else {
            idleCount = 0
        }
    }

    /**
     * Detect likely mode of operation based on waveform characteristics.
     */
    fun detectMode(currentWaveform: List<Double>): String {
        if (currentWaveform.isEmpty()) return "UNKNOWN"

        val peak = currentWaveform.maxOrNull() ?: 0.0
        val avg = currentWaveform.average()
        val stdDev = calculateStdDev(currentWaveform, avg)

        // USB device signatures typically have:
        // - Specific current ranges (0.1-2.5A typically)
        // - More stable patterns (low stdDev relative to avg)        // - Regular ripple patterns

        // PSU typically has:
        // - Higher currents (2.0-20A)
        // - More variable ripple
        // - Larger deviations

        return when {
            peak > 5.0 -> "PSU"
            avg in 0.05..2.5 && stdDev / avg < 0.5 -> "USB"
            peak > 1.0 && stdDev / avg > 0.6 -> "PSU"
            else -> "USB" // Default to USB
        }
    }

    /**
     * Validate if recording has minimum required samples and quality.
     */
    fun validateRecording(mode: String = modeRekam): Boolean {
        return when (mode) {
            "USB" -> {
                // Need at least 150 samples for USB (AUTO_WAVEID_SAMPLES = 100)
                currentBuffer.size >= 150 && peakCurrent >= 0.1
            }
            "PSU" -> {
                // Need at least 300 samples for PSU
                voltageBuffer.size >= 300 && peakCurrent >= 0.2
            }
            else -> false
        }
    }

    private fun calculateStats(durationMs: Long): RecordingStats {
        val peakVoltage = voltageBuffer.maxOrNull() ?: 0.0
        val avgCurrent = if (currentBuffer.isNotEmpty()) currentBuffer.average() else 0.0
        val avgVoltage = if (voltageBuffer.isNotEmpty()) voltageBuffer.average() else 0.0
        val peakPower = powerBuffer.maxOrNull() ?: 0.0

        // For multi-channel USB, snapshot D+/D- when current significant
        val dpAvg = if (dpBuffer.isNotEmpty()) {
            dpBuffer.filter { it > 0.01 }.average()
        } else 0.0
        val dmAvg = if (dmBuffer.isNotEmpty()) {
            dmBuffer.filter { it > 0.01 }.average()
        } else 0.0

        val actualMinCurrent = if (minCurrent == Double.MAX_VALUE) 0.0 else minCurrent

        return RecordingStats(
            durasiMs = durationMs,
            peakCurrent = peakCurrent,
            avgCurrent = avgCurrent,
            minCurrent = actualMinCurrent,
            peakVoltage = peakVoltage,
            peakPower = peakPower,
            dpAvg = dpAvg,
            dmAvg = dmAvg,
            sampleCount = currentBuffer.size,
            idleCount = idleCount
        )
    }

    fun getWaveformData(): Map<String, List<Double>> = mapOf(
        "current" to currentBuffer.toList(),
        "voltage" to voltageBuffer.toList(),
        "power" to powerBuffer.toList(),
        "dp" to dpBuffer.toList(),
        "dm" to dmBuffer.toList()
    )

    fun getRecordingDurationMs(): Long = System.currentTimeMillis() - startTimeMs

    fun isCurrentlyRecording(): Boolean = isRecording

    private fun calculateStdDev(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }
}
