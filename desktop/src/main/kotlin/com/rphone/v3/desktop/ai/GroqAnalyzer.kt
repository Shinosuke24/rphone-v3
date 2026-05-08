package com.rphone.v3.desktop.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GroqAnalyzer {
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val TIMEOUT_MS = 120_000

    private val modelFallback = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "gemma2-9b-it"
    )

    suspend fun analisa(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val cfg = AiConfigStore.load()
        val apiKey = cfg.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API key Groq belum diisi di Settings"))
        }

        for ((idx, modelName) in modelFallback.withIndex()) {
            try {
                val content = kirimRequest(apiKey, modelName, prompt)
                if (!content.isNullOrBlank()) {
                    val info = if (idx > 0) "\n\n[Model: $modelName]" else ""
                    return@withContext Result.success(content + info)
                }
            } catch (_: Exception) {
            }
        }

        Result.failure(Exception("Semua model Groq gagal. Coba provider lain di Settings."))
    }

    private fun kirimRequest(apiKey: String, model: String, prompt: String): String? {
        val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
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

            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

            val code = conn.responseCode
            if (code == 200) {
                val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val json = JSONObject(responseBody)
                return json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }
}
