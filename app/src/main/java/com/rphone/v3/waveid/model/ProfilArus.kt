package com.rphone.v3.waveid.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profil_arus")
data class ProfilArus(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val brand: String = "",
    val model: String = "",
    val kondisi: String = "",
    val username: String = "",
    val tanggal: Long = 0L,
    val durasiMs: Long = 0L,
    val tegangan: Float = 0f,
    val puncakArus: Float = 0f,
    val rataArus: Float = 0f,
    val minArus: Float = 0f,
    val puncakDaya: Float = 0f,
    val waveformJson: String = "[]",
    val faseJson: String = "[]",
    val sumber: String = "lokal",
    val namaFile: String = "",
    val modeRekam: String = "PSU",
    val dpAvg: Float = 0f,
    val dmAvg: Float = 0f,
    // ─── Multi-channel USB (Task 24) ──────────────────────────
    val voltWaveformJson: String = "[]",
    val dpWaveformJson: String = "[]",
    val dmWaveformJson: String = "[]",
    val puncakVolt: Float = 0f,
    val avgVolt: Float = 0f,
    val puncakDp: Float = 0f,
    val avgDp: Float = 0f,
    val puncakDm: Float = 0f,
    val avgDm: Float = 0f
) {
    fun isValid(): Boolean {
        if (brand.isBlank() || model.isBlank() || kondisi.isBlank()) return false
        // USB mode: waveformJson wajib non-kosong (data multi-channel)
        // PSU/WAVE mode: waveformJson bisa "[]" (flat/idle masih valid)
        if (modeRekam == "USB" && waveformJson == "[]") return false
        return true
    }
}
