package com.rphone.v3.ai

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GroqAnalyzer {

    private const val TAG           = "GroqAnalyzer"
    private const val ENC_PREF_NAME = "rphone_ai_prefs"
    private const val KEY_API_KEY   = "groq_api_key"
    private const val GROQ_URL      = "https://api.groq.com/openai/v1/chat/completions"
    private const val TIMEOUT_MS    = 120_000

    private val MODEL_FALLBACK = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "gemma2-9b-it"
    )

    fun getApiKey(context: Context): String {
        val appCtx = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                appCtx,
                ENC_PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val key = prefs.getString(KEY_API_KEY, "") ?: ""
            Log.d(TAG, "getApiKey: panjang key = ${key.length}, kosong = ${key.isBlank()}")
            key
        } catch (e: Exception) {
            Log.e(TAG, "getApiKey EXCEPTION: ${e::class.simpleName} — ${e.message}")
            ""
        }
    }

    suspend fun analisa(context: Context, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            val apiKey = getApiKey(context)
            Log.d(TAG, "analisa: apiKey blank = ${apiKey.isBlank()}")

            if (apiKey.isBlank()) return@withContext Result.failure(
                Exception("API key Groq belum diisi di Settings\n\nDapatkan API key gratis di console.groq.com")
            )

            for ((index, modelName) in MODEL_FALLBACK.withIndex()) {
                Log.d(TAG, "Mencoba model: $modelName")
                try {
                    val result = kirimRequest(apiKey, modelName, prompt)
                    if (result != null) {
                        Log.d(TAG, "Berhasil dengan model: $modelName")
                        val info = if (index > 0) "\n\n[Model: $modelName]" else ""
                        return@withContext Result.success(result + info)
                    }
                    Log.w(TAG, "Model $modelName: response kosong, coba model berikutnya")
                    continue
                } catch (e: Exception) {
                    Log.e(TAG, "Model $modelName gagal: ${e::class.simpleName} — ${e.message}")
                    if (index == MODEL_FALLBACK.lastIndex) {
                        return@withContext Result.failure(
                            Exception(
                                "Semua model Groq gagal.\n" +
                                "Kemungkinan: API key salah, rate limit, atau koneksi bermasalah.\n" +
                                "Coba lagi nanti atau ganti provider di Settings."
                            )
                        )
                    }
                    continue
                }
            }

            Result.failure(Exception("Tidak ada jawaban dari Groq. Coba lagi nanti."))
        }

    private fun kirimRequest(apiKey: String, model: String, prompt: String): String? {
        val url = URL(GROQ_URL)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod        = "POST"
            conn.connectTimeout       = TIMEOUT_MS
            conn.readTimeout          = TIMEOUT_MS
            conn.doOutput             = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 4096)
                put("temperature", 0.7)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            Log.d(TAG, "HTTP $responseCode untuk model $model")

            if (responseCode == 200) {
                val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val json = JSONObject(responseBody)
                return json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                    .takeIf { it.isNotBlank() }
            } else {
                val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                Log.e(TAG, "Error $responseCode: $errBody")
                if (responseCode == 429 || responseCode == 404) return null
                throw Exception("HTTP $responseCode: $errBody")
            }
        } finally {
            conn.disconnect()
        }
    }
}
