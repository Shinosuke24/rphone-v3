package com.rphone.v3.desktop.database

import com.google.gson.Gson
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Desktop equivalent of APK's ProfilArus entity.
 * Stores waveform profile data for USB/PSU analysis with multi-channel support.
 */
data class ProfilArus(
    var id: Long = 0,
    var brand: String = "",
    var model: String = "",
    var kondisi: String = "", // kondisi = condition (OK, RUSAK, etc)
    var username: String = "Unknown",
    var tanggal: Long = 0L, // Unix timestamp millis (same as APK)
    var durasiMs: Long = 0,
    
    // USB mode: current/voltage/power
    var tegangan: Float = 0.0f, // voltage (Float for APK parity)
    var puncakArus: Float = 0.0f, // peak current
    var rataArus: Float = 0.0f, // average current
    var minArus: Float = 0.0f, // minimum current
    var puncakDaya: Float = 0.0f, // peak power
    
    // Waveforms (JSON arrays)
    var waveformJson: String = "[]", // current waveform
    var faseJson: String = "[]", // phase/metadata
    
    // PSU mode fields
    var puncakVolt: Float = 0.0f,
    var avgVolt: Float = 0.0f,
    var voltWaveformJson: String = "[]",
    
    // USB multi-channel (D+/D-)
    var dpAvg: Float = 0.0f,
    var dmAvg: Float = 0.0f,
    var puncakDp: Float = 0.0f,
    var avgDp: Float = 0.0f,
    var puncakDm: Float = 0.0f,
    var avgDm: Float = 0.0f,
    var dpWaveformJson: String = "[]",
    var dmWaveformJson: String = "[]",
    
    // Metadata
    var sumber: String = "MANUAL", // source: MANUAL, AUTO, IMPORTED
    var namaFile: String = "", // filename for sync/dedup
    var modeRekam: String = "USB", // USB or PSU
    var namaKonektor: String = "" // connector name for save dialog
) {
    fun isValid(): Boolean {
        if (brand.isBlank() || model.isBlank() || kondisi.isBlank()) return false
        // follow APK: if modeRekam == "USB" require waveformJson non-empty
        return if (modeRekam.equals("USB", ignoreCase = true)) {
            waveformJson.isNotEmpty() && waveformJson != "[]"
        } else {
            true
        }
    }

    fun getWaveformArray(): List<Float> {
        return try {
            Gson().fromJson(waveformJson, Array<Float>::class.java).toList()
        } catch (e: Exception) {
            // try legacy Double parsing and convert
            try {
                Gson().fromJson(waveformJson, Array<Double>::class.java).map { it.toFloat() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun setWaveformArray(data: List<Float>) {
        waveformJson = Gson().toJson(data)
    }

    fun getDpWaveformArray(): List<Float> {
        return try {
            Gson().fromJson(dpWaveformJson, Array<Float>::class.java).toList()
        } catch (e: Exception) {
            try {
                Gson().fromJson(dpWaveformJson, Array<Double>::class.java).map { it.toFloat() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun setDpWaveformArray(data: List<Float>) {
        dpWaveformJson = Gson().toJson(data)
    }

    fun getDmWaveformArray(): List<Float> {
        return try {
            Gson().fromJson(dmWaveformJson, Array<Float>::class.java).toList()
        } catch (e: Exception) {
            try {
                Gson().fromJson(dmWaveformJson, Array<Double>::class.java).map { it.toFloat() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun setDmWaveformArray(data: List<Float>) {
        dmWaveformJson = Gson().toJson(data)
    }

    fun getFaseArray(): List<Map<String, Any>>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(faseJson, List::class.java) as? List<Map<String, Any>>
        } catch (e: Exception) {
            null
        }
    }

    fun setFaseArray(data: List<Map<String, Any>>) {
        faseJson = Gson().toJson(data)
    }

    companion object {
        fun createFromFields(
            brand: String,
            model: String,
            kondisi: String,
            username: String,
            waveform: List<Double>,
            voltage: Double = 0.0,
            peakCurrent: Double = 0.0,
            avgCurrent: Double = 0.0,
            minCurrent: Double = 0.0,
            peakPower: Double = 0.0,
            modeRekam: String = "USB",
            namaKonektor: String = ""
        ): ProfilArus {
            val profil = ProfilArus(
                brand = brand,
                model = model,
                kondisi = kondisi,
                username = username,
                tanggal = System.currentTimeMillis(),
                tegangan = voltage.toFloat(),
                puncakArus = peakCurrent.toFloat(),
                rataArus = avgCurrent.toFloat(),
                minArus = minCurrent.toFloat(),
                puncakDaya = peakPower.toFloat(),
                modeRekam = modeRekam,
                namaKonektor = namaKonektor,
                sumber = "MANUAL"
            )
            // convert incoming Double waveform to Float list for storage
            profil.setWaveformArray(waveform.map { it.toFloat() })
            return profil
        }
    }
}
