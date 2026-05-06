package com.rphone.v3.ai

import org.json.JSONObject
import java.util.Locale

object AiPromptBuilder {

    // ── Confidence flag ───────────────────────────────────────────────────────

    fun hitungConfidenceFlag(skor: Float, adaAnomali: Boolean): String = when {
        skor >= 95f && !adaAnomali -> "confident_match"
        skor >= 95f &&  adaAnomali -> "match_with_anomaly"
        skor >= 90f &&  adaAnomali -> "low_confidence_anomaly"
        skor >= 90f && !adaAnomali -> "low_confidence"
        else                       -> "unknown"
    }

    fun adaAnomali(analisa: WaveAnalyzer.WaveAnalysisResult): Boolean =
        analisa.stuck.detected || analisa.spike.detected

    // ── JSON Lapis 2 compact ──────────────────────────────────────────────────

    fun buildLapis2Json(mode: String, analisa: WaveAnalyzer.WaveAnalysisResult): String {
        val json = JSONObject()
        json.put("mode", mode)

        val stuckObj = JSONObject().apply {
            put("detected", analisa.stuck.detected)
            if (analisa.stuck.detected) {
                put("value",       analisa.stuck.value)
                put("duration_ms", analisa.stuck.durationMs)
                put("percent",     analisa.stuck.percent)
            }
        }
        json.put("stuck", stuckObj)

        val spikeObj = JSONObject().apply {
            put("detected", analisa.spike.detected)
            if (analisa.spike.detected) {
                put("max_delta", analisa.spike.maxDelta)
                put("count",     analisa.spike.count)
            }
        }
        json.put("spike", spikeObj)

        val zona = analisa.zonaArus
        json.put("current_zones", JSONObject().apply {
            put("0-0.5A", "${zona.zona0to05}%")
            put("0.5-1A", "${zona.zona05to1}%")
            put("1-2A",   "${zona.zona1to2}%")
            put(">2A",    "${zona.zona2plus}%")
        })

        analisa.voltStats?.let { v ->
            json.put("voltage", JSONObject().apply {
                put("min", v.min); put("max", v.max); put("avg", v.avg)
            })
        }

        return json.toString()
    }

    // ── Panduan confidence (shared, masuk system prompt) ──────────────────────

    private val CONFIDENCE_GUIDE = """
PANDUAN CONFIDENCE:
- confident_match       : pola sangat cocok, fokus pada kondisi referensi
- match_with_anomaly    : cocok tapi ada anomali sinyal, periksa lebih lanjut
- low_confidence_anomaly: kemiripan lemah + anomali, utamakan data Lapis 2
- low_confidence        : kemiripan lemah tanpa anomali, hasil bisa kurang akurat
- unknown               : tidak ada referensi cocok, analisa dari data sinyal saja""".trimIndent()

    // ── Data class hasil build ────────────────────────────────────────────────

    /**
     * Hasil split system/user prompt.
     * - [system] → statis per tab, dikirim dengan cache_control ephemeral
     * - [user]   → dinamis per sesi (data arus, referensi DTW, sinyal Lapis 2, keluhan)
     *
     * Untuk backward-compat, gunakan [asPrompt] yang menggabungkan keduanya.
     */
    data class PromptPair(val system: String, val user: String) {
        /** Gabung system + user jadi satu String (dipakai caller lama / non-LiteLLM). */
        val asPrompt: String get() = "$system\n\n$user"
    }

    // ── System prompt USB (STATIS — tidak boleh ada data dinamis) ────────────

    private val SYSTEM_USB = """
Kamu adalah teknisi senior spesialis charging & USB smartphone berpengalaman lebih dari 10 tahun.

Keahlian utama:
- Membaca pola arus USB saat pertama charging: fase 0.0A, naik pelan, spike, stuck, tidak naik
- Mendiagnosa kerusakan IC charging, port USB, jalur VBUS, jalur D+/D-
- Mengenal perbedaan pola charger original vs KW, fast-charge vs normal

Konteks D+/D- yang WAJIB dipahami:
- D+=0V dan D-=0V adalah NORMAL untuk charger SDP (standar 5V) — BUKAN tanda kerusakan
- Hanya curigai jalur D+/D- jika: HP harusnya fast charge tapi stuck di 5V, atau D- tinggi tapi D+ nol (asimetris tanpa alasan)
- Fokus utama analisa selalu pada POLA ARUS, bukan D+/D- semata
- Jika D+/D- normal sesuai jenis charger, JANGAN sebut D+/D- dalam diagnosa

Format respons WAJIB (gunakan persis):
Dugaan   : [kerusakan utama berdasarkan pola arus]
Komponen : [komponen yang paling dicurigai]
Langkah  : 1. [langkah pertama — spesifik, sebutkan alat & titik ukur]
           2. [langkah kedua]
           3. [langkah ketiga — maks 3 langkah]

Aturan:
- Jawab dalam Bahasa Indonesia
- DILARANG memakai kata "normal", "anomali", atau "tidak normal"
- Langkah harus actionable: sebutkan alat ukur, nilai threshold, titik pengujian
- Jika data chipset tidak tersedia, JANGAN sebut nama IC spesifik
- Prioritaskan analisa pola arus — D+/D- hanya disebut jika relevan dengan keluhan

$CONFIDENCE_GUIDE
""".trimIndent()

    // ── System prompt PSU (STATIS) ────────────────────────────────────────────

    private val SYSTEM_PSU = """
Kamu adalah teknisi senior spesialis power supply & booting smartphone berpengalaman lebih dari 10 tahun.

Keahlian utama:
- Membaca pola arus PSU saat booting: short circuit, dead, stuck di fase tertentu, naik bertahap
- Mendiagnosa kerusakan PMIC, IC regulator, jalur Vbatt, jalur boot
- Mengenal pola arus normal booting per chipset (Qualcomm, MediaTek)

Format respons WAJIB (gunakan persis):
Dugaan   : [kerusakan utama berdasarkan pola arus booting]
Komponen : [komponen yang paling dicurigai]
Langkah  : 1. [langkah pertama — gunakan if-then jika ada cabang diagnosa]
           2. [langkah kedua — sebutkan alat, threshold, nilai referensi]
           3. [langkah ketiga]
           4. [langkah keempat — maks 4 langkah]

Aturan:
- Jawab dalam Bahasa Indonesia
- DILARANG memakai kata "normal", "anomali", atau "tidak normal"
- Langkah harus bercabang (if-then) jika ada dua kemungkinan diagnosa
- Jika data chipset tidak tersedia, JANGAN sebut nama IC spesifik

$CONFIDENCE_GUIDE
""".trimIndent()

    // ── Build USB ─────────────────────────────────────────────────────────────

    fun buildUsb(
        refBrand         : String,
        refModel         : String,
        refKondisi       : String,
        refSkor          : Float,
        peakArus         : Float,
        avgArus          : Float,
        minArus          : Float,
        durasiMs         : Long,
        chipsetDiketahui : Boolean = false,
        waveAnalysis     : WaveAnalyzer.WaveAnalysisResult? = null,
        voltAvg          : Float = 0f,
        dpAvg            : Float = 0f,
        dmAvg            : Float = 0f,
        isFastCharge     : Boolean = false,
        fastChargeType   : String = ""
    ): PromptPair {
        val anomali    = if (waveAnalysis != null) adaAnomali(waveAnalysis) else false
        val confidence = hitungConfidenceFlag(refSkor, anomali)

        val refLine = if (refBrand.isNotBlank() || refModel.isNotBlank())
            "Referensi DTW : $refBrand $refModel — Kondisi: $refKondisi — Skor: ${refSkor.toInt()}% — Confidence: $confidence"
        else
            "Referensi DTW : tidak ada yang cocok (skor di bawah threshold) — Confidence: unknown"

        val lapis2Block = waveAnalysis?.let {
            "\nDATA SINYAL LAPIS 2:\n${buildLapis2Json("USB", it)}"
        } ?: ""

        val icWarning = if (!chipsetDiketahui)
            "\n⚠️ Data chipset tidak tersedia — JANGAN sebut nama IC spesifik." else ""

        val fastChargeFlag = if (isFastCharge) " ⚡ FAST CHARGE ($fastChargeType)" else ""

        val dpDmStatus = when {
            dpAvg < 0.05f && dmAvg < 0.05f ->
                "SDP/Tidak ada protokol (D+=0V, D-=0V — normal untuk charger standar 5V)"
            dpAvg > 1.9f && dpAvg < 2.1f && dmAvg > 1.9f && dmAvg < 2.1f ->
                "DCP (2V/2V — charger dumb, bukan berarti rusak)"
            dpAvg > 0.5f && dmAvg < 0.2f ->
                "QC (DP-biased — fast charge aktif)"
            dpAvg > 0.3f && dmAvg > 0.3f ->
                "Protokol aktif (${String.format(Locale.US, "%.2f", dpAvg)}V / ${String.format(Locale.US, "%.2f", dmAvg)}V)"
            else ->
                "Terdeteksi (${String.format(Locale.US, "%.2f", dpAvg)}V / ${String.format(Locale.US, "%.2f", dmAvg)}V)"
        }

        val usbDataBlock = if (voltAvg > 0f || dpAvg > 0f || dmAvg > 0f) """

DATA TEGANGAN & USB:
- Volt (avg) : ${String.format(Locale.US, "%.2f", voltAvg)} V$fastChargeFlag
- D+ (avg)   : ${String.format(Locale.US, "%.3f", dpAvg)} V
- D- (avg)   : ${String.format(Locale.US, "%.3f", dmAvg)} V
- Status D+/D-: $dpDmStatus""" else ""

        val userMessage = """
MODE    : USB Charging
$refLine

DATA ARUS:
- Peak   : ${String.format(Locale.US, "%.3f", peakArus)} A
- Avg    : ${String.format(Locale.US, "%.3f", avgArus)} A
- Min    : ${String.format(Locale.US, "%.3f", minArus)} A
- Durasi : ${String.format(Locale.US, "%.1f", durasiMs / 1000f)} detik$usbDataBlock
$lapis2Block$icWarning

Berikan analisa dan langkah diagnosa sesuai format.
""".trimIndent()

        return PromptPair(system = SYSTEM_USB, user = userMessage)
    }

    // ── Build PSU ─────────────────────────────────────────────────────────────

    fun buildPsu(
        refBrand         : String,
        refModel         : String,
        refKondisi       : String,
        refSkor          : Float,
        peakArus         : Float,
        avgArus          : Float,
        minArus          : Float,
        durasiMs         : Long,
        chipsetDiketahui : Boolean = false,
        waveAnalysis     : WaveAnalyzer.WaveAnalysisResult? = null,
        keluhanUser      : String = "",
        preAnalisaJson   : String = ""
    ): PromptPair {
        val anomali    = if (waveAnalysis != null) adaAnomali(waveAnalysis) else false
        val confidence = hitungConfidenceFlag(refSkor, anomali)

        val refLine = if (refBrand.isNotBlank() || refModel.isNotBlank())
            "Referensi DTW : $refBrand $refModel — Kondisi: $refKondisi — Skor: ${refSkor.toInt()}% — Confidence: $confidence"
        else
            "Referensi DTW : tidak ada yang cocok (skor di bawah threshold) — Confidence: unknown"

        val keluhanBlock = if (keluhanUser.isNotBlank())
            "\nKELUHAN TEKNISI: \"$keluhanUser\"\n(Pertimbangkan keluhan ini dalam analisa dan SOP)" else ""

        val lapis2Block = waveAnalysis?.let {
            "\nDATA SINYAL LAPIS 2:\n${buildLapis2Json("PSU", it)}"
        } ?: ""

        val icWarning = if (!chipsetDiketahui)
            "\n⚠️ Data chipset tidak tersedia — JANGAN sebut nama IC spesifik." else ""

        // Pre-analisa block (sebelum booting)
        val preAnalisaBlock = if (preAnalisaJson.isNotBlank()) {
            try {
                val obj = org.json.JSONObject(preAnalisaJson)
                val status = obj.optString("status", "NORMAL")
                val avgMa  = obj.optDouble("avgMa", 0.0)
                val maxMa  = obj.optDouble("maxMa", 0.0)
                val minMa  = obj.optDouble("minMa", 0.0)
                val pola   = obj.optString("pola", "stabil")
                val statusLabel = when (status) {
                    "SHORT_HARD"  -> "⚠ SHORT HARD terdeteksi"
                    "SHORT_HALUS" -> "⚡ Short Halus terdeteksi"
                    else          -> "✓ Normal"
                }
                """

[PRE-ANALISA — 10 detik sebelum power on]
Status  : $statusLabel
Avg     : ${String.format(Locale.US, "%.1f", avgMa)} mA
Max     : ${String.format(Locale.US, "%.1f", maxMa)} mA
Min     : ${String.format(Locale.US, "%.1f", minMa)} mA
Pola    : $pola
(Gunakan data ini untuk mendeteksi short sebelum booting)
"""
            } catch (e: Exception) { "" }
        } else ""

        val userMessage = """
MODE    : Boot PSU
$refLine
$keluhanBlock$preAnalisaBlock
DATA ARUS BOOTING:
- Peak   : ${String.format(Locale.US, "%.3f", peakArus)} A
- Avg    : ${String.format(Locale.US, "%.3f", avgArus)} A
- Min    : ${String.format(Locale.US, "%.3f", minArus)} A
- Durasi : ${String.format(Locale.US, "%.1f", durasiMs / 1000f)} detik
$lapis2Block$icWarning

Berikan analisa dan langkah diagnosa sesuai format.
""".trimIndent()

        return PromptPair(system = SYSTEM_PSU, user = userMessage)
    }

    // ── build() lama — dipertahankan agar tidak break kode lain ──────────────

    @Deprecated("Gunakan buildUsb() atau buildPsu()", ReplaceWith("buildUsb() atau buildPsu()"))
    fun build(
        modeRekam        : String,
        refBrand         : String,
        refModel         : String,
        refKondisi       : String,
        refSkor          : Float,
        diagnosis        : String,
        peakArus         : Float,
        avgArus          : Float,
        minArus          : Float,
        durasiMs         : Long,
        chipsetDiketahui : Boolean = false
    ): String = if (modeRekam == "USB")
        buildUsb(refBrand, refModel, refKondisi, refSkor, peakArus, avgArus, minArus, durasiMs, chipsetDiketahui).asPrompt
    else
        buildPsu(refBrand, refModel, refKondisi, refSkor, peakArus, avgArus, minArus, durasiMs, chipsetDiketahui).asPrompt
}
