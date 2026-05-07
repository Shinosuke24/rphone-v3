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
    var tanggal: String = "", // ISO 8601 format: 2026-05-08T14:30:00
    var durasiMs: Long = 0,
    
    // USB mode: current/voltage/power
    var tegangan: Double = 0.0, // voltage
    var puncakArus: Double = 0.0, // peak current
    var rataArus: Double = 0.0, // average current
    var minArus: Double = 0.0, // minimum current
    var puncakDaya: Double = 0.0, // peak power
    
    // Waveforms (JSON arrays)
    var waveformJson: String = "[]", // current waveform
    var faseJson: String = "[]", // phase/metadata
    
    // PSU mode fields
    var puncakVolt: Double = 0.0,
    var avgVolt: Double = 0.0,
    var voltWaveformJson: String = "[]",
    
    // USB multi-channel (D+/D-)
    var dpAvg: Double = 0.0,
    var dmAvg: Double = 0.0,
    var puncakDp: Double = 0.0,
    var avgDp: Double = 0.0,
    var puncakDm: Double = 0.0,
    var avgDm: Double = 0.0,
    var dpWaveformJson: String = "[]",
    var dmWaveformJson: String = "[]",
    
    // Metadata
    var sumber: String = "MANUAL", // source: MANUAL, AUTO, IMPORTED
    var namaFile: String = "", // filename for sync/dedup
    var modeRekam: String = "USB", // USB or PSU
    var namaKonektor: String = "" // connector name for save dialog
) {
    fun isValid(): Boolean {
        return brand.isNotBlank() && model.isNotBlank() && kondisi.isNotBlank() &&
            !waveformJson.isEmpty() && waveformJson != "[]"
    }

    fun getWaveformArray(): List<Double> {
        return try {
            Gson().fromJson(waveformJson, Array<Double>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setWaveformArray(data: List<Double>) {
        waveformJson = Gson().toJson(data)
    }

    fun getDpWaveformArray(): List<Double> {
        return try {
            Gson().fromJson(dpWaveformJson, Array<Double>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setDpWaveformArray(data: List<Double>) {
        dpWaveformJson = Gson().toJson(data)
    }

    fun getDmWaveformArray(): List<Double> {
        return try {
            Gson().fromJson(dmWaveformJson, Array<Double>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setDmWaveformArray(data: List<Double>) {
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
                tanggal = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                tegangan = voltage,
                puncakArus = peakCurrent,
                rataArus = avgCurrent,
                minArus = minCurrent,
                puncakDaya = peakPower,
                modeRekam = modeRekam,
                namaKonektor = namaKonektor,
                sumber = "MANUAL"
            )
            profil.setWaveformArray(waveform)
            return profil
        }
    }
}
