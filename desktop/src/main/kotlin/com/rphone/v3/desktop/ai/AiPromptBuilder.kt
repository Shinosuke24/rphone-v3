package com.rphone.v3.desktop.ai

import org.json.JSONObject
import java.util.Locale

object AiPromptBuilder {
    fun hitungConfidenceFlag(skor: Float, adaAnomali: Boolean): String = when {
        skor >= 95f && !adaAnomali -> "confident_match"
        skor >= 95f && adaAnomali -> "match_with_anomaly"
        skor >= 90f && adaAnomali -> "low_confidence_anomaly"
        skor >= 90f && !adaAnomali -> "low_confidence"
        else -> "unknown"
    }

    fun adaAnomali(analisa: WaveAnalyzer.WaveAnalysisResult): Boolean =
        analisa.stuck.detected || analisa.spike.detected

    fun buildLapis2Json(mode: String, analisa: WaveAnalyzer.WaveAnalysisResult): String {
        val json = JSONObject()
        json.put("mode", mode)

        val stuckObj = JSONObject().apply {
            put("detected", analisa.stuck.detected)
            if (analisa.stuck.detected) {
                put("value", analisa.stuck.value)
                put("duration_ms", analisa.stuck.durationMs)
                put("percent", analisa.stuck.percent)
            }
        }
        json.put("stuck", stuckObj)

        val spikeObj = JSONObject().apply {
            put("detected", analisa.spike.detected)
            if (analisa.spike.detected) {
                put("max_delta", analisa.spike.maxDelta)
                put("count", analisa.spike.count)
            }
        }
        json.put("spike", spikeObj)

        val zona = analisa.zonaArus
        json.put("current_zones", JSONObject().apply {
            put("0-0.5A", "${zona.zona0to05}%")
            put("0.5-1A", "${zona.zona05to1}%")
            put("1-2A", "${zona.zona1to2}%")
            put(">2A", "${zona.zona2plus}%")
        })

        analisa.voltStats?.let { v ->
            json.put("voltage", JSONObject().apply {
                put("min", v.min)
                put("max", v.max)
                put("avg", v.avg)
            })
        }

        return json.toString()
    }

    private val confidenceGuide = """
PANDUAN CONFIDENCE:
- confident_match       : pola sangat cocok, fokus pada kondisi referensi
- match_with_anomaly    : cocok tapi ada anomali sinyal, periksa lebih lanjut
- low_confidence_anomaly: kemiripan lemah + anomali, utamakan data Lapis 2
- low_confidence        : kemiripan lemah tanpa anomali, hasil bisa kurang akurat
- unknown               : tidak ada referensi cocok, analisa dari data sinyal saja
""".trimIndent()

    data class PromptPair(val system: String, val user: String) {
        val asPrompt: String get() = "$system\n\n$user"
    }

    private val systemUsb = """
Kamu adalah teknisi senior spesialis charging dan USB smartphone.
Format respons wajib:
Dugaan   : [kerusakan utama]
Komponen : [komponen paling dicurigai]
Langkah  : 1. [langkah spesifik]
           2. [langkah kedua]
           3. [langkah ketiga]
Aturan: Bahasa Indonesia, actionable, hindari kata "normal" dan "anomali".
$confidenceGuide
""".trimIndent()

    private val systemPsu = """
Kamu adalah teknisi senior spesialis power supply dan booting smartphone.
Format respons wajib:
Dugaan   : [kerusakan utama]
Komponen : [komponen paling dicurigai]
Langkah  : 1. [langkah spesifik]
           2. [langkah kedua]
           3. [langkah ketiga]
           4. [langkah keempat]
Aturan: Bahasa Indonesia, actionable, gunakan if-then bila perlu.
$confidenceGuide
""".trimIndent()

    fun buildUsb(
        refBrand: String,
        refModel: String,
        refKondisi: String,
        refSkor: Float,
        peakArus: Float,
        avgArus: Float,
        minArus: Float,
        durasiMs: Long,
        chipsetDiketahui: Boolean = false,
        waveAnalysis: WaveAnalyzer.WaveAnalysisResult? = null,
        voltAvg: Float = 0f,
        dpAvg: Float = 0f,
        dmAvg: Float = 0f,
        isFastCharge: Boolean = false,
        fastChargeType: String = ""
    ): PromptPair {
        val anomali = if (waveAnalysis != null) adaAnomali(waveAnalysis) else false
        val confidence = hitungConfidenceFlag(refSkor, anomali)

        val refLine = if (refBrand.isNotBlank() || refModel.isNotBlank()) {
            "Referensi DTW : $refBrand $refModel - Kondisi: $refKondisi - Skor: ${refSkor.toInt()}% - Confidence: $confidence"
        } else {
            "Referensi DTW : tidak ada yang cocok - Confidence: unknown"
        }

        val lapis2Block = waveAnalysis?.let {
            "\nDATA SINYAL LAPIS 2:\n${buildLapis2Json("USB", it)}"
        } ?: ""

        val icWarning = if (!chipsetDiketahui) {
            "\nData chipset tidak tersedia - jangan sebut nama IC spesifik."
        } else ""

        val fastChargeFlag = if (isFastCharge) " FAST CHARGE ($fastChargeType)" else ""

        val user = """
MODE    : USB Charging
$refLine

DATA ARUS:
- Peak   : ${String.format(Locale.US, "%.3f", peakArus)} A
- Avg    : ${String.format(Locale.US, "%.3f", avgArus)} A
- Min    : ${String.format(Locale.US, "%.3f", minArus)} A
- Durasi : ${String.format(Locale.US, "%.1f", durasiMs / 1000f)} detik
- Volt   : ${String.format(Locale.US, "%.2f", voltAvg)} V$fastChargeFlag
- D+     : ${String.format(Locale.US, "%.3f", dpAvg)} V
- D-     : ${String.format(Locale.US, "%.3f", dmAvg)} V
$lapis2Block$icWarning

Berikan analisa dan langkah diagnosa sesuai format.
""".trimIndent()

        return PromptPair(system = systemUsb, user = user)
    }

    fun buildPsu(
        refBrand: String,
        refModel: String,
        refKondisi: String,
        refSkor: Float,
        peakArus: Float,
        avgArus: Float,
        minArus: Float,
        durasiMs: Long,
        chipsetDiketahui: Boolean = false,
        waveAnalysis: WaveAnalyzer.WaveAnalysisResult? = null,
        keluhanUser: String = "",
        preAnalisaJson: String = ""
    ): PromptPair {
        val anomali = if (waveAnalysis != null) adaAnomali(waveAnalysis) else false
        val confidence = hitungConfidenceFlag(refSkor, anomali)

        val refLine = if (refBrand.isNotBlank() || refModel.isNotBlank()) {
            "Referensi DTW : $refBrand $refModel - Kondisi: $refKondisi - Skor: ${refSkor.toInt()}% - Confidence: $confidence"
        } else {
            "Referensi DTW : tidak ada yang cocok - Confidence: unknown"
        }

        val keluhanBlock = if (keluhanUser.isNotBlank()) {
            "\nKELUHAN TEKNISI: \"$keluhanUser\""
        } else ""

        val lapis2Block = waveAnalysis?.let {
            "\nDATA SINYAL LAPIS 2:\n${buildLapis2Json("PSU", it)}"
        } ?: ""

        val preBlock = if (preAnalisaJson.isNotBlank()) "\nPRE-ANALISA: $preAnalisaJson" else ""
        val icWarning = if (!chipsetDiketahui) {
            "\nData chipset tidak tersedia - jangan sebut nama IC spesifik."
        } else ""

        val user = """
MODE    : Boot PSU
$refLine
$keluhanBlock$preBlock

DATA ARUS BOOTING:
- Peak   : ${String.format(Locale.US, "%.3f", peakArus)} A
- Avg    : ${String.format(Locale.US, "%.3f", avgArus)} A
- Min    : ${String.format(Locale.US, "%.3f", minArus)} A
- Durasi : ${String.format(Locale.US, "%.1f", durasiMs / 1000f)} detik
$lapis2Block$icWarning

Berikan analisa dan langkah diagnosa sesuai format.
""".trimIndent()

        return PromptPair(system = systemPsu, user = user)
    }
}
