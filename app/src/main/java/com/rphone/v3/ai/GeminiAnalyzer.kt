package com.rphone.v3.ai

import android.content.Context
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

object GeminiAnalyzer {

    private const val ENC_PREF_NAME = "rphone_ai_prefs"
    private const val KEY_API_KEY   = "gemini_api_key"
    private const val GEMINI_MODEL  = "gemini-2.0-flash"
    private const val GEMINI_API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"

    private const val SYSTEM_PROMPT =
        "Kamu adalah teknisi senior reparasi smartphone dengan keahlian mendalam " +
        "dalam diagnosis kerusakan hardware: PMIC, IC charging, eMMC, power rail, " +
        "dan analisa sinyal arus. Berikan analisa singkat, akurat, dan actionable " +
        "dalam Bahasa Indonesia sesuai format yang diminta."

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // FIX: gunakan applicationContext agar EncryptedSharedPreferences
    // buka file yang sama saat simpan (dari Settings) dan baca (dari sini)
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
            prefs.getString(KEY_API_KEY, "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun analisa(context: Context, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = getApiKey(context)
                if (apiKey.isBlank()) return@withContext Result.failure(
                    Exception("API key Gemini belum diisi di Settings"))

                val body = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().put(
                            JSONObject().put("text", SYSTEM_PROMPT)
                        ))
                    })
                    put("contents", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(
                                JSONObject().put("text", prompt)
                            ))
                        }
                    ))
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 4096)
                        put("temperature", 0.7)
                    })
                }.toString()

                val url = "$GEMINI_API_URL?key=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response     = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(responseBody)
                            .getJSONObject("error")
                            .getString("message")
                    } catch (e: Exception) {
                        responseBody
                    }
                    return@withContext Result.failure(
                        Exception("Gemini API error ${response.code}: $errMsg"))
                }

                val json = JSONObject(responseBody)
                val teks = json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                Result.success(teks)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
