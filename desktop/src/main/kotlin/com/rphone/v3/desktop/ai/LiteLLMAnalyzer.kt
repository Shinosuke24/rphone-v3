package com.rphone.v3.desktop.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object LiteLLMAnalyzer {
    private const val DEFAULT_BASE_URL = "https://api.koboillm.com/v1"
    private const val CONNECT_TIMEOUT = 30_000
    private const val READ_TIMEOUT = 120_000

    suspend fun analisa(systemPrompt: String, userMessage: String): Result<String> {
        val cfg = AiConfigStore.load()
        val model = cfg.model
        val body = buildBody(systemPrompt, userMessage, model)
        return kirim(body)
    }

    suspend fun analisa(prompt: String): Result<String> {
        val cfg = AiConfigStore.load()
        val model = cfg.model
        val body = buildBody(systemPrompt = null, userMessage = prompt, model = model)
        return kirim(body)
    }

    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val cfg = AiConfigStore.load()
        val apiKey = cfg.apiKey
        val baseUrl = cfg.baseUrl.ifBlank { DEFAULT_BASE_URL }.removeSuffix("/")

        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API key LiteLLM belum diisi"))
        }

        try {
            val conn = URL("$baseUrl/models").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            if (conn.responseCode != 200) {
                val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode}: $errBody"))
            }

            val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json = JSONObject(responseBody)
            val data = json.getJSONArray("data")
            val models = mutableListOf<String>()
            for (i in 0 until data.length()) {
                models.add(data.getJSONObject(i).getString("id"))
            }
            Result.success(models.sorted())
        } catch (e: Exception) {
            Result.failure(Exception("Gagal mengambil daftar model: ${e.message}"))
        }
    }

    private fun buildBody(systemPrompt: String?, userMessage: String, model: String): JSONObject {
        val messages = JSONArray()
        if (!systemPrompt.isNullOrBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
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

    private suspend fun kirim(body: JSONObject): Result<String> = withContext(Dispatchers.IO) {
        val cfg = AiConfigStore.load()
        val apiKey = cfg.apiKey
        val baseUrl = cfg.baseUrl.ifBlank { DEFAULT_BASE_URL }.removeSuffix("/")
        val model = cfg.model

        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API key LiteLLM belum diisi di Settings"))
        }
        if (model.isBlank()) {
            return@withContext Result.failure(Exception("Model LiteLLM belum dipilih di Settings"))
        }

        try {
            val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

            if (conn.responseCode == 200) {
                val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val json = JSONObject(responseBody)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                return@withContext if (content.isNotBlank()) Result.success(content)
                else Result.failure(Exception("Respons LiteLLM kosong"))
            }

            val errBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            Result.failure(Exception("HTTP ${conn.responseCode}: $errBody"))
        } catch (e: Exception) {
            Result.failure(Exception("Gagal menghubungi LiteLLM: ${e.message}"))
        }
    }
}
