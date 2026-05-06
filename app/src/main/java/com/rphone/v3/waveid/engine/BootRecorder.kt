package com.rphone.v3.waveid.engine

import com.rphone.v3.waveid.model.FaseArus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class StatusRekaman {
    SIAP, BERSIAP, MEREKAM, DIJEDA, SELESAI
}

data class TitikData(
    val waktuMs: Long = 0L,
    val arus: Float = 0f,
    val tegangan: Float = 0f
)

class BootRecorder {

    private val _status = MutableStateFlow(StatusRekaman.SIAP)
    val status: StateFlow<StatusRekaman> = _status

    private val titikData = mutableListOf<TitikData>()
    private val faseList  = mutableListOf<FaseArus>()

    private var waktuMulai = 0L
    private var faseMulai  = 0L
    private var faseAktif  = ""

    var peakArus: Float = 0f
        private set
    var rataArus: Float = 0f
        private set
    var minArus: Float  = Float.MAX_VALUE
        private set

    // ─── Multi-channel USB fields ─────────────────────────────
    private val titikVolt = mutableListOf<Float>()
    private val titikDp   = mutableListOf<Float>()
    private val titikDm   = mutableListOf<Float>()

    // Nilai terakhir D+/D- untuk deteksi perubahan (snapshot strategy)
    private var lastDp = Float.NaN
    private var lastDm = Float.NaN
    private val DP_THRESHOLD = 0.05f  // perubahan minimum agar masuk snapshot

    var peakVolt: Float = 0f
        private set
    var avgVolt: Float  = 0f
        private set
    var peakDp: Float   = 0f
        private set
    var avgDp: Float    = 0f
        private set
    var peakDm: Float   = 0f
        private set
    var avgDm: Float    = 0f
        private set

    // ─── State transitions ────────────────────────────────────

    fun bersiapRekam() {
        reset()
        _status.value = StatusRekaman.BERSIAP
    }

    fun mulaiRekam() {
        waktuMulai = System.currentTimeMillis()
        faseMulai  = waktuMulai
        faseAktif  = "Power On"
        _status.value = StatusRekaman.MEREKAM
    }

    fun jedaRekam() {
        _status.value = StatusRekaman.DIJEDA
    }

    fun lanjutkanRekam() {
        _status.value = StatusRekaman.MEREKAM
    }

    fun selesaiRekam() {
        if (faseAktif.isNotEmpty()) {
            simpanFaseAktif()
        }
        _status.value = StatusRekaman.SELESAI
    }

    // ─── Data input ───────────────────────────────────────────

    fun tambahData(arus: Float, tegangan: Float) {
        if (_status.value != StatusRekaman.MEREKAM) return
        val now = System.currentTimeMillis()
        val titik = TitikData(now - waktuMulai, arus, tegangan)
        titikData.add(titik)

        if (arus > peakArus) peakArus = arus
        if (arus < minArus)  minArus  = arus
        rataArus = titikData.map { it.arus }.average().toFloat()

        autoDetectFase(arus, titik.waktuMs)
    }

    // ─── USB multi-channel input ──────────────────────────────

    fun tambahDataUsb(arus: Float, tegangan: Float, dp: Float, dm: Float) {
        tambahData(arus, tegangan)

        // Volt: semua sampel masuk (sama seperti arus)
        titikVolt.add(tegangan)
        if (tegangan > peakVolt) peakVolt = tegangan
        if (titikVolt.isNotEmpty()) avgVolt = titikVolt.average().toFloat()

        // D+: snapshot hanya saat nilai berubah > threshold (hemat memori, kurangi noise flat)
        if (dp > peakDp) peakDp = dp
        val dpBerubah = lastDp.isNaN() || kotlin.math.abs(dp - lastDp) > DP_THRESHOLD
        if (dpBerubah) { titikDp.add(dp); lastDp = dp }
        if (titikDp.isNotEmpty()) avgDp = titikDp.average().toFloat()

        // D-: snapshot hanya saat nilai berubah > threshold
        if (dm > peakDm) peakDm = dm
        val dmBerubah = lastDm.isNaN() || kotlin.math.abs(dm - lastDm) > DP_THRESHOLD
        if (dmBerubah) { titikDm.add(dm); lastDm = dm }
        if (titikDm.isNotEmpty()) avgDm = titikDm.average().toFloat()
    }

    private fun autoDetectFase(arus: Float, waktuMs: Long) {
        val newFase = when {
            waktuMs < 3_000  && arus > 0.3f -> "Power On"
            waktuMs < 8_000  && arus < 0.3f -> "Bootloader"
            waktuMs < 25_000 && arus > 0.4f -> "Android Init"
            waktuMs < 50_000 && arus > 0.2f -> "Sistem Muat"
            arus < 0.15f                     -> "Boot Selesai"
            else -> faseAktif
        }
        if (newFase != faseAktif && faseAktif.isNotEmpty()) {
            simpanFaseAktif()
            faseAktif = newFase
            faseMulai = System.currentTimeMillis()
        }
    }

    fun tandaiFaseManual(nama: String) {
        if (faseAktif.isNotEmpty()) {
            simpanFaseAktif()
        }
        faseAktif = nama
        faseMulai = System.currentTimeMillis()
    }

    private fun simpanFaseAktif() {
        val now = System.currentTimeMillis()
        val mulaiOffset   = faseMulai - waktuMulai
        val selesaiOffset = now - waktuMulai
        val faseSamples   = titikData.filter {
            it.waktuMs in mulaiOffset..selesaiOffset
        }
        val peak = faseSamples.maxOfOrNull { it.arus } ?: 0f
        val avg  = if (faseSamples.isNotEmpty())
            faseSamples.map { it.arus }.average().toFloat() else 0f

        faseList.add(
            FaseArus(
                nama      = faseAktif,
                mulaiMs   = mulaiOffset,
                selesaiMs = selesaiOffset,
                puncak    = peak,
                rata      = avg
            )
        )
    }

    // ─── Getters ──────────────────────────────────────────────

    fun getWaveformJson(): String {
        val arr = JSONArray()
        titikData.forEach { arr.put(it.arus.toDouble()) }
        return arr.toString()
    }

    fun getVoltWaveformJson(): String {
        val arr = JSONArray()
        titikVolt.forEach { arr.put(it.toDouble()) }
        return arr.toString()
    }

    fun getDpWaveformJson(): String {
        val arr = JSONArray()
        titikDp.forEach { arr.put(it.toDouble()) }
        return arr.toString()
    }

    fun getDmWaveformJson(): String {
        val arr = JSONArray()
        titikDm.forEach { arr.put(it.toDouble()) }
        return arr.toString()
    }

    fun getFaseJson(): String {
        val arr = JSONArray()
        faseList.forEach { fase ->
            val obj = JSONObject()
            obj.put("nama",      fase.nama)
            obj.put("mulaiMs",   fase.mulaiMs)
            obj.put("selesaiMs", fase.selesaiMs)
            obj.put("puncak",    fase.puncak.toDouble())
            obj.put("rata",      fase.rata.toDouble())
            arr.put(obj)
        }
        return arr.toString()
    }

    fun getDurasiMs(): Long {
        if (titikData.size < 2) return 0L
        return titikData.last().waktuMs - titikData.first().waktuMs
    }

    fun getJumlahSampel(): Int = titikData.size

    fun getFaseList(): List<FaseArus> = faseList.toList()

    fun getMinArusSafe(): Float =
        if (minArus == Float.MAX_VALUE) 0f else minArus

    // ─── Reset ────────────────────────────────────────────────

    fun reset() {
        titikData.clear()
        faseList.clear()
        waktuMulai = 0L
        faseMulai  = 0L
        faseAktif  = ""
        peakArus   = 0f
        rataArus   = 0f
        minArus    = Float.MAX_VALUE
        // Multi-channel reset
        titikVolt.clear(); titikDp.clear(); titikDm.clear()
        peakVolt = 0f; avgVolt = 0f
        peakDp   = 0f; avgDp   = 0f
        peakDm   = 0f; avgDm   = 0f
        lastDp   = Float.NaN; lastDm = Float.NaN
        _status.value = StatusRekaman.SIAP
    }
}
