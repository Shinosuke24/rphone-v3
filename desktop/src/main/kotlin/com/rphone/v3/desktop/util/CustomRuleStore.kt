package com.rphone.v3.desktop.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.rphone.v3.desktop.ai.UartAiAnalyzer

/**
 * Desktop port of APK's CustomRuleStore.kt
 * Stores and manages custom UART parsing rules using JSON serialization.
 */
class CustomRuleStore(private val filePath: String) {
    private val gson = Gson()

    data class UartRule(
        val id: String = java.util.UUID.randomUUID().toString(),
        @SerializedName(value = "label", alternate = ["description"])
        val label: String,
        val pattern: String,
        val group: Int = 0,
        @SerializedName(value = "status", alternate = ["resultType"])
        val status: String,
        val enabled: Boolean = true
    ) {
        fun toStatus(): String {
            return when (status.uppercase()) {
                "ERROR" -> "ERROR"
                "WARNING" -> "WARNING"
                else -> "INFO"
            }
        }

        fun toAnalyzerRule(): UartAiAnalyzer.UartRule {
            return UartAiAnalyzer.UartRule(
                pattern = pattern,
                resultType = toStatus(),
                description = label
            )
        }
    }

    private val maxRules = 50

    fun loadRules(): List<UartRule> {
        return try {
            val file = java.io.File(filePath)
            if (file.exists()) {
                val content = file.readText()
                val rulesArray = gson.fromJson(content, Array<UartRule>::class.java)
                rulesArray.toList()
            } else {
                getDefaultRules()
            }
        } catch (e: Exception) {
            getDefaultRules()
        }
    }

    fun saveRules(rules: List<UartRule>) {
        try {
            val limitedRules = rules.take(maxRules)
            val json = gson.toJson(limitedRules)
            java.io.File(filePath).apply {
                parentFile?.mkdirs()
                writeText(json)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addRule(pattern: String, resultType: String, description: String) {
        val rules = loadRules().toMutableList()
        if (rules.size < maxRules) {
            rules.add(UartRule(
                pattern = pattern,
                label = description,
                group = 0,
                status = resultType
            ))
            saveRules(rules)
        }
    }

    fun deleteRule(id: String) {
        val rules = loadRules().filter { it.id != id }
        saveRules(rules)
    }

    fun updateRule(id: String, pattern: String, resultType: String, description: String) {
        val rules = loadRules().map { rule ->
            if (rule.id == id) {
                rule.copy(pattern = pattern, label = description, status = resultType)
            } else {
                rule
            }
        }
        saveRules(rules)
    }

    private fun getDefaultRules(): List<UartRule> {
        return listOf(
            UartRule(pattern = "ERROR", label = "Error message", group = 0, status = "ERROR"),
            UartRule(pattern = "EXCEPTION", label = "Exception occurred", group = 0, status = "ERROR"),
            UartRule(pattern = "FATAL", label = "Fatal error", group = 0, status = "ERROR"),
            UartRule(pattern = "WARNING", label = "Warning message", group = 0, status = "WARNING"),
            UartRule(pattern = "TIMEOUT", label = "Timeout occurred", group = 0, status = "WARNING"),
            UartRule(pattern = "FAILED", label = "Operation failed", group = 0, status = "WARNING"),
            UartRule(pattern = "INFO", label = "Info message", group = 0, status = "INFO"),
            UartRule(pattern = "DEBUG", label = "Debug message", group = 0, status = "INFO")
        )
    }
}
