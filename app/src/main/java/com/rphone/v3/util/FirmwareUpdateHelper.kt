package com.rphone.v3.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * FirmwareUpdateHelper — Cek & download firmware OTA via Supabase Storage.
 *
 * Flow:
 *   1. App kirim "FW_VER?" ke ESP32 via BT/OTG
 *   2. ESP32 balas "FW_VER:3.3.18"
 *   3. fetchFirmwareInfo() → ambil firmware.json dari Supabase
 *   4. Bandingkan versi → tampilkan overlay blokir jika beda
 *   5. User tekan update → download 3 file → flash berurutan via OTG
 *
 * Flash: hanya firmware → offset 0x10000 (OTA slot).
 * Bootloader & partitions tidak diflash — hardware protected.
 *
 * Format firmware.json di Supabase:
 * {
 *   "version": "3.3.19",
 *   "firmware_url": "https://...firmware.bin",
 *   "changelog": "Deskripsi perubahan firmware"
 * }
 */
object FirmwareUpdateHelper {

    private const val TAG = "FirmwareUpdateHelper"

    // Supabase config (sama dengan OtaUpdateHelper)
    private const val SUPABASE_URL = "https://zlqkmedaupuqiqiwoxyw.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpscWttZWRhdXB1cWlxaXdveHl3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY2NjYzNTYsImV4cCI6MjA5MjI0MjM1Nn0.vGLBsZPUo2juN9Izs5lX4-Sck7acEU9UE5hJW-GJ3uc"

    // URL ke firmware.json di bucket "releases"
    private const val FIRMWARE_JSON_URL =
        "$SUPABASE_URL/storage/v1/object/public/releases/firmware.json"

    // Offset flash ESP32
    // CATATAN: 0x1000 (bootloader) dan 0x8000 (partitions) TIDAK bisa ditulis
    // via aplikasi biasa — hardware protected, ESP32 akan restart/crash.
    // Hanya firmware (0x10000) yang bisa diupdate via OTA protocol.
    private const val OFFSET_FIRMWARE    = "0x10000"

    // ─────────────────────────────────────────────────────────────
    // Data class
    // ─────────────────────────────────────────────────────────────

    data class FirmwareInfo(
        val version: String,
        val firmwareUrl: String,
        val changelog: String
    )

    data class DownloadedFirmware(
        val firmware: java.io.File
    )

    // ─────────────────────────────────────────────────────────────
    // Fetch firmware.json dari Supabase
    // ─────────────────────────────────────────────────────────────

    /**
     * Fetch firmware.json dari Supabase.
     * Return null jika gagal (network error, parse error, dst).
     */
    suspend fun fetchFirmwareInfo(): FirmwareInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(FIRMWARE_JSON_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout    = 8000
            conn.requestMethod  = "GET"
            conn.setRequestProperty("apikey", SUPABASE_KEY)

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "fetchFirmwareInfo HTTP $code — skip firmware check")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            FirmwareInfo(
                version    = json.getString("version"),
                firmwareUrl = json.getString("firmware_url"),
                changelog  = json.optString("changelog", "-")
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchFirmwareInfo error: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Cek apakah versi firmware cocok
    // ─────────────────────────────────────────────────────────────

    fun isUpdateNeeded(deviceVersion: String, serverVersion: String): Boolean {
        val dev = deviceVersion.trim()
        val srv = serverVersion.trim()
        if (dev.isEmpty() || srv.isEmpty()) return false
        return dev != srv
    }

    // ─────────────────────────────────────────────────────────────
    // Download satu file firmware
    // ─────────────────────────────────────────────────────────────

    private suspend fun downloadFile(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit = {}
    ): java.io.File? = withContext(Dispatchers.IO) {
        try {
            val outFile = java.io.File(context.cacheDir, fileName)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout    = 120000
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

            Log.i(TAG, "Downloaded $fileName → ${outFile.length()} bytes")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile [$fileName] error: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Download semua 3 file firmware
    // ─────────────────────────────────────────────────────────────

    /**
     * Download firmware bin dari Supabase.
     * [onProgress] dipanggil 0..100.
     * [onStatus] dipanggil dengan pesan status teks.
     * Return DownloadedFirmware jika sukses, null jika gagal.
     */
    suspend fun downloadAllFirmware(
        context: Context,
        info: FirmwareInfo,
        onProgress: (Int) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): DownloadedFirmware? {
        onStatus("Mengunduh firmware v${info.version}...")
        val firmware = downloadFile(
            context, info.firmwareUrl, "fw_main.bin"
        ) { onProgress(it) }
        if (firmware == null) { onStatus("Gagal unduh firmware."); return null }

        onProgress(100)
        onStatus("Firmware berhasil diunduh.")
        return DownloadedFirmware(firmware)
    }

    // ─────────────────────────────────────────────────────────────
    // Flash semua 3 file ke ESP32 via OTG Serial
    // ─────────────────────────────────────────────────────────────

    /**
     * Flash firmware ke ESP32 via USB OTG Serial ke offset 0x10000 (OTA slot).
     *
     * CATATAN: Bootloader (0x1000) dan partitions (0x8000) TIDAK diflash —
     * area tersebut hardware-protected, ESP32 akan restart jika dicoba tulis.
     * Update firmware biasa hanya butuh flash ke 0x10000.
     *
     * Protocol:
     *   → FW_FLASH_START:0x10000:<size>
     *   ← FW_FLASH_READY
     *   → FW_CHUNK:<index>:<base64>  (loop)
     *   ← FW_CHUNK_OK:<index>        (per chunk)
     *   → FW_FLASH_END
     *   ← FW_FLASH_DONE
     *   ESP32 restart otomatis setelah DONE.
     *
     * [onProgress] dipanggil 0..100.
     * Return true jika sukses.
     */
    suspend fun flashAllFirmware(
        files: DownloadedFirmware,
        sendCommand: (String) -> Unit,
        dataFlow: kotlinx.coroutines.flow.SharedFlow<String>,
        onProgress: (Int) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): Boolean {
        onStatus("Memflash firmware ke ESP32...")
        val ok = flashSingleFile(
            binFile     = files.firmware,
            offset      = OFFSET_FIRMWARE,
            sendCommand = sendCommand,
            dataFlow    = dataFlow,
            onProgress  = onProgress,
            onStatus    = onStatus
        )
        if (!ok) {
            onStatus("Gagal flash firmware.")
            return false
        }
        Log.i(TAG, "Firmware flash OK — ESP32 akan restart")
        onProgress(100)
        onStatus("Firmware berhasil diflash! ESP32 akan restart...")
        return true
    }

    // ─────────────────────────────────────────────────────────────
    // Flash satu file ke ESP32
    // ─────────────────────────────────────────────────────────────

    private suspend fun flashSingleFile(
        binFile: java.io.File,
        offset: String,
        sendCommand: (String) -> Unit,
        dataFlow: kotlinx.coroutines.flow.SharedFlow<String>,
        onProgress: (Int) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): Boolean {
        // Tidak pakai withContext(Dispatchers.IO) agar dataFlow.collect tidak miss response
        return try {
            val totalSize   = binFile.length()
            val chunkSize   = 256  // 512→256: cegah serial buffer overflow, Base64(256)=344 chars
            val totalChunks = ((totalSize + chunkSize - 1) / chunkSize).toInt()

            Log.i(TAG, "flashSingleFile offset=$offset size=$totalSize chunks=$totalChunks")

            // Baca semua bytes di IO thread dulu, baru proses di calling coroutine
            val allBytes = withContext(Dispatchers.IO) { binFile.readBytes() }

            // Step 1: FW_FLASH_START:<offset>:<size>
            sendCommand("FW_FLASH_START:$offset:$totalSize")

            // Step 2: Tunggu FW_FLASH_READY (timeout 15 detik)
            val ready = waitForResponse(dataFlow, "FW_FLASH_READY", 15_000)
            if (!ready) {
                onStatus("ESP32 tidak merespons FW_FLASH_READY (offset $offset).")
                return false
            }

            // Step 3: Kirim chunk per chunk
            var chunkIdx = 0
            while (chunkIdx * chunkSize < allBytes.size) {
                val start   = chunkIdx * chunkSize
                val end     = minOf(start + chunkSize, allBytes.size)
                val chunk   = allBytes.copyOfRange(start, end)
                val encoded = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)

                sendCommand("FW_CHUNK:$chunkIdx:$encoded")

                // Timeout 8 detik per chunk
                val ack = waitForResponse(dataFlow, "FW_CHUNK_OK:$chunkIdx", 8_000)
                if (!ack) {
                    onStatus("Timeout ACK chunk $chunkIdx/$totalChunks (offset $offset).")
                    Log.e(TAG, "ACK timeout chunk=$chunkIdx offset=$offset")
                    return false
                }

                chunkIdx++
                onProgress((chunkIdx * 100 / totalChunks).coerceIn(0, 99))

                // Delay 10ms antar chunk: beri waktu ESP32 proses & jaga serial buffer
                delay(10)
            }

            // Step 4: FW_FLASH_END
            sendCommand("FW_FLASH_END")

            val done = waitForResponse(dataFlow, "FW_FLASH_DONE", 20_000)
            if (!done) {
                onStatus("Timeout FW_FLASH_DONE (offset $offset).")
                return false
            }

            onProgress(100)
            true

        } catch (e: Exception) {
            Log.e(TAG, "flashSingleFile error offset=$offset: ${e.message}", e)
            onStatus("Error: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helper: tunggu response tertentu dari dataFlow
    // ─────────────────────────────────────────────────────────────

    private suspend fun waitForResponse(
        dataFlow: kotlinx.coroutines.flow.SharedFlow<String>,
        expectedPrefix: String,
        timeoutMs: Long
    ): Boolean {
        var found = false
        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                dataFlow.collect { line ->
                    if (line.startsWith(expectedPrefix)) {
                        found = true
                        throw kotlinx.coroutines.CancellationException("found")
                    }
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) { }
        return found
    }
}
