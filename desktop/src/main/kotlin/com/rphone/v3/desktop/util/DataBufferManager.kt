package com.rphone.v3.desktop.util

import java.util.logging.Logger

/**
 * DataBufferManager — Centralized buffer management for serial data and waveforms
 * Handles memory cleanup strategy and prevents buffer overflow
 */
object DataBufferManager {
    private val logger = Logger.getLogger(DataBufferManager::class.java.name)
    
    // Buffer configuration
    const val MAX_SAMPLE_POINTS = 50_000
    const val MAX_UART_BUFFER_SIZE = 65536  // 64 KB
    const val CLEANUP_THRESHOLD_PERCENT = 85
    const val TARGET_CLEANUP_PERCENT = 50

    private var totalBytesProcessed: Long = 0
    private var bufferCleanupCount = 0

    data class BufferStats(
        val currentSize: Long,
        val maxSize: Long,
        val utilizationPercent: Int,
        val cleanupCount: Int,
        val totalProcessed: Long
    )

    /**
     * Check if waveform buffer needs cleanup
     */
    fun shouldCleanupWaveform(currentSamples: Int): Boolean {
        val utilizationPercent = (currentSamples * 100) / MAX_SAMPLE_POINTS
        return utilizationPercent >= CLEANUP_THRESHOLD_PERCENT
    }

    /**
     * Cleanup waveform buffer by removing oldest samples
     */
    fun cleanupWaveformBuffer(samples: MutableList<Double>): Int {
        if (samples.size <= MAX_SAMPLE_POINTS * TARGET_CLEANUP_PERCENT / 100) {
            return 0
        }

        val targetSize = (MAX_SAMPLE_POINTS * TARGET_CLEANUP_PERCENT / 100).toInt()
        val removeCount = samples.size - targetSize
        
        repeat(removeCount) {
            if (samples.isNotEmpty()) samples.removeAt(0)
        }
        
        bufferCleanupCount++
        logger.info("Cleaned up waveform buffer: removed $removeCount samples (total cleanups: $bufferCleanupCount)")
        
        return removeCount
    }

    /**
     * Check if UART buffer needs cleanup
     */
    fun shouldCleanupUartBuffer(currentSize: Long): Boolean {
        val utilizationPercent = (currentSize * 100) / MAX_UART_BUFFER_SIZE
        return utilizationPercent >= CLEANUP_THRESHOLD_PERCENT
    }

    /**
     * Cleanup UART buffer - remove oldest lines
     */
    fun cleanupUartBuffer(buffer: String): String {
        val lines = buffer.split("\n")
        val targetLineCount = (lines.size * TARGET_CLEANUP_PERCENT / 100).toInt()
        val removeCount = lines.size - targetLineCount
        
        val cleanedLines = lines.drop(removeCount)
        bufferCleanupCount++
        
        logger.info("Cleaned up UART buffer: removed $removeCount lines (total cleanups: $bufferCleanupCount)")
        
        return cleanedLines.joinToString("\n")
    }

    /**
     * Track bytes processed for statistics
     */
    fun recordBytesProcessed(count: Long) {
        totalBytesProcessed += count
    }

    /**
     * Get current buffer statistics
     */
    fun getBufferStats(currentWaveSize: Int, currentUartSize: Long): BufferStats {
        val wavePercent = (currentWaveSize * 100) / MAX_SAMPLE_POINTS
        val uartPercent = ((currentUartSize * 100) / MAX_UART_BUFFER_SIZE).toInt()
        val avgUtilization = (wavePercent + uartPercent) / 2

        return BufferStats(
            currentSize = currentWaveSize.toLong() + currentUartSize,
            maxSize = MAX_SAMPLE_POINTS.toLong() + MAX_UART_BUFFER_SIZE,
            utilizationPercent = avgUtilization,
            cleanupCount = bufferCleanupCount,
            totalProcessed = totalBytesProcessed
        )
    }

    /**
     * Reset statistics
     */
    fun resetStats() {
        totalBytesProcessed = 0
        bufferCleanupCount = 0
        logger.info("Buffer statistics reset")
    }

    /**
     * Validate buffer size
     */
    fun validateBufferSize(size: Long): Boolean {
        return size > 0 && size <= MAX_UART_BUFFER_SIZE
    }

    /**
     * Get human-readable buffer size
     */
    fun formatBufferSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
