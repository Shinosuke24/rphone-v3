package com.rphone.v3.desktop.managers

import com.rphone.v3.desktop.database.ProfilArus
import com.rphone.v3.desktop.database.ProfilArusDao
import com.rphone.v3.desktop.database.WaveIDDatabase
import com.rphone.v3.desktop.engine.BootRecorder
import com.rphone.v3.desktop.engine.DtwMatcher
import com.rphone.v3.desktop.ai.UartAiAnalyzer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Central manager for all WaveID recording, matching, and database operations.
 * Coordinates BootRecorder, DtwMatcher, and database DAO.
 */
class WaveIDManager(private val dbPath: String = "${System.getProperty("user.home")}/.rphone/wavedb.sqlite") {
    private val db: WaveIDDatabase = WaveIDDatabase.getInstance(dbPath)
    private val dao: ProfilArusDao = db.profilArusDao()
    private val matcher = DtwMatcher()
    private val uartAnalyzer = UartAiAnalyzer()

    // Get all profiles
    fun getAllProfiles(): List<ProfilArus> = dao.getAll()

    // Get profiles by mode
    fun getProfilesByMode(mode: String): List<ProfilArus> = dao.getAllByMode(mode)

    // Get profiles for comparison
    fun getProfilesForComparison(mode: String, brand: String, model: String): List<ProfilArus> {
        return dao.getAllByModeAndChipset(mode, brand, model)
    }

    // Save new profile
    fun saveProfile(profil: ProfilArus): Long {
        profil.tanggal = System.currentTimeMillis()
        return dao.insert(profil)
    }

    // Update profile
    fun updateProfile(profil: ProfilArus) = dao.update(profil)

    // Delete profile
    fun deleteProfile(id: Long) = dao.delete(id)

    // Search profiles
    fun searchProfiles(query: String): List<ProfilArus> = dao.search(query)

    // Get profile count
    fun getTotalCount(): Int = dao.getCount()

    // Get distinct brands
    fun getDistinctBrands(mode: String): List<String> = dao.getDistinctBrandsByMode(mode)

    // Get distinct models
    fun getDistinctModels(brand: String, mode: String): List<String> = dao.getDistinctModelsByBrandAndMode(brand, mode)

    // Compare waveforms
    fun compareWaveforms(
        queryWaveform: List<Double>,
        queryPeak: Double,
        queryAvg: Double,
        referenceProfiles: List<ProfilArus>
    ): List<DtwMatcher.MatchResult> {
        val references = referenceProfiles.map { profil ->
            Triple(
                profil.id,
                profil.waveformJson,
                "${profil.brand}|${profil.model}|${profil.kondisi}"
            )
        }
        return matcher.findSimilarProfiles(queryWaveform, queryPeak, queryAvg, references)
    }

    // Generate diagnosis
    fun generateDiagnosis(matches: List<DtwMatcher.MatchResult>, threshold: Double = 75.0): String {
        return matcher.generateDiagnosis(matches, threshold)
    }

    // Get recorder for manual recording
    fun createRecorder(mode: String = "USB"): BootRecorder = BootRecorder(mode)

    // Migrate from old JSON format to database
    fun migrateFromJson(jsonProfiles: List<ProfilArus>) {
        for (profil in jsonProfiles) {
            if (profil.id == 0L) {
                saveProfile(profil)
            } else {
                updateProfile(profil)
            }
        }
    }

    // Backup database to ZIP
    fun backupToZip(zipPath: String): Boolean {
        val profiles = getAllProfiles()
        return com.rphone.v3.desktop.util.RphpHandler.exportProfiles(profiles, zipPath)
    }

    // Restore from ZIP
    fun restoreFromZip(zipPath: String): Boolean {
        val profiles = com.rphone.v3.desktop.util.RphpHandler.importProfiles(zipPath) ?: return false
        migrateFromJson(profiles)
        return true
    }
}