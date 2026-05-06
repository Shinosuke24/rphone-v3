package com.rphone.v3.waveid.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rphone.v3.waveid.model.ProfilArus

@Dao
interface ProfilArusDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInternal(profil: ProfilArus): Long

    suspend fun insert(profil: ProfilArus): Long {
        if (!profil.isValid()) {
            throw IllegalArgumentException("ProfilArus data tidak valid: brand=${profil.brand}, model=${profil.model}, waveform=${profil.waveformJson.length} chars")
        }
        return insertInternal(profil)
    }

    @Update
    suspend fun update(profil: ProfilArus)

    @Query("SELECT * FROM profil_arus ORDER BY tanggal DESC")
    fun getAll(): LiveData<List<ProfilArus>>

    @Query("SELECT * FROM profil_arus ORDER BY tanggal DESC")
    suspend fun getAllSync(): List<ProfilArus>

    @Query("SELECT * FROM profil_arus WHERE modeRekam = :mode ORDER BY tanggal DESC")
    suspend fun getAllByMode(mode: String): List<ProfilArus>

    @Query("SELECT * FROM profil_arus WHERE modeRekam = :mode AND id != :excludeId ORDER BY tanggal DESC")
    suspend fun getAllByModeExclude(mode: String, excludeId: Long): List<ProfilArus>

    @Query("SELECT * FROM profil_arus WHERE id = :id")
    suspend fun getById(id: Long): ProfilArus?

    @Query("DELETE FROM profil_arus WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM profil_arus")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM profil_arus")
    suspend fun getCount(): Int

    @Query("SELECT * FROM profil_arus WHERE brand LIKE '%' || :q || '%' OR model LIKE '%' || :q || '%' OR kondisi LIKE '%' || :q || '%' ORDER BY tanggal DESC")
    suspend fun search(q: String): List<ProfilArus>

    @Query("SELECT * FROM profil_arus WHERE modeRekam = :mode AND (brand LIKE '%' || :q || '%' OR model LIKE '%' || :q || '%' OR kondisi LIKE '%' || :q || '%') ORDER BY tanggal DESC")
    suspend fun searchByMode(q: String, mode: String): List<ProfilArus>

    @Query("SELECT COUNT(*) FROM profil_arus WHERE namaFile = :namaFile AND namaFile != ''")
    suspend fun existsByNamaFile(namaFile: String): Int

    @Query("SELECT COUNT(DISTINCT username) FROM profil_arus WHERE username != ''")
    suspend fun countTeknisiUnik(): Int

    @Query("SELECT COUNT(*) FROM profil_arus")
    suspend fun getTotalProfil(): Int

    // Task 30: ambil distinct username diurutkan berdasarkan aktivitas terbaru
    @Query("SELECT username FROM profil_arus WHERE username != '' GROUP BY username ORDER BY MAX(tanggal) DESC")
    suspend fun getDistinctUsernames(): List<String>

    // Task 31: DTW Filter — ambil distinct brand berdasarkan mode (USB / PSU)
    @Query("SELECT DISTINCT brand FROM profil_arus WHERE modeRekam = :mode AND brand != '' ORDER BY brand ASC")
    suspend fun getDistinctBrandsByMode(mode: String): List<String>

    // Task 31: DTW Filter — ambil distinct model berdasarkan brand + mode
    @Query("SELECT DISTINCT model FROM profil_arus WHERE modeRekam = :mode AND brand = :brand AND model != '' ORDER BY model ASC")
    suspend fun getDistinctModelsByBrandAndMode(mode: String, brand: String): List<String>

    // Task 31: DTW Filter — ambil profil berdasarkan mode + brand + model
    @Query("SELECT * FROM profil_arus WHERE modeRekam = :mode AND brand = :brand AND model = :model ORDER BY tanggal DESC")
    suspend fun getAllByModeAndChipset(mode: String, brand: String, model: String): List<ProfilArus>

    // Task 31: hitung jumlah profil untuk kombinasi mode + brand + model
    @Query("SELECT COUNT(*) FROM profil_arus WHERE modeRekam = :mode AND brand = :brand AND model = :model")
    suspend fun countByModeAndChipset(mode: String, brand: String, model: String): Int
}
