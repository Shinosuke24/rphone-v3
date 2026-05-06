package com.rphone.v3.platform

import android.content.Context
import com.rphone.v3.core.platform.FileStorage
import android.util.Log
import java.io.File

/**
 * Android implementation of FileStorage
 */
class AndroidFileStorage(private val context: Context) : FileStorage {
    
    private val appDataDir = context.filesDir
    private val TAG = "AndroidFileStorage"
    
    override suspend fun save(filename: String, data: String): Boolean {
        return try {
            val file = File(appDataDir, filename)
            file.writeText(data)
            Log.d(TAG, "Saved file: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file: $filename", e)
            false
        }
    }
    
    override suspend fun load(filename: String): String? {
        return try {
            val file = File(appDataDir, filename)
            if (file.exists()) {
                file.readText()
            } else {
                Log.w(TAG, "File not found: $filename")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading file: $filename", e)
            null
        }
    }
    
    override suspend fun delete(filename: String): Boolean {
        return try {
            val file = File(appDataDir, filename)
            if (file.delete()) {
                Log.d(TAG, "Deleted file: $filename")
                true
            } else {
                Log.w(TAG, "Failed to delete file: $filename")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: $filename", e)
            false
        }
    }
    
    override suspend fun listFiles(directory: String): List<String> {
        return try {
            val dir = if (directory.isEmpty()) appDataDir else File(appDataDir, directory)
            dir.listFiles()?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files in: $directory", e)
            emptyList()
        }
    }
    
    override suspend fun getFileSize(filename: String): Long {
        return try {
            val file = File(appDataDir, filename)
            if (file.exists()) file.length() else -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size: $filename", e)
            -1L
        }
    }
    
    override suspend fun export(filename: String, data: String): Boolean {
        return try {
            // Export to /Downloads or external storage if available
            val downloadsDir = File("/storage/emulated/0/Download")
            if (downloadsDir.exists()) {
                val file = File(downloadsDir, filename)
                file.writeText(data)
                Log.d(TAG, "Exported file to: ${file.absolutePath}")
                true
            } else {
                // Fallback to app cache directory
                val file = context.getExternalFilesDir(null)?.let { File(it, filename) }
                if (file != null) {
                    file.writeText(data)
                    Log.d(TAG, "Exported file to: ${file.absolutePath}")
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting file: $filename", e)
            false
        }
    }
}
