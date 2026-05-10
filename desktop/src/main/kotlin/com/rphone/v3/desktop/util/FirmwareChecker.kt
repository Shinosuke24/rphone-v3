package com.rphone.v3.desktop.util

import com.rphone.v3.desktop.platform.DesktopSerialConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.util.logging.Logger

/**
 * FirmwareChecker — Check ESP32 firmware version after connection.
 *
 * Strategy:
 *  1. Try to query ESP32 with "FW_VER?" → wait for "FW_VER:x.x.x" (timeout 8 seconds)
 *  2. If ESP32 doesn't respond (old firmware without handler) → use KNOWN_FIRMWARE_VERSION as fallback
 *
 * Update KNOWN_FIRMWARE_VERSION whenever a new firmware is released.
 */
object FirmwareChecker {
    private val logger = Logger.getLogger(FirmwareChecker::class.java.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val KNOWN_FIRMWARE_VERSION = "3.3.18"

    private var sudahCek = false
    private var deviceFwVersion: String = ""
    private var firmwareInfo: FirmwareUpdateHelper.FirmwareInfo? = null
    private var checkJob: Job? = null

    /**
     * Entry point — call after connection established
     */
    fun cekSetelahKonek(
        serialConnection: DesktopSerialConnection,
        onCheckDone: (needsUpdate: Boolean) -> Unit = {}
    ) {
        if (sudahCek) return
        sudahCek = true

        checkJob?.cancel()
        checkJob = scope.launch {
            try {
                // Step 1: Query ESP32 firmware version
                delay(1_000)
                logger.info("Sending FW_VER? to ESP32...")
                serialConnection.sendCommand("FW_VER?")

                val fwVersi = tunggiFwVerResponse(serialConnection, timeoutMs = 8_000)

                val versiYangDipakai: String
                if (fwVersi != null) {
                    versiYangDipakai = fwVersi
                    logger.info("Firmware device (from ESP32): $fwVersi")
                } else {
                    versiYangDipakai = KNOWN_FIRMWARE_VERSION
                    logger.warning("ESP32 didn't respond to FW_VER? — using fallback: $KNOWN_FIRMWARE_VERSION")
                }

                deviceFwVersion = versiYangDipakai

                // Step 2: Fetch firmware.json from Supabase
                val info = FirmwareUpdateHelper.fetchFirmwareInfo()
                if (info == null) {
                    logger.warning("Failed to fetch firmware.json — skipping check")
                    onCheckDone(false)
                    return@launch
                }

                firmwareInfo = info
                logger.info("Firmware server: ${info.version} — device: $versiYangDipakai")

                // Step 3: Compare versions
                if (versiYangDipakai.trim() != info.version.trim()) {
                    logger.warning("FIRMWARE MISMATCH: device=$versiYangDipakai server=${info.version}")
                    onCheckDone(true)
                } else {
                    logger.info("Firmware OK — version matches: $versiYangDipakai")
                    onCheckDone(false)
                }

            } catch (e: Exception) {
                logger.severe("cekSetelahKonek error: ${e.message}")
                onCheckDone(false)
            }
        }
    }

    fun reset() {
        sudahCek = false
        deviceFwVersion = ""
        firmwareInfo = null
        checkJob?.cancel()
        checkJob = null
    }

    fun shutdown() {
        checkJob?.cancel()
        checkJob = null
        scope.cancel()
    }

    fun getDeviceFirmwareVersion(): String = deviceFwVersion
    fun getFirmwareInfo(): FirmwareUpdateHelper.FirmwareInfo? = firmwareInfo

    private suspend fun tunggiFwVerResponse(
        serialConnection: DesktopSerialConnection,
        timeoutMs: Long
    ): String? {
        return withTimeoutOrNull(timeoutMs) {
            serialConnection.receive()
                .map { it.toString(Charsets.UTF_8).trim() }
                .firstOrNull { it.contains("FW_VER:", ignoreCase = true) }
                ?.let { line -> Regex("FW_VER:([\\d.]+)").find(line)?.groupValues?.get(1) }
        }
    }
}
