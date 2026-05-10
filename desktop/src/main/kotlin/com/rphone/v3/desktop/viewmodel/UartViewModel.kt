package com.rphone.v3.desktop.viewmodel

import com.google.gson.Gson
import com.rphone.v3.core.platform.FileStorage
import com.rphone.v3.desktop.ai.UartAiAnalyzer
import javafx.application.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class UartViewModel(private val storage: FileStorage) {

    enum class StreamState { IDLE, STREAMING, PARSING, ANALISA }

    data class TechnicianInfo(
        val chipset: String = "—",
        val vendor: String = "UNKNOWN",
        val powerRails: List<String> = emptyList(),
        val bootStage: String = "—",
        val errors: List<String> = emptyList(),
        val thermal: String = "—",
        val modem: String = "—",
        val storage: String = "—",
        val memory: String = "—",
        val device: String = "—",
        val customMatches: Map<String, String> = emptyMap()
    )

    var onConsoleUpdate: ((String) -> Unit)? = null
    var onParsedUpdate: ((String) -> Unit)? = null
    var onRulesUpdate: ((List<String>) -> Unit)? = null

    private val gson = Gson()
    private val consoleMutex = Mutex()
    private val parsedMutex = Mutex()
    private val consoleBuffer = ArrayDeque<String>()
    private val parsedBuffer = ArrayDeque<String>()
    private val customRules = mutableListOf<UartAiAnalyzer.UartRule>()
    private var parseJob: Job? = null
    private val _streamState = MutableStateFlow(StreamState.IDLE)
    val streamState: StateFlow<StreamState> = _streamState
    private var baudRateValue = 115200

    var detectedVendor: String = "UNKNOWN"
        private set

    init {
        loadRules()
        loadBaudRate()
    }

    fun startStreaming() {
        _streamState.value = StreamState.STREAMING
    }

    fun stopStreaming() {
        if (_streamState.value != StreamState.ANALISA) {
            _streamState.value = StreamState.IDLE
        }
    }

    fun hasLog(): Boolean = consoleBuffer.isNotEmpty()

    fun getFullLogText(): String = consoleBuffer.joinToString("\n")

    fun getCurrentTechInfo(): TechnicianInfo {
        val snapshot = consoleBuffer.toList()
        val vendor = detectVendor(snapshot)
        val parsed = buildParsedMessages(snapshot, vendor)
        return TechnicianInfo(
            chipset = parsed.firstOrNull { it.content.contains("MT", ignoreCase = true) || it.content.contains("SM", ignoreCase = true) }?.content ?: "—",
            vendor = vendor,
            bootStage = parsed.firstOrNull { it.content.contains("BOOT", ignoreCase = true) }?.content ?: "—",
            errors = parsed.filter { it.level == "ERROR" }.map { it.content }.take(20),
            thermal = parsed.firstOrNull { it.content.contains("TEMP", ignoreCase = true) }?.content ?: "—",
            modem = parsed.firstOrNull { it.content.contains("MODEM", ignoreCase = true) }?.content ?: "—",
            storage = parsed.firstOrNull { it.content.contains("UFS", ignoreCase = true) || it.content.contains("EMMC", ignoreCase = true) }?.content ?: "—",
            memory = parsed.firstOrNull { it.content.contains("RAM", ignoreCase = true) || it.content.contains("LPDDR", ignoreCase = true) }?.content ?: "—",
            device = parsed.firstOrNull()?.content ?: "—",
            customMatches = parsed.flatMap { item -> item.matches.map { it.first to it.second } }.toMap()
        )
    }

    fun setBaudRate(baud: Int) {
        baudRateValue = baud
        saveBaudRate()
    }

    fun loadBaudRate() {
        GlobalScope.launch {
            val text = storage.load("uart_baud.txt").orEmpty().trim()
            val baud = text.toIntOrNull() ?: 115200
            baudRateValue = baud
        }
    }

    fun triggerFullParse() {
        parseJob?.cancel()
        parseJob = GlobalScope.launch(Dispatchers.IO) {
            _streamState.value = StreamState.PARSING
            val snapshot = consoleMutex.withLock { consoleBuffer.toList() }
            val vendor = detectVendor(snapshot).also { if (it != "UNKNOWN") detectedVendor = it }
            val parsed = buildParsedMessages(snapshot, vendor)
            val text = parsed.joinToString("\n") { "[${it.level}] ${it.content}" }
            parsedMutex.withLock {
                parsedBuffer.clear()
                parsedBuffer.add(text)
            }
            Platform.runLater { onParsedUpdate?.invoke(text) }
            _streamState.value = StreamState.ANALISA
        }
    }

    fun parseConsole(input: String) {
        input.lineSequence().forEach { processLine(it) }
        triggerFullParse()
    }

    fun addConsoleMessage(direction: String, text: String) {
        val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val msg = "[$direction $timestamp] $text"
        GlobalScope.launch {
            consoleMutex.withLock {
                consoleBuffer.addLast(msg)
                while (consoleBuffer.size > 1500) consoleBuffer.removeFirst()
            }
            Platform.runLater { onConsoleUpdate?.invoke(getFullLogText()) }
            if (_streamState.value == StreamState.IDLE) _streamState.value = StreamState.STREAMING
        }
    }

    fun addRule(pattern: String, action: String) {
        val rule = UartAiAnalyzer.UartRule(pattern = pattern, resultType = action.uppercase(), description = action)
        customRules.add(rule)
        saveRules()
        Platform.runLater { onRulesUpdate?.invoke(getRules()) }
    }

    fun removeRule(index: Int) {
        if (index in customRules.indices) {
            customRules.removeAt(index)
            saveRules()
            Platform.runLater { onRulesUpdate?.invoke(getRules()) }
        }
    }

    fun getRules(): List<String> = customRules.map { "${it.pattern} -> ${it.resultType} (${it.description})" }

    fun applyCustomRules(line: String): UartAiAnalyzer.ParsedMessage {
        return UartAiAnalyzer().parseLogLine(line, customRules)
    }

    private fun processLine(line: String) {
        val clean = bersihkanLine(line)
        if (clean.isBlank()) return
        GlobalScope.launch {
            consoleMutex.withLock {
                consoleBuffer.addLast(clean)
                while (consoleBuffer.size > 1500) consoleBuffer.removeFirst()
            }
            Platform.runLater { onConsoleUpdate?.invoke(getFullLogText()) }
            if (_streamState.value == StreamState.IDLE) _streamState.value = StreamState.STREAMING
        }
        parseJob?.cancel()
        parseJob = GlobalScope.launch {
            delay(300L)
            triggerFullParse()
        }
    }

    private fun bersihkanLine(line: String): String {
        val total = line.length
        if (total == 0) return line
        val printable = line.count { it.code in 32..126 || it == '\t' }
        val ratio = printable.toFloat() / total
        if (ratio < 0.4f) return ""
        return if (ratio < 0.85f) line.filter { it.code in 32..126 || it == '\t' } else line
    }

    private fun detectVendor(snapshot: List<String>): String {
        for (line in snapshot) {
            val u = line.uppercase()
            when {
                u.contains("IMAGE_VARIANT_STRING") || u.contains("SBL1") || u.contains("ABL") -> return "QCOM"
                u.contains("PROCESSOR : MEDIATEK") || u.contains("PRELOADER") || u.contains("[MT") -> return "MTK"
            }
        }
        return "UNKNOWN"
    }

    private fun buildParsedMessages(snapshot: List<String>, vendor: String): List<UartAiAnalyzer.ParsedMessage> {
        val analyzer = UartAiAnalyzer()
        val rules = if (customRules.isNotEmpty()) {
            customRules
        } else {
            listOf(
                UartAiAnalyzer.UartRule("ERROR", "ERROR", "Error message"),
                UartAiAnalyzer.UartRule("EXCEPTION", "ERROR", "Exception occurred"),
                UartAiAnalyzer.UartRule("WARNING", "WARNING", "Warning message"),
                UartAiAnalyzer.UartRule("TIMEOUT", "WARNING", "Timeout occurred"),
                UartAiAnalyzer.UartRule("INFO", "INFO", "Info message"),
                UartAiAnalyzer.UartRule("DEBUG", "INFO", "Debug message")
            )
        }
        return snapshot.map { analyzer.parseLogLine(it, rules) }
    }

    private fun loadRules() {
        GlobalScope.launch {
            val json = storage.load("uart_rules.json").orEmpty()
            if (json.isBlank()) {
                if (customRules.isEmpty()) {
                    customRules.addAll(
                        listOf(
                            UartAiAnalyzer.UartRule("ERROR", "ERROR", "Error message"),
                            UartAiAnalyzer.UartRule("EXCEPTION", "ERROR", "Exception occurred"),
                            UartAiAnalyzer.UartRule("WARNING", "WARNING", "Warning message"),
                            UartAiAnalyzer.UartRule("TIMEOUT", "WARNING", "Timeout occurred"),
                            UartAiAnalyzer.UartRule("FAILED", "WARNING", "Operation failed"),
                            UartAiAnalyzer.UartRule("INFO", "INFO", "Info message"),
                            UartAiAnalyzer.UartRule("DEBUG", "INFO", "Debug message")
                        )
                    )
                }
            } else {
                try {
                    val loaded = gson.fromJson(json, Array<UartAiAnalyzer.UartRule>::class.java)?.toList().orEmpty()
                    customRules.clear()
                    customRules.addAll(loaded)
                } catch (_: Exception) {
                    // keep defaults
                }
            }
            Platform.runLater { onRulesUpdate?.invoke(getRules()) }
        }
    }

    private fun saveRules() {
        GlobalScope.launch { storage.save("uart_rules.json", gson.toJson(customRules)) }
    }

    private fun saveBaudRate() {
        GlobalScope.launch { storage.save("uart_baud.txt", baudRateValue.toString()) }
    }
}
