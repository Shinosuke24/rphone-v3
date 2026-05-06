package com.rphone.v3.util

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

object BackupManager {

    private const val TAG = "BackupManager"
    private const val BACKUP_FILENAME_PATTERN = "rphone_backup_%s.zip"
    private const val PREFS_JSON = "preferences.json"

    suspend fun createBackup(context: Context): File? {
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
                val backupFileName = String.format(BACKUP_FILENAME_PATTERN, timestamp)

                val backupDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: File(context.filesDir, "backups")

                if (!backupDir.exists()) {
                    backupDir.mkdirs()
                }

                val backupFile = File(backupDir, backupFileName)

                ZipOutputStream(backupFile.outputStream()).use { zos ->
                    val prefsJson = backupPreferences(context)
                    zos.putNextEntry(ZipEntry(PREFS_JSON))
                    zos.write(prefsJson.toByteArray())
                    zos.closeEntry()
                }

                Log.i(TAG, "Backup created: ${backupFile.absolutePath}")
                return@withContext backupFile
            } catch (e: Exception) {
                Log.e(TAG, "Backup error: ${e.message}")
                return@withContext null
            }
        }
    }

    suspend fun restoreBackup(context: Context, backupFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                ZipFile(backupFile).use { zipFile ->
                    val prefsEntry = zipFile.getEntry(PREFS_JSON)
                    if (prefsEntry != null) {
                        val prefsJson = zipFile.getInputStream(prefsEntry)
                            .bufferedReader().use { it.readText() }
                        restorePreferences(context, prefsJson)
                    }
                }

                Log.i(TAG, "Backup restored: ${backupFile.absolutePath}")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Restore error: ${e.message}")
                return@withContext false
            }
        }
    }

    private fun backupPreferences(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
            val json = JSONObject()

            prefs.all.forEach { (key, value) ->
                json.put(key, value)
            }

            json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Preferences backup error: ${e.message}")
            "{}"
        }
    }

    private fun restorePreferences(context: Context, jsonString: String) {
        try {
            val prefs = context.getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
            val json = JSONObject(jsonString)
            val editor = prefs.edit()

            json.keys().forEach { key ->
                val value = json.get(key)
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }

            editor.apply()
            Log.i(TAG, "Restored preferences")
        } catch (e: Exception) {
            Log.e(TAG, "Preferences restore error: ${e.message}")
        }
    }

    fun listBackups(context: Context): List<File> {
        return try {
            val backupDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, "backups")

            backupDir.listFiles { file ->
                file.isFile && file.name.startsWith("rphone_backup_") && file.name.endsWith(".zip")
            }?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "List backups error: ${e.message}")
            emptyList()
        }
    }

    fun deleteBackup(backupFile: File): Boolean {
        return try {
            backupFile.delete().also {
                Log.i(TAG, "Backup deleted: ${backupFile.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete backup error: ${e.message}")
            false
        }
    }

    fun getBackupSizeFormatted(backupFile: File): String {
        val bytes = backupFile.length()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}