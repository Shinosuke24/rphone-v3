package com.rphone.v3.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rphone.v3.ui.uart.ParsedItem
import com.rphone.v3.ui.uart.ParsedItemStatus
import com.rphone.v3.ui.uart.TechnicianInfo

object UartAiAnalyzer {

    private const val ENC_PREF_NAME = "rphone_ai_prefs"

    // ── System Prompt UART (STATIS — tidak boleh ada data dinamis) ──
    private val SYSTEM_UART = """
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

    /**
     * Filter List<ParsedItem> untuk dikirim ke AI.
     * - Status ERROR / WARNING → selalu kirim
     * - Status NORMAL / OK dengan value mengandung angka → kirim
     * - Semua item dengan value kosong → skip
     */
    private fun filterDataUntukAi(items: List<ParsedItem>): String {
        val baris = mutableListOf<String>()
        for (item in items) {
            if (item.value.isBlank() || item.value == "—") continue
            when (item.status) {
                ParsedItemStatus.ERROR   -> baris.add("❌ ${item.label.padEnd(18)}: ${item.value}")
                ParsedItemStatus.WARNING -> baris.add("⚠️ ${item.label.padEnd(18)}: ${item.value}")
                ParsedItemStatus.OK,
                ParsedItemStatus.NORMAL  -> {
                    // Kirim jika ada nilai informatif (bukan sekadar "—")
                    baris.add("   ${item.label.padEnd(18)}: ${item.value}")
                }
            }
        }
        return baris.joinToString("\n")
    }

    /**
     * Build user message UART (DINAMIS — berisi data teknisi).
     *
     * @param info          TechnicianInfo dari ViewModel
     * @param filteredData  Hasil filterDataUntukAi() — hanya item bermasalah
     * @param rawLogSample  20 baris terakhir log mentah
     */
    private fun buildUserMessage(
        info: TechnicianInfo,
        filteredData: String,
        rawLogSample: String
    ): String {
        val dataBlock = if (filteredData.isNotBlank()) {
            "=== DATA PARSED (item bermasalah saja) ===\n$filteredData"
        } else {
            "=== DATA PARSED ===\n(Tidak ada item bermasalah terdeteksi)"
        }

        val infoBlock = buildString {
            appendLine("🔧 Chipset    : ${info.chipset}")
            appendLine("📋 Vendor     : ${info.vendor}")
            appendLine("📋 Boot Stage : ${info.bootStage}")
            if (info.errors.isNotEmpty())
                appendLine("❌ Error      : ${info.errors.joinToString(" | ")}")
            if (info.thermal != "—")
                appendLine("🌡 Thermal    : ${info.thermal}")
            if (info.modem != "—")
                appendLine("📶 Modem      : ${info.modem}")
            if (info.storage != "—")
                appendLine("💾 Storage    : ${info.storage}")
            if (info.memory != "—")
                appendLine("🧠 Memory     : ${info.memory}")
        }.trimEnd()

        return """
MODE : UART Log Analysis

=== INFO DEVICE ===
$infoBlock

$dataBlock

=== SAMPEL LOG (20 baris terakhir) ===
$rawLogSample

Berikan analisa dan langkah diagnosa sesuai format.
    """.trimIndent()
    }

    suspend fun analisa(
        context: Context,
        info: TechnicianInfo,
        rawLogSample: String,
        parsedItems: List<ParsedItem> = emptyList()
    ): Result<String> {
        val filteredData = filterDataUntukAi(parsedItems)
        val userMessage  = buildUserMessage(info, filteredData, rawLogSample)

        return when (bacaProvider(context)) {
            "claude"  -> ClaudeAnalyzer.analisa(context, SYSTEM_UART + "\n\n" + userMessage)
            "groq"    -> GroqAnalyzer.analisa(context, SYSTEM_UART + "\n\n" + userMessage)
            "gemini"  -> GeminiAnalyzer.analisa(context, SYSTEM_UART + "\n\n" + userMessage)
            "litellm" -> LiteLLMAnalyzer.analisa(context, SYSTEM_UART, userMessage)
            else      -> LiteLLMAnalyzer.analisa(context, SYSTEM_UART, userMessage)
        }
    }

    private fun bacaProvider(context: Context): String {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                context, ENC_PREF_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ) as android.content.SharedPreferences
            prefs.getString("ai_provider", "litellm") ?: "litellm"
        } catch (e: Exception) { "litellm" }
    }
}
