package com.rphone.v3.desktop.viewmodel

import com.rphone.v3.core.platform.FileStorage
import javafx.application.Platform
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ProbeViewModel(private val storage: FileStorage) {

    enum class Mode { VOLT, DIODE, OHM }

    data class ProbeReading(
        val mode: Mode,
        val display: String,
        val valueOnly: String,
        val volt: Double = 0.0,
        val vdrop: Double = 0.0,
        val ohm: Double = 0.0,
        val isOpen: Boolean = false,
        val isShort: Boolean = false
    )

    var onReadingUpdate: ((ProbeReading) -> Unit)? = null
    var onHistoryUpdate: ((List<String>) -> Unit)? = null
    var onSendCommand: ((String) -> Unit)? = null

    private val probeMedianSize = 3
    private val probeStableDurationMs = 500L
    private val probePollIntervalMs = 150L
    private var pollingThread: Thread? = null
    private var isPolling = false
    private val probeVoltBuffer = ArrayDeque<Double>(5)
    private val probeDiodeBuffer = ArrayDeque<Double>(5)
    private val probeOhmBuffer = ArrayDeque<Double>(5)

    private var probeStableDisplay = ""
    private var probeStableStartMs = 0L
    private var probeHasStartedMeasuring = false
    private var probeLastStableOhm = 0.0

    private val historyAktif = mutableListOf<String>()
    private val historyPasif = mutableListOf<String>()

    private fun median(buffer: ArrayDeque<Double>): Double {
        if (buffer.isEmpty()) return 0.0
        val sorted = buffer.sorted()
        return sorted[sorted.size / 2]
    }

    private fun addToProbeBuffer(buffer: ArrayDeque<Double>, value: Double) {
        if (buffer.size >= probeMedianSize) buffer.removeFirst()
        buffer.addLast(value)
    }

    fun processJson(jsonText: String) {
        try {
            // simple extractors
            fun extractString(key: String): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(jsonText)?.groups?.get(1)?.value
            fun extractDouble(key: String): Double? = Regex("\"$key\"\\s*:\\s*([-+]?[0-9]*\\.?[0-9]+)").find(jsonText)?.groups?.get(1)?.value?.toDoubleOrNull()

            val probeModeRaw = extractString("probe") ?: "VOLT"
            val probeMode = when (probeModeRaw.uppercase()) {
                "DIODE" -> Mode.DIODE
                "OHM" -> Mode.OHM
                else -> Mode.VOLT
            }

            if (probeModeRaw.equals("SETTLING", ignoreCase = true)) {
                // notify UI to show settling
                Platform.runLater {
                    onReadingUpdate?.invoke(ProbeReading(probeMode, "---", "---"))
                }
                return
            }

            val volt = extractDouble("volt") ?: 0.0
            val vdrop = extractDouble("vdrop") ?: 0.0
            val ohm = extractDouble("ohm") ?: 0.0
            val displayRaw = extractString("display") ?: ""

            val reading = when (probeMode) {
                Mode.VOLT -> {
                    if (volt < 0.3) {
                        probeVoltBuffer.clear()
                        ProbeReading(probeMode, "0.00 V", "0.00", volt = 0.0)
                    } else {
                        addToProbeBuffer(probeVoltBuffer, volt)
                        val stable = if (probeVoltBuffer.size < probeMedianSize) volt else median(probeVoltBuffer)
                        ProbeReading(probeMode, String.format("%.3f V", stable), String.format("%.3f", stable), volt = stable)
                    }
                }
                Mode.DIODE -> {
                    val isOpen = displayRaw.uppercase() == "OL" || displayRaw.uppercase() == "OPEN"
                    val isShort = displayRaw.uppercase().contains("SHORT")
                    if (isOpen || isShort) {
                        probeDiodeBuffer.clear()
                        val displayOut = when {
                            isOpen -> "OL"
                            displayRaw.isBlank() -> "0mV"
                            else -> displayRaw
                        }
                        ProbeReading(probeMode, displayOut, displayOut, vdrop = 0.0, isOpen = isOpen, isShort = isShort)
                    } else {
                        val mv = if (vdrop > 0.0) vdrop * 1000.0 else (displayRaw.removeSuffix("mV").trim().toDoubleOrNull() ?: 0.0)
                        addToProbeBuffer(probeDiodeBuffer, mv)
                        val stableMv = if (probeDiodeBuffer.size < probeMedianSize) mv else median(probeDiodeBuffer)
                        val stableV = stableMv / 1000.0
                        ProbeReading(probeMode, String.format("%.3f V", stableV), String.format("%.0f", stableMv), vdrop = stableV)
                    }
                }
                Mode.OHM -> {
                    val isOpen = displayRaw.uppercase() == "OL" || displayRaw.uppercase() == "OPEN"
                    val isShort = displayRaw.uppercase().contains("SHORT")
                    if (isOpen || isShort) {
                        probeOhmBuffer.clear()
                        val displayOut = if (isOpen) "OL" else "0Ω"
                        ProbeReading(probeMode, displayOut, displayOut, ohm = if (isOpen) 0.0 else 0.0, isOpen = isOpen, isShort = isShort)
                    } else {
                        val ohmValue = when {
                            ohm > 0.0 -> ohm
                            displayRaw.endsWith("MΩ") -> (displayRaw.removeSuffix("MΩ").trim().toDoubleOrNull() ?: 0.0) * 1_000_000.0
                            displayRaw.endsWith("KΩ") -> (displayRaw.removeSuffix("KΩ").trim().toDoubleOrNull() ?: 0.0) * 1_000.0
                            displayRaw.endsWith("Ω") -> displayRaw.removeSuffix("Ω").trim().toDoubleOrNull() ?: 0.0
                            else -> displayRaw.trim().toDoubleOrNull() ?: 0.0
                        }
                        addToProbeBuffer(probeOhmBuffer, ohmValue)
                        val med = if (probeOhmBuffer.size < probeMedianSize) ohmValue else median(probeOhmBuffer)
                        val deadband = when {
                            med >= 1_000_000.0 -> 5000.0
                            med >= 1_000.0 -> 500.0
                            else -> 1.0
                        }
                        val stable = if (probeLastStableOhm != 0.0 && kotlin.math.abs(med - probeLastStableOhm) < deadband) {
                            probeLastStableOhm
                        } else {
                            probeLastStableOhm = med
                            med
                        }
                        val text = when {
                            stable >= 1_000_000.0 -> String.format("%.2fMΩ", stable / 1_000_000.0)
                            stable >= 1_000.0 -> String.format("%.3fKΩ", stable / 1_000.0)
                            else -> String.format("%.1fΩ", stable)
                        }
                        ProbeReading(probeMode, text, text, ohm = stable)
                    }
                }
            }

            // history handling
            val isGnd = when (reading.mode) {
                Mode.VOLT -> reading.volt == 0.0
                Mode.DIODE -> reading.vdrop < 0.05
                Mode.OHM -> reading.display == "0Ω"
            }
            val isValid = when (reading.mode) {
                Mode.VOLT -> reading.volt > 0.0
                Mode.DIODE, Mode.OHM -> !reading.isOpen
            }

            if (isValid) {
                if (isGnd && !probeHasStartedMeasuring) {
                    // ignore
                } else {
                    if (!isGnd) probeHasStartedMeasuring = true

                    val displayHistory = if (isGnd) "GND" else reading.display
                    val now = System.currentTimeMillis()
                    if (displayHistory != probeStableDisplay) {
                        probeStableDisplay = displayHistory
                        probeStableStartMs = now
                    } else if (now - probeStableStartMs >= probeStableDurationMs) {
                        val t = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        val entry = "$t | ${reading.mode.name} | ${reading.display}"
                        val target = if (reading.mode == Mode.VOLT) historyAktif else historyPasif
                        target.add(0, entry)
                        while (target.size > 20) target.removeLast()
                        probeStableStartMs = Long.MAX_VALUE
                        Platform.runLater { onHistoryUpdate?.invoke((historyAktif + historyPasif).sortedDescending()) }
                    }
                }
            }

            Platform.runLater {
                onReadingUpdate?.invoke(reading)
            }

        } catch (e: Exception) {
            // swallow; callers can log
        }
    }

    suspend fun saveProbeJsonSnapshot(jsonText: String, mode: String): Boolean {
        return try {
            val modePrefix = when (mode.uppercase()) {
                "DIODE" -> "DIO"
                "OHM" -> "OHM"
                "VOLT" -> "VOL"
                else -> "VOL"
            }
            val filename = "probe-${modePrefix}-${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"))}.json"
            storage.save(filename, jsonText)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Start polling periodically (parity with APK ProbeViewModel.startPolling).
     * Sends GET_VOLT / GET_DIODE / GET_OHM commands every 150ms.
     */
    fun startPolling() {
        if (isPolling) return
        isPolling = true
        pollingThread = Thread {
            while (isPolling) {
                try {
                    // Determine current mode and send appropriate poll command
                    val pollCmd = when {
                        probeVoltBuffer.isNotEmpty() -> "GET_VOLT"
                        probeDiodeBuffer.isNotEmpty() -> "GET_DIODE"
                        probeOhmBuffer.isNotEmpty() -> "GET_OHM"
                        else -> "GET_VOLT" // default mode
                    }
                    onSendCommand?.invoke(pollCmd)
                    Thread.sleep(probePollIntervalMs)
                } catch (e: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // swallow
                }
            }
        }
        pollingThread?.start()
    }

    /**
     * Stop polling (parity with APK ProbeViewModel.stopPolling).
     */
    fun stopPolling() {
        isPolling = false
        pollingThread?.interrupt()
        pollingThread = null
    }
}

