package com.rphone.v3.ai

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ClaudeAnalyzer {

    private const val TAG            = "ClaudeAnalyzer"
    private const val ENC_PREF_NAME  = "rphone_ai_prefs"
    private const val KEY_API_KEY    = "claude_api_key"
    private const val CLAUDE_MODEL   = "claude-sonnet-4-6"
    private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"

    private const val SYSTEM_PROMPT =
        "Kamu adalah teknisi senior reparasi smartphone dengan keahlian mendalam " +
        "dalam diagnosis kerusakan hardware: PMIC, IC charging, eMMC, power rail, " +
        "dan analisa sinyal arus. Berikan analisa singkat, akurat, dan actionable " +
        "dalam Bahasa Indonesia sesuai format yang diminta."

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun getApiKey(context: Context): String {
        val appCtx = context.applicationContext  // FIX: pakai applicationContext
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
            try {
                val apiKey = getApiKey(context)
                Log.d(TAG, "analisa: apiKey blank = ${apiKey.isBlank()}")

                if (apiKey.isBlank()) return@withContext Result.failure(
                    Exception("API key Claude belum diisi di Settings"))

                val systemArray = JSONArray().put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", SYSTEM_PROMPT)
                        put("cache_control", JSONObject().put("type", "ephemeral"))
                    }
                )

                val body = JSONObject().apply {
                    put("model",      CLAUDE_MODEL)
                    put("max_tokens", 4096)
                    put("system",     systemArray)
                    put("messages", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        }
                    ))
                }.toString()

                val request = Request.Builder()
                    .url(CLAUDE_API_URL)
                    .addHeader("x-api-key",            apiKey)
                    .addHeader("anthropic-version",    "2023-06-01")
                    .addHeader("anthropic-beta",       "prompt-caching-2024-07-31")
                    .addHeader("Content-Type",         "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response     = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "Claude API error ${response.code}: $responseBody")
                    return@withContext Result.failure(
                        Exception("Claude API error ${response.code}: $responseBody"))
                }

                val json = JSONObject(responseBody)
                val teks = json.getJSONArray("content")
                    .getJSONObject(0)
                    .getString("text")
                Result.success(teks)
            } catch (e: Exception) {
                Log.e(TAG, "analisa EXCEPTION: ${e::class.simpleName} — ${e.message}")
                Result.failure(e)
            }
        }
}
