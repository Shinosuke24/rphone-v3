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

object LiteLLMAnalyzer {

    private const val TAG           = "LiteLLMAnalyzer"
    private const val ENC_PREF_NAME = "rphone_ai_prefs"

    private const val KEY_API_KEY   = "litellm_api_key"
    private const val KEY_BASE_URL  = "litellm_base_url"
    private const val KEY_MODEL     = "litellm_model"

    private const val DEFAULT_BASE_URL = "https://api.koboillm.com/v1"

    private const val CONNECT_TIMEOUT = 30_000
    private const val READ_TIMEOUT    = 120_000

    // ── Shared Prefs ──────────────────────────────────────────────────────────

    private fun getPrefs(context: Context): android.content.SharedPreferences? {
        val appCtx = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appCtx,
                ENC_PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "getPrefs EXCEPTION: ${e.message}")
            null
        }
    }

    fun getApiKey(context: Context): String  = getPrefs(context)?.getString(KEY_API_KEY,  "") ?: ""
    fun getBaseUrl(context: Context): String = getPrefs(context)?.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    fun getModel(context: Context): String   = getPrefs(context)?.getString(KEY_MODEL,    "") ?: ""

    // ── Build request body ────────────────────────────────────────────────────

    /**
     * Build JSONObject body untuk /chat/completions.
     *
     * @param systemPrompt  Teks system prompt (statis per tab). Dikirim dengan
     *                      cache_control ephemeral agar LiteLLM bisa cache-nya.
     *                      Jika null/blank → tidak ada system message (mode lama).
     * @param userMessage   Teks user message (dinamis — data teknisi).
     * @param model         Model ID dari settings.
     */
    private fun buildBody(
        systemPrompt: String?,
        userMessage: String,
        model: String
    ): JSONObject {
        val messages = JSONArray()

        // System message (plain string — LiteLLM handles caching server-side;
        // cache_control blocks are Anthropic-native only and break Gemini/Vertex)
        if (!systemPrompt.isNullOrBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // User message (selalu ada)
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        return JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            put("temperature", 0.7)
            put("messages", messages)
        }
    }

    // ── Core HTTP call ────────────────────────────────────────────────────────

    private suspend fun kirim(context: Context, body: JSONObject): Result<String> =
        withContext(Dispatchers.IO) {
            val apiKey  = getApiKey(context)
            val baseUrl = getBaseUrl(context).removeSuffix("/")
            val model   = getModel(context)

            if (baseUrl.isBlank() || apiKey.isBlank()) {
                return@withContext Result.failure(Exception("Base URL / API key LiteLLM belum diisi di Settings"))
            }
            if (model.isBlank()) {
                return@withContext Result.failure(Exception("Model LiteLLM belum dipilih. Tekan FETCH MODELS di Settings."))
            }

            try {
                val url  = URL("$baseUrl/chat/completions")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout    = READ_TIMEOUT
                conn.doOutput       = true
                conn.setRequestProperty("Content-Type",  "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val json    = JSONObject(responseBody)
                    val content = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()

                    if (content.isNotBlank()) Result.success(content)
                    else Result.failure(Exception("Respons dari LiteLLM kosong."))
                } else {
                    val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                    Log.e(TAG, "Error $responseCode: $errBody")
                    Result.failure(Exception("HTTP $responseCode: $errBody"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "analisa EXCEPTION: ${e.message}")
                Result.failure(Exception("Gagal menghubungi LiteLLM: ${e.message}"))
            }
        }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * [BARU] Analisa dengan system prompt terpisah + caching.
     * Dipakai oleh tab USB, PSU, dan UART setelah Task 11 selesai.
     *
     * @param systemPrompt  Prompt statis per tab (persona teknisi). Tidak boleh
     *                      mengandung data dinamis agar caching efektif.
     * @param userMessage   Data teknisi yang berubah tiap sesi
     *                      (nilai arus, hasil parsing UART, keluhan, dll).
     */
    suspend fun analisa(
        context: Context,
        systemPrompt: String,
        userMessage: String
    ): Result<String> {
        val model = getModel(context)
        val body  = buildBody(systemPrompt, userMessage, model)
        return kirim(context, body)
    }

    /**
     * [LAMA — dipertahankan] Analisa dengan single prompt string.
     * Masih dipakai oleh WaveAnalyzer / kode lain yang belum migrasi.
     */
    suspend fun analisa(context: Context, prompt: String): Result<String> {
        val model = getModel(context)
        val body  = buildBody(systemPrompt = null, userMessage = prompt, model = model)
        return kirim(context, body)
    }

    // ── Fetch Models ──────────────────────────────────────────────────────────

    suspend fun fetchModels(context: Context): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val apiKey  = getApiKey(context)
            val baseUrl = getBaseUrl(context).removeSuffix("/")

            if (baseUrl.isBlank() || apiKey.isBlank()) {
                return@withContext Result.failure(Exception("Base URL / API key LiteLLM belum diisi"))
            }

            try {
                val url  = URL("$baseUrl/models")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout    = READ_TIMEOUT
                conn.setRequestProperty("Authorization", "Bearer $apiKey")

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val json  = JSONObject(responseBody)
                    val data  = json.getJSONArray("data")
                    val models = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        models.add(data.getJSONObject(i).getString("id"))
                    }
                    Result.success(models.sorted())
                } else {
                    val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                    Log.e(TAG, "fetchModels Error $responseCode: $errBody")
                    Result.failure(Exception("HTTP $responseCode: $errBody"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchModels EXCEPTION: ${e.message}")
                Result.failure(Exception("Gagal mengambil daftar model: ${e.message}"))
            }
        }
}
