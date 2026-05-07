package com.rphone.v3.desktop.platform

import com.rphone.v3.core.platform.FileStorage
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Windows/Desktop implementation of FileStorage
 */
class DesktopFileStorage : FileStorage {
    
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val appDataDir = File(System.getProperty("user.home"), ".rphone-v3").apply {
        if (!exists()) {
            mkdirs()
        }
    }
    
    override suspend fun save(filename: String, data: String): Boolean {
        return try {
            val file = File(appDataDir, filename)
            file.writeText(data)
            logger.info("Saved file: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            logger.error("Error saving file: $filename", e)
            false
        }
    }
    
    override suspend fun load(filename: String): String? {
        return try {
            val file = File(appDataDir, filename)
            if (file.exists()) {
                file.readText()
            } else {
                logger.warn("File not found: $filename")
                null
            }
        } catch (e: Exception) {
            logger.error("Error loading file: $filename", e)
            null
        }
    }
    
    override suspend fun delete(filename: String): Boolean {
        return try {
            val file = File(appDataDir, filename)
            if (file.delete()) {
                logger.info("Deleted file: $filename")
                true
            } else {
                logger.warn("Failed to delete file: $filename")
                false
            }
        } catch (e: Exception) {
            logger.error("Error deleting file: $filename", e)
            false
        }
    }
    
    override suspend fun listFiles(directory: String): List<String> {
        return try {
            val dir = if (directory.isEmpty()) appDataDir else File(appDataDir, directory)
            dir.listFiles()?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            logger.error("Error listing files in: $directory", e)
            emptyList()
        }
    }
    
    override suspend fun getFileSize(filename: String): Long {
        return try {
            val file = File(appDataDir, filename)
            if (file.exists()) file.length() else -1L
        } catch (e: Exception) {
            logger.error("Error getting file size: $filename", e)
            -1L
        }
    }
    
    override suspend fun export(filename: String, data: String): Boolean {
        return try {
            val downloadsDir = File(System.getProperty("user.home"), "Downloads")
            val file = File(downloadsDir, filename)
            file.writeText(data)
            logger.info("Exported file to: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            logger.error("Error exporting file: $filename", e)
            false
        }
    }
}
