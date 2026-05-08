package com.rphone.v3.desktop.ai

import org.json.JSONObject
import java.io.File

data class DesktopAiConfig(
    val provider: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String
)

object AiConfigStore {
    private val dataDir: File = File(System.getProperty("user.home"), ".rphone-v3")
    private val aiFile: File = File(dataDir, "ai_settings.json")

    fun load(): DesktopAiConfig {
        if (!aiFile.exists()) {
            return DesktopAiConfig(
                provider = "liteLLM",
                apiKey = "",
                baseUrl = "https://api.koboillm.com/v1",
                model = ""
            )
        }

        return try {
            val json = JSONObject(aiFile.readText())
            DesktopAiConfig(
                provider = json.optString("provider", "liteLLM"),
                apiKey = json.optString("apiKey", ""),
                baseUrl = json.optString("baseUrl", "https://api.koboillm.com/v1"),
                model = json.optString("model", "")
            )
        } catch (_: Exception) {
            DesktopAiConfig(
                provider = "liteLLM",
                apiKey = "",
                baseUrl = "https://api.koboillm.com/v1",
                model = ""
            )
        }
    }
}
