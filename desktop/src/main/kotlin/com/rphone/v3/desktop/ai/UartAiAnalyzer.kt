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
     * Filter parsed UART messages into a compact AI payload.
     * Mirrors the APK behavior: keep only meaningful issue lines.
     */
    fun buildFilteredAiText(messages: List<ParsedMessage>): String {
        val baris = mutableListOf<String>()
        for (item in messages) {
            if (item.content.isBlank()) continue
            when (item.level.uppercase()) {
                "ERROR" -> baris.add("❌ ${item.content}")
                "WARNING" -> baris.add("⚠️ ${item.content}")
                else -> baris.add(item.content)
            }
        }
        return baris.take(20).joinToString("\n")
    }

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
     * APK-compatible detailed user message builder.
     */
    fun buildUserMessage(
        brand: String,
        model: String,
        bootStage: String,
        thermalStatus: String,
        modemStatus: String,
        storageUsed: String,
        memoryAvailable: String,
        errorItems: List<ParsedMessage> = emptyList(),
        rawSample: String = ""
    ): String {
        val filteredData = buildFilteredAiText(errorItems)
        val dataBlock = if (filteredData.isNotBlank()) {
            "=== DATA PARSED (item bermasalah saja) ===\n$filteredData"
        } else {
            "=== DATA PARSED ===\n(Tidak ada item bermasalah terdeteksi)"
        }

        val infoBlock = buildString {
            appendLine("🔧 Chipset    : $brand")
            appendLine("📋 Vendor     : $model")
            appendLine("📋 Boot Stage : $bootStage")
            if (thermalStatus != "—") appendLine("🌡 Thermal    : $thermalStatus")
            if (modemStatus != "—") appendLine("📶 Modem      : $modemStatus")
            if (storageUsed != "—") appendLine("💾 Storage    : $storageUsed")
            if (memoryAvailable != "—") appendLine("🧠 Memory     : $memoryAvailable")
        }.trimEnd()

        return """
MODE : UART Log Analysis

=== INFO DEVICE ===
$infoBlock

$dataBlock

=== SAMPEL LOG (20 baris terakhir) ===
$rawSample

Berikan analisa dan langkah diagnosa secara singkat dan actionable.
        """.trimIndent()
    }

    /**
     * System prompt (cached, static).
     */
    fun getSystemPrompt(): String {
        return """
Kamu adalah teknisi senior spesialis log UART & firmware smartphone berpengalaman lebih dari 10 tahun.

Keahlian utama:
- Membaca hasil parsing UART: PMIC fault, boot stage stuck, baseband error, power rail anomali
- Mendiagnosa kerusakan dari pola log: dead boot, bootloop, modem fail, eMMC/UFS error
- Mengenal perbedaan pola log Qualcomm (SBL/XBL/ABL) dan MediaTek (PRELOADER/LK)

Format respons WAJIB (gunakan persis):
Dugaan   : [kerusakan utama berdasarkan data parsed]
Komponen : [komponen yang paling dicurigai]
Langkah  : 1. [langkah pertama — sebutkan alat, titik ukur, nilai threshold]
           2. [langkah kedua — bercabang (if-then) jika ada dua kemungkinan]
           3. [langkah ketiga — maks 3 langkah]

Aturan:
- Jawab dalam Bahasa Indonesia
- DILARANG memakai kata "normal", "anomali", atau "tidak normal"
- Langkah harus actionable: sebutkan nama IC, jalur, atau alat ukur yang relevan
- Input yang diterima adalah hasil parsing terfilter — HANYA item bermasalah yang dikirim
- Jika semua data bersih (tidak ada input), jawab: "Tidak ditemukan indikasi kerusakan dari log yang tersedia."
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

    suspend fun analisa(input: AnalysisInput): Result<String> {
        val cfg = AiConfigStore.load()
        val provider = cfg.provider.lowercase()

        val prompt = if (input.systemPrompt.isBlank()) {
            input.userMessage
        } else {
            "${input.systemPrompt}\n\n${input.userMessage}"
        }

        return when (provider) {
            "claude" -> ClaudeAnalyzer.analisa(prompt)
            "groq" -> GroqAnalyzer.analisa(prompt)
            "gemini" -> GeminiAnalyzer.analisa(prompt)
            "litellm", "litellm".lowercase() ->
                LiteLLMAnalyzer.analisa(input.systemPrompt, input.userMessage)
            else -> LiteLLMAnalyzer.analisa(input.systemPrompt, input.userMessage)
        }
    }
}
