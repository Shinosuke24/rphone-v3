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
 * 
 * Indonesian TTS format (parity dengan APK):
 * - VOLT: "{angka} volt" (e.g., "satu koma tiga lima volt")
 * - DIODE: "{angka}" (e.g., "nol koma enam dua")
 * - Special: OL → "O L", GND → "G N D"
 */
class DesktopTtsManager {
    
    private var isInitialized = true  // Windows TTS always available
    private val osName = System.getProperty("os.name").lowercase()
    
    /**
     * Play audio reading untuk nilai probe
     * Contoh: playProbeReading("VOLT", "1.35 V")
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
            "VOLT" -> formatVolt(value)
            "DIODE" -> formatDiode(value)
            "OHM" -> return "OHM mode not read"  // APK doesn't speak OHM values
            else -> value
        }
    }
    
    /**
     * Volt mode → "1 koma 35 volt"
     * Input contoh: "1.35 V", "0.00 V", "3.3 V"
     */
    private fun formatVolt(display: String): String {
        val numStr = display.replace("V", "").replace("v", "").trim()
        return "${formatAngka(numStr)} volt"
    }

    /**
     * Diode mode → "0 koma 62"
     * Input contoh: "0.625", "0.62"
     */
    private fun formatDiode(display: String): String {
        val numStr = display.replace("V", "").replace("v", "").trim()
        return formatAngka(numStr)
    }
    
    /**
     * Konversi angka desimal ke ucapan Bahasa Indonesia.
     * "1.35" → "satu koma tiga lima"
     * "0.62" → "nol koma enam dua"
     */
    private fun formatAngka(numStr: String): String {
        return when {
            numStr == "OL" || numStr == "OPEN" -> "O L"
            numStr == "GND" -> "G N D"
            else -> {
                try {
                    val parts = numStr.split(".")
                    val bulat = angkaBulat(parts[0].trim().toIntOrNull() ?: 0)
                    if (parts.size > 1 && parts[1].trim().isNotEmpty()) {
                        val desimalDigits = parts[1].trim()
                            .take(2)
                            .map { digitToWord(it) }
                            .joinToString(" ")
                        "$bulat koma $desimalDigits"
                    } else {
                        bulat
                    }
                } catch (e: Exception) {
                    numStr
                }
            }
        }
    }

    private fun angkaBulat(n: Int): String = when (n) {
        0  -> "nol"
        1  -> "satu"
        2  -> "dua"
        3  -> "tiga"
        4  -> "empat"
        5  -> "lima"
        6  -> "enam"
        7  -> "tujuh"
        8  -> "delapan"
        9  -> "sembilan"
        10 -> "sepuluh"
        11 -> "sebelas"
        in 12..19 -> "${angkaBulat(n - 10)} belas"
        in 20..99 -> {
            val puluhan = n / 10
            val satuan  = n % 10
            val p = if (puluhan == 2) "dua puluh" else "${angkaBulat(puluhan)} puluh"
            if (satuan == 0) p else "$p ${angkaBulat(satuan)}"
        }
        else -> n.toString()
    }

    private fun digitToWord(c: Char): String = when (c) {
        '0' -> "nol"
        '1' -> "satu"
        '2' -> "dua"
        '3' -> "tiga"
        '4' -> "empat"
        '5' -> "lima"
        '6' -> "enam"
        '7' -> "tujuh"
        '8' -> "delapan"
        '9' -> "sembilan"
        else -> ""
    }
    
    /**
     * Execute TTS using Windows PowerShell SAPI with Indonesian locale
     * Works on all Windows machines (built-in)
     */
    private fun speak(text: String) {
        try {
            if (osName.contains("windows")) {
                // Use Windows PowerShell SAPI for native TTS with Indonesian locale
                val psCommand = """
                    Add-Type -AssemblyName System.speech
                    ${'$'}speak = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    
                    # Coba gunakan Indonesian voice jika tersedia
                    try {
                        ${'$'}voice = ${'$'}speak.GetInstalledVoices() | Where-Object { ${'$'}_.VoiceInfo.Culture.Name -eq 'id-ID' } | Select-Object -First 1
                        if (${'$'}voice) {
                            ${'$'}speak.SelectVoice(${'$'}voice.VoiceInfo.Name)
                        }
                    } catch {}
                    
                    ${'$'}speak.Speak("$text")
                """.trimIndent()
                
                val process = Runtime.getRuntime().exec(arrayOf("powershell", "-Command", psCommand))
                process.waitFor()
            } else if (osName.contains("mac")) {
                // macOS: use built-in 'say' command with Indonesian
                Runtime.getRuntime().exec(arrayOf("say", "-v", "id_ID", text)).waitFor()
            } else if (osName.contains("linux")) {
                // Linux: try espeak with Indonesian language flag
                Runtime.getRuntime().exec(arrayOf("espeak", "-v", "id", text)).waitFor()
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

