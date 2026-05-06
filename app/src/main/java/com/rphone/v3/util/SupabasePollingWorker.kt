package com.rphone.v3.util

import android.content.Context
import android.util.Log
import androidx.work.*
import com.rphone.v3.waveid.database.WaveIDDatabase
import com.rphone.v3.waveid.model.ProfilArus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class SupabasePollingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG            = "SupabasePollingWorker"
        private const val SUPABASE_URL   = "https://zlqkmedaupuqiqiwoxyw.supabase.co"
        private const val SUPABASE_KEY   = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpscWttZWRhdXB1cWlxaXdveHl3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY2NjYzNTYsImV4cCI6MjA5MjI0MjM1Nn0.vGLBsZPUo2juN9Izs5lX4-Sck7acEU9UE5hJW-GJ3uc"
        private const val BUCKET         = "r-phone-v3"
        private const val PREF_NAME      = "rphone_prefs"
        private const val PREF_LAST_SYNC = "supabase_last_sync_ts"
        private const val KEY_USERNAME   = "username"

        // Key untuk in-app banner — dibaca oleh MainActivity
        const val PREF_BANNER_TITLE    = "supabase_banner_title"
        const val PREF_BANNER_SUBTITLE = "supabase_banner_subtitle"
        const val PREF_BANNER_VISIBLE  = "supabase_banner_visible"

        const val WORK_NAME      = "supabase_polling"
        const val KEY_FULL_SYNC  = "full_sync"

        fun jadwalkan(context: Context) {
            val request = PeriodicWorkRequestBuilder<SupabasePollingWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "PeriodicWork dijadwalkan (15 menit)")
        }

        fun triggerSekarang(context: Context) {
            val request = OneTimeWorkRequestBuilder<SupabasePollingWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Manual trigger SupabasePollingWorker")
        }

        fun triggerFullSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<SupabasePollingWorker>()
                .setInputData(
                    androidx.work.workDataOf(KEY_FULL_SYNC to true)
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Full sync trigger SupabasePollingWorker")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val isFullSync = inputData.getBoolean(KEY_FULL_SYNC, false)
            val prefs      = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            var lastSync   = prefs.getLong(PREF_LAST_SYNC, 0L)
            val myUsername = prefs.getString(KEY_USERNAME, "") ?: ""

            val db      = WaveIDDatabase.getInstance(applicationContext).profilArusDao()
            val folders = listOf("USB", "PSU", "WAVE")

            if (isFullSync) {
                // Full sync: hapus semua lokal dulu, lalu tarik ulang semua dari server
                Log.d(TAG, "Mode: FULL SYNC — hapus semua lokal lalu tarik dari server")
                db.deleteAll()
                lastSync = 0L
            } else if (lastSync == 0L) {
                // Pertama kali: set baseline 1 jam ke belakang
                lastSync = System.currentTimeMillis() - (60 * 60 * 1000L)
                Log.d(TAG, "First run — baseline 1 jam ke belakang")
            }

            // Kumpulkan semua data baru untuk ditampilkan di banner
            // Jika > 1 file baru, banner tampilkan yang terbaru saja
            var latestUsername = ""
            var latestBrand    = ""
            var latestModel    = ""
            var latestKondisi  = ""
            var adaDataBaru    = false

            for (folder in folders) {
                val items = listFolder("$folder/") ?: continue

                for (i in 0 until items.length()) {
                    val item      = items.getJSONObject(i)
                    val namaFile  = item.optString("name", "")
                    val createdAt = parseIsoToMillis(item.optString("created_at", ""))

                    if (namaFile.isBlank() || createdAt <= lastSync) continue

                    val path       = "$folder/$namaFile"
                    val jsonString = downloadFile(path) ?: continue

                    try {
                        val profil = jsonToProfil(jsonString, folder)
                        if (profil.username == myUsername) continue

                        // Dedup: skip kalau namaFile sudah ada di DB lokal
                        if (profil.namaFile.isNotBlank() &&
                            db.existsByNamaFile(profil.namaFile) > 0) {
                            Log.d(TAG, "Skip duplikat: ${profil.namaFile}")
                            continue
                        }

                        db.insertInternal(profil)

                        // Kirim system notification
                        SupabaseNotifHelper.kirimNotif(
                            applicationContext,
                            profil.username,
                            profil.brand,
                            profil.model,
                            profil.kondisi
                        )

                        // Simpan untuk banner in-app (ambil yang paling baru)
                        if (!adaDataBaru) {
                            latestUsername = profil.username
                            latestBrand    = profil.brand
                            latestModel    = profil.model
                            latestKondisi  = profil.kondisi
                        }
                        adaDataBaru = true

                        Log.i(TAG, "Sync OK: ${profil.brand} ${profil.model} dari ${profil.username}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Parse/insert gagal [$namaFile]: ${e.message}")
                    }
                }
            }

            // Simpan state banner ke SharedPreferences → MainActivity akan baca
            if (adaDataBaru) {
                prefs.edit()
                    .putString(PREF_BANNER_TITLE, "$latestUsername upload data baru")
                    .putString(PREF_BANNER_SUBTITLE, "$latestBrand $latestModel — $latestKondisi")
                    .putBoolean(PREF_BANNER_VISIBLE, true)
                    .apply()
                Log.i(TAG, "Banner state disimpan → visible")
            }

            prefs.edit().putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "doWork error: ${e.message}", e)
            Result.retry()
        }
    }

    // ── HTTP: List folder ────────────────────────────────────────

    private fun listFolder(prefix: String): JSONArray? {
        val url  = "$SUPABASE_URL/storage/v1/object/list/$BUCKET"
        val body = JSONObject().apply {
            put("prefix", prefix)
            put("limit", 100)
            put("offset", 0)
            put("sortBy", JSONObject().apply {
                put("column", "created_at")
                put("order", "desc")
            })
        }.toString()

        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))

            if (conn.responseCode in 200..299) {
                JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                Log.e(TAG, "listFolder gagal HTTP ${conn.responseCode} prefix=$prefix")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "listFolder exception [$prefix]: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ── HTTP: Download file ──────────────────────────────────────

    private fun downloadFile(path: String): String? {
        val conn = URL("$SUPABASE_URL/storage/v1/object/$BUCKET/$path")
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("apikey", SUPABASE_KEY)

            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.e(TAG, "downloadFile gagal HTTP ${conn.responseCode} path=$path")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile exception [$path]: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ── Parse JSON → ProfilArus ──────────────────────────────────

    private fun jsonToProfil(jsonString: String, folder: String): ProfilArus {
        val j = JSONObject(jsonString)
        return ProfilArus(
            id           = 0,
            brand        = j.optString("brand"),
            model        = j.optString("model"),
            kondisi      = j.optString("kondisi"),
            username     = j.optString("username"),
            tanggal      = j.optLong("tanggal"),
            durasiMs     = j.optLong("durasiMs"),
            tegangan     = j.optDouble("tegangan", 0.0).toFloat(),
            puncakArus   = j.optDouble("puncakArus", 0.0).toFloat(),
            rataArus     = j.optDouble("rataArus", 0.0).toFloat(),
            minArus      = j.optDouble("minArus", 0.0).toFloat(),
            puncakDaya   = j.optDouble("puncakDaya", 0.0).toFloat(),
            waveformJson = j.optString("waveformJson", "[]"),
            faseJson     = j.optString("faseJson", "[]"),
            sumber       = "cloud",
            namaFile     = j.optString("namaFile"),
            modeRekam    = folder.uppercase(),
            dpAvg        = j.optDouble("dpAvg", 0.0).toFloat(),
            dmAvg        = j.optDouble("dmAvg", 0.0).toFloat(),
            // ── Multi-channel USB (Task 24) — wajib dibaca dari cloud ──
            voltWaveformJson = j.optString("voltWaveformJson", "[]"),
            dpWaveformJson   = j.optString("dpWaveformJson",   "[]"),
            dmWaveformJson   = j.optString("dmWaveformJson",   "[]"),
            puncakVolt   = j.optDouble("puncakVolt",  0.0).toFloat(),
            avgVolt      = j.optDouble("avgVolt",     0.0).toFloat(),
            puncakDp     = j.optDouble("puncakDp",    0.0).toFloat(),
            avgDp        = j.optDouble("avgDp",       0.0).toFloat(),
            puncakDm     = j.optDouble("puncakDm",    0.0).toFloat(),
            avgDm        = j.optDouble("avgDm",       0.0).toFloat()
        )
    }

    // ── Util: ISO 8601 → millis ──────────────────────────────────

    private fun parseIsoToMillis(iso: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(iso.substringBefore("."))?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
