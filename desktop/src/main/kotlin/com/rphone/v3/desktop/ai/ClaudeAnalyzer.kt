package com.rphone.v3.desktop.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ClaudeAnalyzer {
    private const val CLAUDE_MODEL = "claude-sonnet-4-6"
    private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"

    private const val SYSTEM_PROMPT =
        "Kamu adalah teknisi senior reparasi smartphone. Berikan analisa singkat, akurat, actionable dalam Bahasa Indonesia."

    suspend fun analisa(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val cfg = AiConfigStore.load()
        val apiKey = cfg.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API key Claude belum diisi di Settings"))
        }

        try {
            val systemArray = JSONArray().put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", SYSTEM_PROMPT)
                    put("cache_control", JSONObject().put("type", "ephemeral"))
                }
            )

            val body = JSONObject().apply {
                put("model", CLAUDE_MODEL)
                put("max_tokens", 4096)
                put("system", systemArray)
                put("messages", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                ))
            }

            val conn = URL(CLAUDE_API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("anthropic-beta", "prompt-caching-2024-07-31")
            conn.setRequestProperty("Content-Type", "application/json")

            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

            val responseBody = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } else {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            }

            if (conn.responseCode !in 200..299) {
                return@withContext Result.failure(Exception("Claude API error ${conn.responseCode}: $responseBody"))
            }

            val json = JSONObject(responseBody)
            val teks = json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
            Result.success(teks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
