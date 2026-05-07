package com.rphone.v3.desktop.ai

import com.google.gson.Gson

/**
 * Desktop port of APK's UartAiAnalyzer.kt
 * Parses UART data and prepares for AI analysis.
 */
class UartAiAnalyzer {
    private val gson = Gson()

    /**
     * Parse custom UART rules from shared data.
     */
    data class UartRule(
        val pattern: String,
        val resultType: String, // "ERROR", "WARNING", "INFO"
        val description: String
    )

    /**
     * Parsed UART message.
     */
    data class ParsedMessage(
        val timestamp: String,
        val level: String, // ERROR, WARNING, INFO
        val content: String,
        val matches: List<Pair<String, String>> // (pattern, description)
    )

    /**
     * Analysis input for AI providers.
     */
    data class AnalysisInput(
        val brand: String,
        val model: String,
        val systemPrompt: String,
        val userMessage: String,
        val rawSample: String = "",
        val errorItems: List<ParsedMessage> = emptyList()
    )

    /**
     * Filter UART data for AI input (ERROR/WARNING only).
     */
    fun filterDataForAi(messages: List<ParsedMessage>): List<ParsedMessage> {
        return messages.filter { it.level in listOf("ERROR", "WARNING") }
            .take(10) // Limit to 10 most recent issues
    }

    /**
     * Build user message for AI based on parsed data.
     */
    fun buildUserMessage(
        brand: String,
        model: String,
        bootStage: String = "UNKNOWN",
        errorCount: Int = 0,
        warningCount: Int = 0,
        thermalStatus: String = "NORMAL",
        modemStatus: String = "UNKNOWN",
        storageUsed: String = "UNKNOWN",
        memoryAvailable: String = "UNKNOWN",
        rawSample: String = ""
    ): String {
        return buildString {
            appendLine("**Device Info:**")
            appendLine("Brand: $brand")
            appendLine("Model: $model")
            appendLine("Boot Stage: $bootStage")
            appendLine()
            appendLine("**System Status:**")
            appendLine("Errors: $errorCount | Warnings: $warningCount")
            appendLine("Thermal: $thermalStatus")
            appendLine("Modem: $modemStatus")
            appendLine("Storage Used: $storageUsed")
            appendLine("Memory Available: $memoryAvailable")
            appendLine()
            if (rawSample.isNotEmpty()) {
                appendLine("**Raw Log Sample:**")
                appendLine(rawSample.take(500))
            }
        }
    }

    /**
     * System prompt (cached, static).
     */
    fun getSystemPrompt(): String {
        return """
            Anda adalah teknisi senior phone repair dengan 15 tahun pengalaman.
            Analisa log UART device Android dan identifikasi masalah hardware/software.
            
            Berikan diagnosis dalam 2-3 kalimat singkat, fokus pada:
            1. Kemungkinan penyebab utama
            2. Level severity (CRITICAL/HIGH/MEDIUM/LOW)
            3. Rekomendasi tindakan
            
            Gunakan Bahasa Indonesia.
        """.trimIndent()
    }

    /**
     * Parse raw log line with pattern matching.
     */
    fun parseLogLine(line: String, rules: List<UartRule>): ParsedMessage {
        val timestamp = extractTimestamp(line)
        var level = "INFO"
        val matches = mutableListOf<Pair<String, String>>()

        for (rule in rules) {
            if (line.contains(rule.pattern, ignoreCase = true)) {
                level = rule.resultType
                matches.add(rule.pattern to rule.description)
            }
        }

        // Default level detection from keywords
        when {
            line.contains("ERROR", ignoreCase = true) -> level = "ERROR"
            line.contains("WARN", ignoreCase = true) -> level = "WARNING"
            line.contains("FATAL", ignoreCase = true) -> level = "ERROR"
        }

        return ParsedMessage(timestamp, level, line, matches)
    }

    /**
     * Extract or generate timestamp.
     */
    private fun extractTimestamp(line: String): String {
        val timeRegex = """\d{2}:\d{2}:\d{2}""".toRegex()
        return timeRegex.find(line)?.value ?: "??:??:??"
    }

    /**
     * Mock analyze using rules (before sending to AI provider).
     */
    fun analyzeWithRules(
        messages: List<ParsedMessage>,
        brand: String,
        model: String
    ): String {
        val errorCount = messages.count { it.level == "ERROR" }
        val warningCount = messages.count { it.level == "WARNING" }

        return when {
            errorCount > 3 -> "Critical errors detected. Hardware may be faulty. ⚠️ HIGH"
            warningCount > 5 -> "Multiple warnings. Possible battery or thermal issue. ⚠️ MEDIUM"
            errorCount > 0 -> "Error detected. Investigate further. ⚠️ MEDIUM"
            else -> "No critical issues. Device appears normal. ✓ LOW"
        }
    }
}
