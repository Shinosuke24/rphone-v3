package com.rphone.v3.desktop.tts

import kotlin.concurrent.thread
import java.util.Locale

/**
 * Desktop TTS (Text-to-Speech) Manager
 * Implementasi audio reading untuk Probe measurements di Windows EXE
 * Equivalent dengan ProbeTtsManager di APK
 * 
 * Uses Windows PowerShell SAPI (built-in Windows Text-to-Speech)
 * No external dependencies needed
 */
class DesktopTtsManager {
    
    private var isInitialized = true  // Windows TTS always available
    private val osName = System.getProperty("os.name").lowercase()
    
    /**
     * Play audio reading untuk nilai probe
     * Contoh: playProbeReading("Voltage", "5.2 Volt")
     */
    fun playProbeReading(mode: String, value: String) {
        if (!isInitialized) return
        
        try {
            thread(isDaemon = true) {
                try {
                    val text = buildProbeReadingText(mode, value)
                    speak(text)
                } catch (e: Exception) {
                    // Silent fail - TTS tidak critical
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
    
    /**
     * Play measurement confirmation audio (e.g., "GND Detected")
     */
    fun playMeasurementConfirm(message: String) {
        if (!isInitialized) return
        
        try {
            thread(isDaemon = true) {
                try {
                    speak(message)
                } catch (e: Exception) {
                    // Silent fail
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
    
    /**
     * Play quick beep/confirmation sound
     */
    fun playBeep(durationMs: Long = 100) {
        try {
            thread(isDaemon = true) {
                try {
                    speak("beep")
                } catch (e: Exception) {
                    // Silent fail
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
    
    private fun buildProbeReadingText(mode: String, value: String): String {
        return when (mode.uppercase()) {
            "VOLT" -> "Voltage reading: $value"
            "DIODE" -> "Diode voltage: $value"
            "OHM" -> "Resistance is $value ohms"
            else -> value
        }
    }
    
    /**
     * Execute TTS using Windows PowerShell SAPI
     * Works on all Windows machines (built-in)
     */
    private fun speak(text: String) {
        try {
            if (osName.contains("windows")) {
                // Use Windows PowerShell SAPI for native TTS (available on all Windows systems)
                val psCommand = """
                    Add-Type -AssemblyName System.speech
                    ${'$'}speak = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    ${'$'}speak.Speak("$text")
                """.trimIndent()
                
                val process = Runtime.getRuntime().exec(arrayOf("powershell", "-Command", psCommand))
                process.waitFor()
            } else if (osName.contains("mac")) {
                // macOS: use built-in 'say' command
                Runtime.getRuntime().exec(arrayOf("say", text)).waitFor()
            } else if (osName.contains("linux")) {
                // Linux: try espeak if available
                Runtime.getRuntime().exec(arrayOf("espeak", text)).waitFor()
            }
        } catch (e: Exception) {
            // Silent fail - TTS is optional feature
        }
    }
    
    /**
     * Cleanup resources
     */
    fun shutdown() {
        // No resources to cleanup for native speech API
    }
}

