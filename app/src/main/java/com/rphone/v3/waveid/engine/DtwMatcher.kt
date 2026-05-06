package com.rphone.v3.waveid.engine

import com.rphone.v3.waveid.model.ProfilArus
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object DtwMatcher {

    private const val TARGET_SIZE = 300

    // Bobot hybrid scoring (total = 1.0)
    private const val BOBOT_DTW  = 0.50f
    private const val BOBOT_PEAK = 0.25f
    private const val BOBOT_AVG  = 0.25f

    // Threshold penalty magnitude: selisih peak > 15% → skor turun paksa
    private const val PENALTY_PEAK_THRESHOLD = 0.15f
    // Maksimal pengurangan skor dari penalty (poin)
    private const val PENALTY_MAX = 20f

    data class HasilMatch(
        val profil: ProfilArus,
        val skor: Float,
        val label: String
    )

    // ─── Normalize waveform ke TARGET_SIZE titik ──────────────

    fun normalize(wave: List<Float>, size: Int = TARGET_SIZE): List<Float> {
        if (wave.isEmpty()) return List(size) { 0f }

        val resampled = if (wave.size == size) {
            wave
        } else {
            val ratio = (wave.size - 1).toFloat() / (size - 1).toFloat()
            List(size) { i ->
                val pos  = i * ratio
                val idx  = pos.toInt().coerceIn(0, wave.size - 2)
                val frac = pos - idx
                wave[idx] * (1f - frac) + wave[idx + 1] * frac
            }
        }

        // Pakai persentil 95 sebagai maxVal agar 1 outlier/noise tidak
        // menarik seluruh kurva ke bawah (fix: normalisasi sensitif noise)
        val sorted  = resampled.sorted()
        val p95idx  = ((sorted.size - 1) * 0.95f).toInt().coerceIn(0, sorted.size - 1)
        val maxVal  = sorted[p95idx].coerceAtLeast(0.001f)
        return resampled.map { (it / maxVal).coerceIn(0f, 1f) }
    }

    // ─── Hitung DTW distance murni (bentuk kurva) ─────────────

    private fun hitungDtwSaja(waveA: List<Float>, waveB: List<Float>): Float {
        val a = normalize(waveA)
        val b = normalize(waveB)
        val n = a.size
        val m = b.size

        val dtw = Array(n) { FloatArray(m) { Float.MAX_VALUE } }
        dtw[0][0] = abs(a[0] - b[0])

        for (i in 1 until n) dtw[i][0] = dtw[i - 1][0] + abs(a[i] - b[0])
        for (j in 1 until m) dtw[0][j] = dtw[0][j - 1] + abs(a[0] - b[j])

        for (i in 1 until n) {
            for (j in 1 until m) {
                val cost = abs(a[i] - b[j])
                dtw[i][j] = cost + min(
                    dtw[i - 1][j],
                    min(dtw[i][j - 1], dtw[i - 1][j - 1])
                )
            }
        }

        val distance = dtw[n - 1][m - 1] / (n + m).toFloat()
        return max(0f, 100f - distance * 100f)
    }

    // ─── Hitung skor kemiripan peak amplitudo (0..100) ────────
    // Skor 100 jika selisih <= 0.05A, turun linear hingga 0 saat >= 1.0A

    private fun hitungSkorPeak(waveUser: List<Float>, peakRef: Float): Float {
        val peakUser = waveUser.maxOrNull() ?: 0f
        val selisih = abs(peakUser - peakRef)
        val TOLERANSI_PENUH = 0.05f
        val SELISIH_MAX     = 1.0f
        return when {
            selisih <= TOLERANSI_PENUH -> 100f
            selisih >= SELISIH_MAX     -> 0f
            else -> {
                val range  = SELISIH_MAX - TOLERANSI_PENUH
                val posisi = selisih - TOLERANSI_PENUH
                max(0f, 100f - (posisi / range) * 100f)
            }
        }
    }

    // ─── Hitung skor kemiripan rata-rata arus (AVG) ───────────
    // Logika sama dengan peak: toleransi ±0.05A, drop linear ke 0 di ±0.8A

    private fun hitungSkorAvg(waveUser: List<Float>, avgRef: Float): Float {
        val avgUser = if (waveUser.isNotEmpty())
            waveUser.average().toFloat() else 0f
        val selisih = abs(avgUser - avgRef)
        val TOLERANSI_PENUH = 0.05f
        val SELISIH_MAX     = 0.8f
        return when {
            selisih <= TOLERANSI_PENUH -> 100f
            selisih >= SELISIH_MAX     -> 0f
            else -> {
                val range  = SELISIH_MAX - TOLERANSI_PENUH
                val posisi = selisih - TOLERANSI_PENUH
                max(0f, 100f - (posisi / range) * 100f)
            }
        }
    }

    // ─── Hitung penalty magnitude ─────────────────────────────
    // Jika selisih peak antara user dan referensi > 15% dari peakRef
    // → kurangi skor final secara paksa (max -20 poin)
    // Ini mencegah false match pada profil yang bentuknya mirip
    // tapi magnitude sangat berbeda (contoh: normal vs normal-tanpa-lcd)

    private fun hitungPenaltyMagnitude(waveUser: List<Float>, peakRef: Float): Float {
        if (peakRef <= 0f) return 0f
        val peakUser = waveUser.maxOrNull() ?: 0f
        val selisihRasio = abs(peakUser - peakRef) / peakRef
        return if (selisihRasio <= PENALTY_PEAK_THRESHOLD) {
            0f  // tidak ada penalty
        } else {
            // Penalty linear: dari 0 saat rasio = 15% hingga PENALTY_MAX saat rasio = 50%
            val range   = 0.50f - PENALTY_PEAK_THRESHOLD
            val posisi  = (selisihRasio - PENALTY_PEAK_THRESHOLD).coerceAtMost(range)
            (posisi / range) * PENALTY_MAX
        }
    }

    // ─── Hitung skor 1 channel dengan referensi dari profil ──
    // Dipakai untuk Volt, D+, D- agar konsisten dengan Arus.
    // peakRef / avgRef = nilai asli dari profil database.

    fun hitungSimilaritySatuChannel(
        waveLive: List<Float>,
        waveRef:  List<Float>,
        peakRef:  Float,
        avgRef:   Float = -1f
    ): Float {
        return hitungSimilarityDenganProfil(waveLive, waveRef, peakRef, avgRef)
    }

    // ─── Hitung skor hybrid (DTW + Peak + AVG + penalty) ──────

    fun hitungSimilarity(waveA: List<Float>, waveB: List<Float>): Float {
        val skorDtw  = hitungDtwSaja(waveA, waveB)
        val peakB    = waveB.maxOrNull() ?: 0f
        val avgB     = if (waveB.isNotEmpty()) waveB.average().toFloat() else 0f
        val skorPeak = hitungSkorPeak(waveA, peakB)
        val skorAvg  = hitungSkorAvg(waveA, avgB)
        val penalty  = hitungPenaltyMagnitude(waveA, peakB)
        val skorRaw  = (skorDtw * BOBOT_DTW) +
                       (skorPeak * BOBOT_PEAK) +
                       (skorAvg  * BOBOT_AVG)
        return max(0f, skorRaw - penalty)
    }

    // ─── Overload: hitungSimilarity dengan data ProfilArus ────
    // Gunakan puncakArus dan rataArus dari ProfilArus (nilai asli
    // saat rekam) agar lebih akurat daripada dihitung ulang dari
    // waveformJson yang sudah di-resample.

    fun hitungSimilarityDenganProfil(
        waveUser: List<Float>,
        waveRef: List<Float>,
        peakRef: Float,
        avgRef: Float = -1f   // -1f = fallback hitung dari waveRef
    ): Float {
        val skorDtw      = hitungDtwSaja(waveUser, waveRef)
        val avgRefFinal  = if (avgRef >= 0f) avgRef
                           else if (waveRef.isNotEmpty())
                               waveRef.average().toFloat() else 0f
        val skorPeak     = hitungSkorPeak(waveUser, peakRef)
        val skorAvg      = hitungSkorAvg(waveUser, avgRefFinal)
        val penalty      = hitungPenaltyMagnitude(waveUser, peakRef)
        val skorRaw      = (skorDtw * BOBOT_DTW) +
                           (skorPeak * BOBOT_PEAK) +
                           (skorAvg  * BOBOT_AVG)
        return max(0f, skorRaw - penalty)
    }

    // ─── Parse waveform dari JSON string ─────────────────────

    fun parseWaveformJson(json: String): List<Float> {
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Cari semua profil yang mirip ─────────────────────────
    // Gunakan hitungSimilarityDenganProfil() dengan rataArus
    // dari ProfilArus agar AVG scoring lebih akurat.

    fun cariKemiripan(
        waveformUser: List<Float>,
        database: List<ProfilArus>,
        threshold: Float = 70f
    ): List<HasilMatch> {
        if (waveformUser.isEmpty() || database.isEmpty()) return emptyList()

        return database
            .mapNotNull { profil ->
                val ref = parseWaveformJson(profil.waveformJson)
                if (ref.isEmpty()) return@mapNotNull null
                val skor = hitungSimilarityDenganProfil(
                    waveUser = waveformUser,
                    waveRef  = ref,
                    peakRef  = profil.puncakArus,
                    avgRef   = profil.rataArus
                )
                if (skor < threshold) return@mapNotNull null
                HasilMatch(
                    profil = profil,
                    skor   = skor,
                    label  = when {
                        skor >= 90f -> "Sangat Mirip"
                        skor >= 80f -> "Kemungkinan Sama"
                        else        -> "Ada Kemiripan"
                    }
                )
            }
            .sortedByDescending { it.skor }
    }

    // ─── Generate teks diagnosis otomatis ────────────────────

    /**
     * Generate diagnosis text berdasarkan hasil DTW match + nilai aktual rekaman.
     *
     * @param hasil        Daftar HasilMatch hasil DTW
     * @param peakAktual   Puncak arus aktual yang diukur (A), 0f jika tidak tersedia
     * @param avgAktual    Rata-rata arus aktual yang diukur (A), 0f jika tidak tersedia
     * @param durasiMs     Durasi rekaman aktual (ms), 0L jika tidak tersedia
     */
    fun generateDiagnosis(
        hasil: List<HasilMatch>,
        peakAktual: Float = 0f,
        avgAktual:  Float = 0f,
        durasiMs:   Long  = 0L
    ): String {
        if (hasil.isEmpty()) {
            return "Tidak ditemukan referensi yang cocok.\n" +
                   "Simpan rekaman ini untuk memperkaya database komunitas."
        }

        val match   = hasil.first()
        val kondisi = match.profil.kondisi.lowercase()
        val mode    = match.profil.modeRekam.uppercase()
        val skor    = match.skor

        // ── Catatan rekaman pendek ─────────────────────────────────────────────
        val SHORT_DURASI_MS = 20_000L  // rekaman < 20 detik dianggap pendek
        val catatanPendek = if (durasiMs in 1L until SHORT_DURASI_MS)
            "\n\n⚠ Rekaman pendek (${durasiMs / 1000L}s) — akurasi DTW lebih rendah. " +
            "Ulangi analisa dengan durasi lebih panjang untuk hasil lebih akurat."
        else ""

        // ── Mode USB: identifikasi charger ──
        if (mode == "USB") {
            val baseText = when {
                "qc 3.0" in kondisi || "qc3" in kondisi ->
                    "Teridentifikasi: Quick Charge 3.0\n" +
                    "Charger mendukung pengisian cepat QC3.0.\n" +
                    "Tegangan variabel: 3.6V–6.5V (200mV step)\n" +
                    "Maks daya: ~18W"
                "qc 2.0" in kondisi || "qc2" in kondisi ->
                    "Teridentifikasi: Quick Charge 2.0\n" +
                    "Charger mendukung pengisian cepat QC2.0.\n" +
                    "Tegangan: 5V / 9V / 12V\n" +
                    "Maks daya: ~18W"
                "qc 4" in kondisi || "qc4" in kondisi ->
                    "Teridentifikasi: Quick Charge 4.0+\n" +
                    "Charger mendukung QC4+ dan USB-PD.\n" +
                    "Maks daya: ~27W"
                "pd" in kondisi || "power delivery" in kondisi ->
                    "Teridentifikasi: USB Power Delivery\n" +
                    "Charger mendukung USB-PD.\n" +
                    "Tegangan: 5V/9V/15V/20V sesuai negosiasi\n" +
                    "Maks daya: tergantung kontrak PD"
                "dcp" in kondisi ->
                    "Teridentifikasi: Dedicated Charging Port (DCP)\n" +
                    "Charger murni, tidak mendukung data.\n" +
                    "DP/DM di-short internal.\n" +
                    "Maks arus: ~1.5A (7.5W @ 5V)"
                "cdp" in kondisi ->
                    "Teridentifikasi: Charging Downstream Port (CDP)\n" +
                    "Port USB dengan kemampuan charge + data.\n" +
                    "Maks arus: 1.5A"
                "sdp" in kondisi ->
                    "Teridentifikasi: Standard Downstream Port (SDP)\n" +
                    "Port USB standar, biasanya dari PC/laptop.\n" +
                    "Maks arus: 500mA (tanpa negosiasi) atau 900mA (USB 3.0)"
                "apple" in kondisi ->
                    "Teridentifikasi: Apple Charger\n" +
                    "Menggunakan tegangan DP/DM proprietary Apple.\n" +
                    "Maks arus sesuai model: 1A / 2.1A / 2.4A"
                else ->
                    "Pola multi-channel USB cocok dengan database.\n" +
                    "Kondisi referensi: \"${match.profil.kondisi}\"\n" +
                    "Konfirmasi dengan pengukuran langsung."
            }
            return baseText + catatanPendek
        }

        // ── Mode PSU / Boot ────────────────────────────────────────────────────
        // Klasifikasi arus AKTUAL — dipakai untuk validasi silang dengan kondisi profil
        val arusKategori = when {
            peakAktual <= 0f         -> null        // data tidak tersedia
            peakAktual < 0.05f       -> "SANGAT_RENDAH"
            peakAktual < 0.3f        -> "RENDAH"
            peakAktual < 0.8f        -> "SEDANG"
            peakAktual < 2.0f        -> "NORMAL"
            else                     -> "TINGGI"
        }

        // Deteksi mismatch: kondisi profil bilang "mati/dead" tapi arus aktual tinggi
        val kondisiMati    = "mati" in kondisi || "matot" in kondisi || "dead" in kondisi
        val kondisiNormal  = "normal" in kondisi
        val mismatch = when {
            kondisiMati   && arusKategori in listOf("SEDANG","NORMAL","TINGGI") -> true
            kondisiNormal && arusKategori in listOf("SANGAT_RENDAH","RENDAH")   -> true
            else                                                                  -> false
        }

        // Baris ringkasan nilai aktual (hanya ditampilkan jika data tersedia)
        val ringkasanAktual = if (peakAktual > 0f)
            "\n\nData terukur: Peak ${String.format("%.3f", peakAktual)}A  |  " +
            "Avg ${String.format("%.3f", avgAktual)}A  |  Skor DTW ${skor.toInt()}%"
        else ""

        // Jika ada mismatch → tampilkan peringatan & gunakan nilai aktual sebagai acuan
        if (mismatch) {
            val diagBerdasarArus = when (arusKategori) {
                "SANGAT_RENDAH" ->
                    "Arus sangat rendah (peak ${String.format("%.3f", peakAktual)}A).\n" +
                    "Kemungkinan:\n" +
                    "• Baterai habis total\n" +
                    "• IC power tidak aktif\n" +
                    "• Konektor baterai longgar"
                "RENDAH" ->
                    "Arus rendah (peak ${String.format("%.3f", peakAktual)}A).\n" +
                    "Kemungkinan:\n" +
                    "• Bootloop awal / stuck di pre-boot\n" +
                    "• IC PMIC output lemah\n" +
                    "• Baterai drop"
                "SEDANG", "NORMAL" ->
                    "Arus booting terdeteksi normal (peak ${String.format("%.3f", peakAktual)}A).\n" +
                    "Pola sesuai rentang booting wajar.\n" +
                    "Kemungkinan kondisi HP: baik atau masalah software ringan."
                "TINGGI" ->
                    "Arus tinggi saat booting (peak ${String.format("%.3f", peakAktual)}A).\n" +
                    "Kemungkinan:\n" +
                    "• Short circuit ringan\n" +
                    "• IC power konsumsi berlebih\n" +
                    "• Komponen bocor arus"
                else ->
                    "Ditemukan kemiripan dengan kondisi:\n" +
                    "\"${match.profil.kondisi}\"\n" +
                    "Konfirmasi dengan pemeriksaan fisik lebih lanjut."
            }
            return diagBerdasarArus +
                   "\n\n⚠ Profil referensi (${match.profil.kondisi}) tidak sepenuhnya cocok " +
                   "dengan nilai arus aktual. Diagnosis berdasarkan pengukuran nyata." +
                   ringkasanAktual + catatanPendek
        }

        // ── Kondisi cocok → tampilkan template sesuai kondisi + ringkasan aktual ──
        val diagTemplate = when {
            "bootloop" in kondisi || "boot loop" in kondisi ->
                "Pola arus sangat mirip kondisi BOOTLOOP.\n" +
                "Kemungkinan penyebab:\n" +
                "• eMMC rusak atau corrupt\n" +
                "• CPU power bermasalah\n" +
                "• Firmware corrupt\n" +
                "• IC PMIC bermasalah"

            "short" in kondisi || "konslet" in kondisi ->
                "Pola arus mirip kondisi SHORT CIRCUIT.\n" +
                "Segera periksa:\n" +
                "• IC charger\n" +
                "• Jalur baterai\n" +
                "• Kapasitor shorted"

            kondisiMati ->
                if (arusKategori == "SANGAT_RENDAH" || arusKategori == "RENDAH")
                    "Pola arus rendah — sesuai referensi kondisi mati/matot.\n" +
                    "Kemungkinan:\n" +
                    "• Baterai habis total\n" +
                    "• IC power mati\n" +
                    "• Konektor baterai longgar"
                else
                    "Pola arus sangat rendah.\n" +
                    "Kemungkinan:\n" +
                    "• Baterai habis total\n" +
                    "• IC power mati\n" +
                    "• Konektor baterai longgar"

            kondisiNormal ->
                "Pola arus sesuai referensi NORMAL.\n" +
                "HP kemungkinan dalam kondisi baik."

            "lcd" in kondisi || "layar" in kondisi ->
                "Pola arus mirip masalah LCD.\n" +
                "Periksa:\n" +
                "• Konektor LCD\n" +
                "• IC driver display\n" +
                "• Backlight circuit"

            "restart" in kondisi || "reboot" in kondisi ->
                "Pola mirip kondisi RESTART LOOP.\n" +
                "Kemungkinan:\n" +
                "• Baterai drop\n" +
                "• Thermal issue\n" +
                "• Software crash"

            else ->
                "Ditemukan kemiripan dengan kondisi:\n" +
                "\"${match.profil.kondisi}\"\n" +
                "Konfirmasi dengan pemeriksaan fisik lebih lanjut."
        }

        return diagTemplate + ringkasanAktual + catatanPendek
    }
}