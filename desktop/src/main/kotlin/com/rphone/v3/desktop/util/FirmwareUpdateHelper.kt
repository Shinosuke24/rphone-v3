package com.rphone.v3.desktop.util

import com.rphone.v3.desktop.platform.DesktopSerialConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.logging.Logger

/**
 * FirmwareUpdateHelper — Check & download firmware OTA via Supabase Storage.
 *
 * Flow:
 *   1. App sends "FW_VER?" to ESP32 via USB
 *   2. ESP32 responds "FW_VER:3.3.18"
 *   3. fetchFirmwareInfo() → fetch firmware.json from Supabase
 *   4. Compare versions → show update overlay if different
 *   5. User presses update → download firmware → flash via USB
 *
 * Flash: only firmware → offset 0x10000 (OTA slot).
 * Bootloader & partitions cannot be flashed — hardware protected.
 *
 * Format firmware.json in Supabase:
 * {
 *   "version": "3.3.19",
 *   "firmware_url": "https://...firmware.bin",
 *   "changelog": "Firmware change description"
 * }
 */
object FirmwareUpdateHelper {
    private val logger = Logger.getLogger(FirmwareUpdateHelper::class.java.name)

    // Supabase config (same as OtaUpdateHelper)
    private const val SUPABASE_URL = "https://zlqkmedaupuqiqiwoxyw.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpscWttZWRhdXB1cWlxaXdveHl3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY2NjYzNTYsImV4cCI6MjA5MjI0MjM1Nn0.vGLBsZPUo2juN9Izs5lX4-Sck7acEU9UE5hJW-GJ3uc"

    // URL to firmware.json in "releases" bucket
    private const val FIRMWARE_JSON_URL =
        "$SUPABASE_URL/storage/v1/object/public/releases/firmware.json"

    // ESP32 flash offsets
    private const val OFFSET_FIRMWARE = "0x10000"

    data class FirmwareInfo(
        val version: String,
        val firmwareUrl: String,
        val changelog: String
    )

    data class DownloadedFirmware(
        val firmware: File
    )

    /**
     * Fetch firmware.json from Supabase.
     * Returns null if failed (network error, parse error, etc).
     */
    suspend fun fetchFirmwareInfo(): FirmwareInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(FIRMWARE_JSON_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SUPABASE_KEY)

            val code = conn.responseCode
            if (code !in 200..299) {
                logger.warning("fetchFirmwareInfo HTTP $code — skipping firmware check")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            FirmwareInfo(
                version = json.getString("version"),
                firmwareUrl = json.getString("firmware_url"),
                changelog = json.optString("changelog", "-")
            )
        } catch (e: Exception) {
            logger.severe("fetchFirmwareInfo error: ${e.message}")
            null
        }
    }

    /**
     * Check if device firmware needs update
     */
    fun isUpdateNeeded(deviceVersion: String, serverVersion: String): Boolean {
        val dev = deviceVersion.trim()
        val srv = serverVersion.trim()
        if (dev.isEmpty() || srv.isEmpty()) return false
        return dev != srv
    }

    /**
     * Download firmware file from Supabase
     */
    private suspend fun downloadFile(
        url: String,
        fileName: String,
        cacheDir: File,
        onProgress: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        try {
            val outFile = File(cacheDir, fileName)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.connect()

            val total = conn.contentLength.toLong()
            var downloaded = 0L

            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        downloaded += bytes
                        if (total > 0) {
                            onProgress((downloaded * 100 / total).toInt())
                        }
                    }
                }
            }

            logger.info("Downloaded $fileName → ${outFile.length()} bytes")
            outFile
        } catch (e: Exception) {
            logger.severe("downloadFile [$fileName] error: ${e.message}")
            null
        }
    }

    /**
     * Download all firmware files from Supabase.
     * [onProgress] called with 0..100.
     * [onStatus] called with status text message.
     */
    suspend fun downloadAllFirmware(
        info: FirmwareInfo,
        cacheDir: File,
        onProgress: (Int) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): DownloadedFirmware? {
        onStatus("Downloading firmware v${info.version}...")
        val firmware = downloadFile(
            info.firmwareUrl, "fw_main.bin", cacheDir
        ) { onProgress(it) }
        if (firmware == null) {
            onStatus("Failed to download firmware.")
            return null
        }

        onProgress(100)
        onStatus("Firmware downloaded successfully.")
        return DownloadedFirmware(firmware)
    }

    /**
     * Flash firmware to ESP32 via USB Serial at offset 0x10000 (OTA slot).
     *
     * NOTE: Bootloader (0x1000) and partitions (0x8000) cannot be flashed —
     * those areas are hardware-protected. Only firmware (0x10000) can be updated via OTA.
     *
     * Protocol:
     *   → FW_FLASH_START:0x10000:<size>
     *   ← FW_FLASH_READY
     *   → FW_CHUNK:<index>:<base64>  (loop)
     *   ← FW_CHUNK_OK:<index>        (per chunk)
     *   → FW_FLASH_END
     *   ← FW_FLASH_DONE
     *   ESP32 restarts automatically after DONE.
     */
    suspend fun flashFirmware(
        serialConnection: DesktopSerialConnection,
        firmwareFile: File,
        onProgress: (Int) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val firmware = firmwareFile.readBytes()
            val size = firmware.size

            onStatus("Starting firmware flash...")
            logger.info("Flashing firmware: ${firmwareFile.absolutePath} ($size bytes)")

            // Send start command
            serialConnection.sendCommand("FW_FLASH_START:$OFFSET_FIRMWARE:$size")
            delay(500)

            // Check if device is ready
            serialConnection.sendCommand("FW_FLASH_READY?")
            delay(500)

            // Flash firmware in chunks (1024 bytes each)
            val chunkSize = 1024
            var flashedBytes = 0
            var chunkIndex = 0

            while (flashedBytes < size) {
                val remaining = size - flashedBytes
                val currentChunkSize = minOf(chunkSize, remaining)
                val chunk = firmware.sliceArray(flashedBytes until flashedBytes + currentChunkSize)
                val base64 = java.util.Base64.getEncoder().encodeToString(chunk)

                serialConnection.sendCommand("FW_CHUNK:$chunkIndex:$base64")
                delay(100)

                flashedBytes += currentChunkSize
                val progress = (flashedBytes * 100 / size)
                onProgress(progress)
                onStatus("Flashing: $progress%")

                logger.info("Flashed chunk $chunkIndex: $currentChunkSize bytes ($progress%)")
                chunkIndex++
            }

            // Send end command
            onStatus("Finalizing flash...")
            serialConnection.sendCommand("FW_FLASH_END")
            delay(1000)

            // Check completion
            serialConnection.sendCommand("FW_FLASH_DONE?")
            delay(2000)

            onStatus("Flash complete. Device will restart.")
            logger.info("Firmware flash complete")
            true

        } catch (e: Exception) {
            logger.severe("flashFirmware error: ${e.message}")
            onStatus("Flash failed: ${e.message}")
            false
        }
    }
}
