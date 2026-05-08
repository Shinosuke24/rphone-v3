package com.rphone.v3.desktop.viewmodel

import com.rphone.v3.core.platform.FileStorage
import javafx.application.Platform

/**
 * UART mode ViewModel — parity with APK UartViewModel
 * Handles UART console, rule parsing, and custom rule management
 */
class UartViewModel(private val storage: FileStorage) {

    var onConsoleUpdate: ((String) -> Unit)? = null
    var onParsedUpdate: ((String) -> Unit)? = null
    var onRulesUpdate: ((List<String>) -> Unit)? = null

    private val consoleBuffer = StringBuilder()
    private val parsedBuffer = StringBuilder()
    private val customRules = mutableListOf<String>()

    fun addConsoleMessage(direction: String, text: String) {
        val timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        val msg = "[$direction $timestamp] $text"
        
        if (consoleBuffer.length > 12000) consoleBuffer.clear()
        consoleBuffer.append(msg).append("\n")
        
        Platform.runLater {
            onConsoleUpdate?.invoke(consoleBuffer.toString())
        }
    }

    fun parseConsole(input: String) {
        try {
            // Simple UART rule parsing — can be extended
            val lines = input.split("\n").filter { it.isNotEmpty() }
            val parsed = buildString {
                lines.forEach { line ->
                    if (line.contains("ERROR") || line.contains("error")) {
                        appendLine("[ERROR] $line")
                    } else if (line.contains("OK") || line.contains("ok")) {
                        appendLine("[OK] $line")
                    } else {
                        appendLine("[MSG] $line")
                    }
                }
            }
            
            if (parsedBuffer.length > 8000) parsedBuffer.clear()
            parsedBuffer.append(parsed)
            
            Platform.runLater {
                onParsedUpdate?.invoke(parsedBuffer.toString())
            }
        } catch (_: Exception) {
            // swallow
        }
    }

    fun addRule(pattern: String, action: String) {
        val rule = "$pattern -> $action"
        customRules.add(rule)
        saveRules()
        Platform.runLater {
            onRulesUpdate?.invoke(customRules.toList())
        }
    }

    fun removeRule(index: Int) {
        if (index in customRules.indices) {
            customRules.removeAt(index)
            saveRules()
            Platform.runLater {
                onRulesUpdate?.invoke(customRules.toList())
            }
        }
    }

    fun getRules(): List<String> = customRules.toList()

    private fun saveRules() {
        try {
            val json = buildString {
                append("[")
                customRules.forEachIndexed { idx, rule ->
                    if (idx > 0) append(",")
                    append("\"").append(rule.replace("\"", "\\\"")).append("\"")
                }
                append("]")
            }
            storage.save("uart_rules.json", json)
        } catch (_: Exception) {
            // swallow
        }
    }

    fun loadRules() {
        try {
            val json = storage.load("uart_rules.json") ?: return
            customRules.clear()
            Regex("\"([^\"]+)\"").findAll(json).forEach { match ->
                customRules.add(match.groups[1]?.value ?: "")
            }
            Platform.runLater {
                onRulesUpdate?.invoke(customRules.toList())
            }
        } catch (_: Exception) {
            // swallow
        }
    }
}
