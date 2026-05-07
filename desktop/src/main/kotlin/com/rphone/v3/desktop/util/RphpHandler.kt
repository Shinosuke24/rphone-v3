package com.rphone.v3.desktop.util

import com.google.gson.Gson
import com.rphone.v3.desktop.database.ProfilArus
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Desktop port of APK's RphpHandler.kt
 * Handles import/export of waveform profiles with ZIP packaging and SHA-256 checksum.
 */
object RphpHandler {
    private val gson = Gson()

    data class ManifestData(
        val version: String = "3.0",
        val count: Int,
        val checksum: String,
        val timestamp: String,
        val exportedFrom: String = "RPhone-Desktop"
    )

    /**
     * Export profiles to ZIP file with manifest and checksums.
     */
    fun exportProfiles(profiles: List<ProfilArus>, zipOutputPath: String): Boolean {
        return try {
            File(zipOutputPath).parentFile?.mkdirs()

            ZipOutputStream(FileOutputStream(zipOutputPath)).use { zos ->
                // Export profiles as JSON
                profiles.forEachIndexed { idx, profil ->
                    val entryName = "profiles/${profil.id}_${profil.brand}_${profil.model}.json"
                    val jsonData = gson.toJson(profil)
                    zos.putNextEntry(ZipEntry(entryName))
                    zos.write(jsonData.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }

                // Create manifest
                val manifestJson = gson.toJson(ManifestData(
                    count = profiles.size,
                    checksum = calculateChecksum(gson.toJson(profiles)),
                    timestamp = java.time.LocalDateTime.now().toString()
                ))

                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Import profiles from ZIP file with checksum verification.
     */
    fun importProfiles(zipInputPath: String): List<ProfilArus>? {
        return try {
            val profiles = mutableListOf<ProfilArus>()

            ZipInputStream(FileInputStream(zipInputPath)).use { zis ->
                var entry = zis.nextEntry
                var manifest: ManifestData? = null

                while (entry != null) {
                    val content = zis.readBytes().toString(StandardCharsets.UTF_8)

                    when {
                        entry.name == "manifest.json" -> {
                            manifest = gson.fromJson(content, ManifestData::class.java)
                        }
                        entry.name.startsWith("profiles/") -> {
                            val profil = gson.fromJson(content, ProfilArus::class.java)
                            profiles.add(profil)
                        }
                    }

                    entry = zis.nextEntry
                }

                // Verify checksum if manifest exists
                if (manifest != null) {
                    val calculatedChecksum = calculateChecksum(gson.toJson(profiles))
                    if (calculatedChecksum != manifest.checksum) {
                        println("Warning: Checksum mismatch. File may be corrupted.")
                        // Continue anyway but log warning
                    }
                }

                profiles
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Calculate SHA-256 checksum for data verification.
     */
    private fun calculateChecksum(data: String): String {
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Export single profile as JSON file.
     */
    fun exportProfileJson(profil: ProfilArus, filePath: String): Boolean {
        return try {
            File(filePath).parentFile?.mkdirs()
            val json = gson.toJson(profil)
            File(filePath).writeText(json, StandardCharsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Import single profile from JSON file.
     */
    fun importProfileJson(filePath: String): ProfilArus? {
        return try {
            val json = File(filePath).readText(StandardCharsets.UTF_8)
            gson.fromJson(json, ProfilArus::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Create backup filename with timestamp.
     */
    fun generateBackupFilename(): String {
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "rphone_backup_$timestamp.zip"
    }
}
