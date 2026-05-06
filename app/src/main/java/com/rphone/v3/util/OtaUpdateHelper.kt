package com.rphone.v3.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OtaUpdateHelper — Cek & download OTA update via Supabase Storage.
 *
 * Flow:
 *   1. fetchVersionInfo()  → ambil version.json dari Supabase
 *   2. Bandingkan versionCode vs BuildConfig.VERSION_CODE
 *   3. Kalau ada update → return OtaInfo, tampilkan dialog di Activity
 *   4. User tekan download → downloadApk()
 *   5. Trigger install via FileProvider
 *
 * v2: tambah fetchFirmwareInfo() untuk cek versi firmware ESP32
 */
object OtaUpdateHelper {

    private const val TAG = "OtaUpdateHelper"

    // Supabase config (sama dengan SupabaseUploader)
    private const val SUPABASE_URL = "https://zlqkmedaupuqiqiwoxyw.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpscWttZWRhdXB1cWlxaXdveHl3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY2NjYzNTYsImV4cCI6MjA5MjI0MjM1Nn0.vGLBsZPUo2juN9Izs5lX4-Sck7acEU9UE5hJW-GJ3uc"

    // URL ke version.json di bucket "releases"
    private const val VERSION_JSON_URL =
        "$SUPABASE_URL/storage/v1/object/public/releases/version.json"

    // URL ke firmware.json di bucket "releases"
    private const val FIRMWARE_JSON_URL =
        "$SUPABASE_URL/storage/v1/object/public/releases/firmware.json"

    // ─────────────────────────────────────────────────────────────
    // Data class hasil fetch
    // ─────────────────────────────────────────────────────────────

    data class OtaInfo(
        val version: String,        // e.g. "1.0.5"
        val versionCode: Int,       // e.g. 5
        val downloadUrl: String,    // URL APK
        val changelog: String       // deskripsi perubahan
    )

    data class FirmwareInfo(
        val version: String,        // e.g. "3.3.18"
        val downloadUrl: String,    // URL firmware .bin
        val changelog: String       // deskripsi perubahan
    )

    // ─────────────────────────────────────────────────────────────
    // Fetch version.json dari Supabase (App OTA)
    // ─────────────────────────────────────────────────────────────

    /**
     * Fetch version.json dari Supabase.
     * Return null jika gagal (network error, server error, JSON parse error).
     */
    suspend fun fetchVersionInfo(): OtaInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(VERSION_JSON_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout    = 8000
            conn.requestMethod  = "GET"
            conn.setRequestProperty("apikey", SUPABASE_KEY)

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "fetchVersionInfo HTTP $code — skip OTA check")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            OtaInfo(
                version     = json.getString("version"),
                versionCode = json.getInt("versionCode"),
                downloadUrl = json.getString("url"),
                changelog   = json.optString("changelog", "-")
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchVersionInfo error: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Fetch firmware.json dari Supabase (Firmware OTA)
    // ─────────────────────────────────────────────────────────────

    /**
     * Fetch firmware.json dari Supabase.
     * Return null jika gagal.
     *
     * Format firmware.json:
     * {
     *   "version": "3.3.18",
     *   "url": "https://...supabase.co/storage/.../firmware.bin",
     *   "changelog": "Deskripsi perubahan firmware"
     * }
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
                version     = json.getString("version"),
                downloadUrl = json.getString("url"),
                changelog   = json.optString("changelog", "-")
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchFirmwareInfo error: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Download APK ke cache
    // ─────────────────────────────────────────────────────────────

    /**
     * Download APK dari [url] ke file cache internal.
     * Return path File jika sukses, null jika gagal.
     * [onProgress] dipanggil dengan nilai 0..100.
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String = "update.apk",
        onProgress: (Int) -> Unit = {}
    ): java.io.File? = withContext(Dispatchers.IO) {
        try {
            val outFile = java.io.File(context.cacheDir, fileName)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout    = 60000
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
                            val progress = (downloaded * 100 / total).toInt()
                            onProgress(progress)
                        }
                    }
                }
            }

            Log.i(TAG, "APK downloaded → ${outFile.absolutePath}")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk error: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Download firmware .bin ke cache
    // ─────────────────────────────────────────────────────────────

    /**
     * Download firmware .bin dari [url] ke file cache internal.
     * Return path File jika sukses, null jika gagal.
     * [onProgress] dipanggil dengan nilai 0..100.
     */
    suspend fun downloadFirmware(
        context: Context,
        url: String,
        fileName: String = "firmware_update.bin",
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
                            val progress = (downloaded * 100 / total).toInt()
                            onProgress(progress)
                        }
                    }
                }
            }

            Log.i(TAG, "Firmware downloaded → ${outFile.absolutePath} (${outFile.length()} bytes)")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "downloadFirmware error: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Trigger install APK
    // ─────────────────────────────────────────────────────────────

    /**
     * Trigger install APK via FileProvider + ACTION_VIEW.
     * Pastikan permission REQUEST_INSTALL_PACKAGES ada di manifest.
     */
    fun installApk(context: Context, apkFile: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "installApk error: ${e.message}", e)
        }
    }
}
