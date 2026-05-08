package com.rphone.v3.desktop.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiAnalyzer {
    private const val GEMINI_MODEL = "gemini-2.0-flash"
    private const val GEMINI_API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"

    private const val SYSTEM_PROMPT =
        "Kamu adalah teknisi senior reparasi smartphone. Berikan analisa singkat, akurat, actionable dalam Bahasa Indonesia."

    suspend fun analisa(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val cfg = AiConfigStore.load()
        val apiKey = cfg.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API key Gemini belum diisi di Settings"))
        }

        try {
            val body = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
                })
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 4096)
                    put("temperature", 0.7)
                })
            }

            val conn = URL("$GEMINI_API_URL?key=$apiKey").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

            val responseBody = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } else {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            }

            if (conn.responseCode !in 200..299) {
                return@withContext Result.failure(Exception("Gemini API error ${conn.responseCode}: $responseBody"))
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
