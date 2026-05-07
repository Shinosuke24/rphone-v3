package com.rphone.v3.core.platform

/**
 * Platform-agnostic interface for file operations.
 */
interface FileStorage {
    
    /**
     * Save data to file
     */
    suspend fun save(filename: String, data: String): Boolean
    
    /**
     * Load data from file
     */
    suspend fun load(filename: String): String?
    
    /**
     * Delete file
     */
    suspend fun delete(filename: String): Boolean
    
    /**
     * List files in directory
     */
    suspend fun listFiles(directory: String = ""): List<String>
    
    /**
     * Get file size
     */
    suspend fun getFileSize(filename: String): Long
    
    /**
     * Export data to user-accessible location (Downloads or Documents)
     */
    suspend fun export(filename: String, data: String): Boolean
}
