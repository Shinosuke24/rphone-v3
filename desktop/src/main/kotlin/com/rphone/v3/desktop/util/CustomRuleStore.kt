package com.rphone.v3.desktop.util

import com.google.gson.Gson

/**
 * Desktop port of APK's CustomRuleStore.kt
 * Stores and manages custom UART parsing rules using JSON serialization.
 */
class CustomRuleStore(private val filePath: String) {
    private val gson = Gson()

    data class UartRule(
        val id: String = java.util.UUID.randomUUID().toString(),
        val pattern: String,
        val resultType: String, // ERROR, WARNING, INFO
        val description: String,
        val enabled: Boolean = true
    )

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
                resultType = resultType,
                description = description
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
                rule.copy(pattern = pattern, resultType = resultType, description = description)
            } else {
                rule
            }
        }
        saveRules(rules)
    }

    private fun getDefaultRules(): List<UartRule> {
        return listOf(
            UartRule(pattern = "ERROR", resultType = "ERROR", description = "Error message"),
            UartRule(pattern = "EXCEPTION", resultType = "ERROR", description = "Exception occurred"),
            UartRule(pattern = "FATAL", resultType = "ERROR", description = "Fatal error"),
            UartRule(pattern = "WARNING", resultType = "WARNING", description = "Warning message"),
            UartRule(pattern = "TIMEOUT", resultType = "WARNING", description = "Timeout occurred"),
            UartRule(pattern = "FAILED", resultType = "WARNING", description = "Operation failed"),
            UartRule(pattern = "INFO", resultType = "INFO", description = "Info message"),
            UartRule(pattern = "DEBUG", resultType = "INFO", description = "Debug message")
        )
    }
}
