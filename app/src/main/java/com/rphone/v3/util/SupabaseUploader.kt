package com.rphone.v3.util

import android.util.Log
import com.rphone.v3.waveid.model.ProfilArus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Upload ProfilArus ke Supabase Storage.
 * Tidak memerlukan dependency eksternal — murni Android SDK.
 *
 * Struktur path di bucket "r-phone-v3":
 *   USB/   → mode USB
 *   PSU/   → mode PSU
 *   WAVE/  → mode WAVE/default
 */
object SupabaseUploader {

    private const val TAG        = "SupabaseUploader"
    private const val SUPABASE_URL  = "https://zlqkmedaupuqiqiwoxyw.supabase.co"
    private const val SUPABASE_KEY  = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpscWttZWRhdXB1cWlxaXdveHl3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY2NjYzNTYsImV4cCI6MjA5MjI0MjM1Nn0.vGLBsZPUo2juN9Izs5lX4-Sck7acEU9UE5hJW-GJ3uc"
    private const val BUCKET     = "r-phone-v3"

    // ─────────────────────────────────────────────────────────────
    // PUBLIC
    // ─────────────────────────────────────────────────────────────

    /**
     * Upload UART log (.txt) ke Supabase Storage.
     * Path: UART/uart_log_<timestamp>_<deviceId>.txt
     *
     * @param logText  Isi log lengkap dari UartViewModel.getFullLogText()
     * @param fileName Nama file (e.g. "uart_log_20250423_143000.txt")
     */
    suspend fun uploadUartLog(logText: String, fileName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val path = "UART/$fileName"
                uploadText(path, logText)
            } catch (e: Exception) {
                Log.e(TAG, "uploadUartLog error: ${e.message}", e)
                false
            }
        }
    }

    suspend fun uploadProfil(profil: ProfilArus): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val subFolder = resolveFolder(profil.modeRekam)
                val fileName  = buildFileName(profil)
                val path      = "$subFolder/$fileName"
                val content   = profilToJson(profil)
                uploadJson(path, content)
            } catch (e: Exception) {
                Log.e(TAG, "uploadProfil error: ${e.message}", e)
                false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Upload
    // ─────────────────────────────────────────────────────────────

    private fun uploadText(path: String, content: String): Boolean {
        val uploadUrl = "$SUPABASE_URL/storage/v1/object/$BUCKET/$path"
        val conn = URL(uploadUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.write(content.toByteArray(Charsets.UTF_8))

            val code = conn.responseCode
            if (code in 200..299) {
                Log.i(TAG, "Upload OK → $path (HTTP $code)")
                true
            } else {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "no body"
                Log.e(TAG, "Upload GAGAL → $path | HTTP $code | $errBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadText error: ${e.message}", e)
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadJson(path: String, content: String): Boolean {
        val uploadUrl = "$SUPABASE_URL/storage/v1/object/$BUCKET/$path"
        val conn = URL(uploadUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(content.toByteArray(Charsets.UTF_8))

            val code = conn.responseCode
            if (code in 200..299) {
                Log.i(TAG, "Upload OK → $path (HTTP $code)")
                true
            } else {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "no body"
                Log.e(TAG, "Upload GAGAL → $path | HTTP $code | $errBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadJson error: ${e.message}", e)
            false
        } finally {
            conn.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────

    private fun resolveFolder(mode: String): String = when (mode.uppercase()) {
        "USB"  -> "USB"
        "PSU"  -> "PSU"
        else   -> "WAVE"
    }

    private fun buildFileName(profil: ProfilArus): String {
        val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(profil.tanggal))
        val slug = "${profil.brand}_${profil.model}_${profil.kondisi}"
            .replace(" ", "_")
            .filter { it.isLetterOrDigit() || it == '_' }
        return "${slug}_${ts}_id${profil.id}.json"
    }

    private fun profilToJson(profil: ProfilArus): String {
        return JSONObject().apply {
            put("id",           profil.id)
            put("brand",        profil.brand)
            put("model",        profil.model)
            put("kondisi",      profil.kondisi)
            put("username",     profil.username)
            put("tanggal",      profil.tanggal)
            put("durasiMs",     profil.durasiMs)
            put("tegangan",     profil.tegangan)
            put("puncakArus",   profil.puncakArus)
            put("rataArus",     profil.rataArus)
            put("minArus",      profil.minArus)
            put("puncakDaya",   profil.puncakDaya)
            put("waveformJson", profil.waveformJson)
            put("faseJson",     profil.faseJson)
            put("sumber",       profil.sumber)
            put("namaFile",     profil.namaFile)
            put("modeRekam",    profil.modeRekam)
            put("dpAvg",        profil.dpAvg)
            put("dmAvg",        profil.dmAvg)
            put("voltWaveformJson", profil.voltWaveformJson)
            put("dpWaveformJson",   profil.dpWaveformJson)
            put("dmWaveformJson",   profil.dmWaveformJson)
            put("puncakVolt",   profil.puncakVolt)
            put("avgVolt",      profil.avgVolt)
            put("puncakDp",     profil.puncakDp)
            put("avgDp",        profil.avgDp)
            put("puncakDm",     profil.puncakDm)
            put("avgDm",        profil.avgDm)
        }.toString()
    }
}
