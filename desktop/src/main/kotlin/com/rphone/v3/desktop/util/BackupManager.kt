package com.rphone.v3.desktop.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.logging.Logger

/**
 * BackupManager — Create and restore backups of preferences and database.
 * 
 * Backup format: ZIP file containing:
 *  - preferences.json: exported settings
 *  - database.db: WaveID database (if needed)
 */
object BackupManager {
    private val logger = Logger.getLogger(BackupManager::class.java.name)

    private const val BACKUP_FILENAME_PATTERN = "rphone_backup_%s.zip"
    private const val PREFS_JSON = "preferences.json"
    private const val DATABASE_FILE = "waveID.db"

    /**
     * Create a backup file in the home directory
     */
    suspend fun createBackup(
        dataDir: File,
        onStatus: (String) -> Unit = {}
    ): File? {
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
                val backupFileName = String.format(BACKUP_FILENAME_PATTERN, timestamp)

                val backupDir = File(dataDir, "backups").apply {
                    if (!exists()) mkdirs()
                }

                val backupFile = File(backupDir, backupFileName)

                onStatus("Creating backup: $backupFileName")

                ZipOutputStream(backupFile.outputStream()).use { zos ->
                    // Backup preferences.json
                    val prefsJson = backupPreferencesJson(dataDir)
                    zos.putNextEntry(ZipEntry(PREFS_JSON))
                    zos.write(prefsJson.toByteArray())
                    zos.closeEntry()
                    logger.info("Added preferences.json to backup")

                    // Backup database if exists
                    val dbFile = File(dataDir, DATABASE_FILE)
                    if (dbFile.exists()) {
                        zos.putNextEntry(ZipEntry(DATABASE_FILE))
                        dbFile.inputStream().use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                        logger.info("Added database to backup")
                    }
                }

                logger.info("Backup created: ${backupFile.absolutePath}")
                onStatus("Backup created successfully")
                backupFile

            } catch (e: Exception) {
                logger.severe("Backup error: ${e.message}")
                onStatus("Backup failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Restore a backup file
     */
    suspend fun restoreBackup(
        backupFile: File,
        dataDir: File,
        onStatus: (String) -> Unit = {}
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                onStatus("Restoring from backup: ${backupFile.name}")
                logger.info("Restoring backup: ${backupFile.absolutePath}")

                ZipFile(backupFile).use { zipFile ->
                    // Restore preferences
                    val prefsEntry = zipFile.getEntry(PREFS_JSON)
                    if (prefsEntry != null) {
                        val prefsJson = zipFile.getInputStream(prefsEntry)
                            .bufferedReader().use { it.readText() }
                        restorePreferencesJson(dataDir, prefsJson)
                        logger.info("Restored preferences.json")
                    }

                    // Restore database
                    val dbEntry = zipFile.getEntry(DATABASE_FILE)
                    if (dbEntry != null) {
                        val outDbFile = File(dataDir, DATABASE_FILE)
                        zipFile.getInputStream(dbEntry).use { input ->
                            outDbFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        logger.info("Restored database")
                    }
                }

                logger.info("Backup restored successfully")
                onStatus("Backup restored successfully")
                true

            } catch (e: Exception) {
                logger.severe("Restore error: ${e.message}")
                onStatus("Restore failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Backup preferences to JSON
     */
    private fun backupPreferencesJson(dataDir: File): String {
        return try {
            val json = JSONObject()

            // Backup ai_settings.json
            val aiSettingsFile = File(dataDir, "ai_settings.json")
            if (aiSettingsFile.exists()) {
                val aiContent = aiSettingsFile.readText()
                json.put("ai_settings", aiContent)
            }

            // Backup custom_rules.json
            val rulesFile = File(dataDir, "custom_rules.json")
            if (rulesFile.exists()) {
                val rulesContent = rulesFile.readText()
                json.put("custom_rules", rulesContent)
            }

            // Backup app_settings.json
            val appSettingsFile = File(dataDir, "app_settings.json")
            if (appSettingsFile.exists()) {
                val appContent = appSettingsFile.readText()
                json.put("app_settings", appContent)
            }

            json.toString(2)
        } catch (e: Exception) {
            logger.severe("Preferences backup error: ${e.message}")
            "{}"
        }
    }

    /**
     * Restore preferences from JSON
     */
    private fun restorePreferencesJson(dataDir: File, jsonString: String) {
        try {
            val json = JSONObject(jsonString)

            // Restore ai_settings.json
            if (json.has("ai_settings")) {
                val aiSettingsFile = File(dataDir, "ai_settings.json")
                aiSettingsFile.writeText(json.getString("ai_settings"))
            }

            // Restore custom_rules.json
            if (json.has("custom_rules")) {
                val rulesFile = File(dataDir, "custom_rules.json")
                rulesFile.writeText(json.getString("custom_rules"))
            }

            // Restore app_settings.json
            if (json.has("app_settings")) {
                val appSettingsFile = File(dataDir, "app_settings.json")
                appSettingsFile.writeText(json.getString("app_settings"))
            }

            logger.info("Restored preferences successfully")
        } catch (e: Exception) {
            logger.severe("Preferences restore error: ${e.message}")
        }
    }

    /**
     * List all available backups
     */
    fun listBackups(dataDir: File): List<File> {
        return try {
            val backupDir = File(dataDir, "backups")
            if (!backupDir.exists()) return emptyList()

            backupDir.listFiles { file ->
                file.isFile && file.name.startsWith("rphone_backup_") && file.name.endsWith(".zip")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (e: Exception) {
            logger.severe("List backups error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Delete a backup file
     */
    fun deleteBackup(backupFile: File): Boolean {
        return try {
            backupFile.delete().also {
                if (it) logger.info("Backup deleted: ${backupFile.name}")
            }
        } catch (e: Exception) {
            logger.severe("Delete backup error: ${e.message}")
            false
        }
    }

    /**
     * Get human-readable backup size
     */
    fun getBackupSizeFormatted(backupFile: File): String {
        val bytes = backupFile.length()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
