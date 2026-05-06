package com.rphone.v3.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.rphone.v3.model.ProbeMode
import java.util.Locale

/**
 * ProbeTtsManager — Task 26
 * Membacakan nilai Dioda & Volt via TTS bawaan Android.
 * Offline, gratis, tidak butuh API tambahan.
 */
class ProbeTtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingSpeak: Pair<String, ProbeMode>? = null  // antrian saat TTS belum siap

    companion object {
        private const val TAG = "ProbeTtsManager"
        private const val PREF_NAME  = "probe_tts_prefs"
        private const val PREF_KEY   = "tts_enabled"
    }

    // ── Inisialisasi ──────────────────────────────────────────────────────────

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("id", "ID"))
                isReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!isReady) {
                    // Fallback ke English jika Bahasa Indonesia tidak tersedia
                    tts?.setLanguage(Locale.ENGLISH)
                    isReady = true
                    Log.w(TAG, "Bahasa Indonesia tidak tersedia, fallback ke English")
                }
                Log.d(TAG, "TTS siap (isReady=$isReady)")

                // Keluarkan antrian jika ada speak() yang dipanggil sebelum siap
                pendingSpeak?.let { (display, mode) ->
                    pendingSpeak = null
                    speak(display, mode)
                }
            } else {
                Log.e(TAG, "TTS gagal inisialisasi, status=$status")
            }
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        pendingSpeak = null
    }

    // ── Toggle ON/OFF (disimpan ke SharedPreferences) ─────────────────────────

    fun isEnabled(): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_KEY, false)   // Default: OFF
    }

    fun setEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY, enabled).apply()
        if (!enabled) tts?.stop()
        Log.d(TAG, "TTS ${if (enabled) "ON" else "OFF"} | isReady=$isReady")
    }

    fun toggle(): Boolean {
        val newState = !isEnabled()
        setEnabled(newState)
        return newState
    }

    // ── Bacakan Nilai ─────────────────────────────────────────────────────────

    /**
     * Dipanggil tepat saat nilai masuk ke riwayat.
     * Hanya aktif untuk mode VOLT dan DIODE.
     */
    fun speak(display: String, mode: ProbeMode) {
        if (!isEnabled()) return
        if (mode == ProbeMode.OHM) return

        if (!isReady) {
            pendingSpeak = Pair(display, mode)
            Log.w(TAG, "TTS belum siap, pending: $display")
            return
        }

        val text = buildSpeakText(display, mode)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "probe_tts")
        Log.d(TAG, "TTS speak: \"$text\" (mode=$mode)")
    }

    /**
     * Versi dengan status — untuk Compare mode.
     * Contoh hasil: "satu koma tujuh sembilan volt, OK"
     *               "nol koma empat volt, beda"
     *               "G N D, OK"
     */
    fun speakWithStatus(display: String, mode: ProbeMode, isOk: Boolean) {
        if (!isEnabled()) return
        if (mode == ProbeMode.OHM) return

        if (!isReady) {
            pendingSpeak = Pair(display, mode)
            return
        }

        val nilaiText  = buildSpeakText(display, mode)
        val statusText = if (isOk) "OK" else "beda"
        val fullText   = "$nilaiText, $statusText"
        tts?.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "probe_tts")
        Log.d(TAG, "TTS speakWithStatus: \"$fullText\"")
    }

    // ── Format teks ucapan ────────────────────────────────────────────────────

    private fun buildSpeakText(display: String, mode: ProbeMode): String {
        return when {
            display == "OL" || display == "OPEN" -> "O L"
            display == "GND"                     -> "G N D"
            mode == ProbeMode.VOLT               -> formatVolt(display)
            mode == ProbeMode.DIODE              -> formatDiode(display)
            else                                 -> display
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
        return try {
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
        else -> c.toString()
    }
}

