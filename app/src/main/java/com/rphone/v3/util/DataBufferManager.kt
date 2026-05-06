package com.rphone.v3.util

import android.util.Log
import java.util.Collections

object DataBufferManager {

    const val MAX_PROBE_HISTORY = 1000
    const val MAX_WAVEFORM_POINTS = 50000
    const val MAX_CACHE_ENTRIES = 500
    private const val CLEANUP_THRESHOLD_MB = 100

    fun <T> limitBufferSize(buffer: MutableList<T>, maxSize: Int): MutableList<T> {
        if (buffer.size > maxSize) {
            Log.w("DataBufferManager", "Buffer size ${buffer.size} exceeds max $maxSize, removing oldest entries")
            val removeCount = buffer.size - maxSize
            repeat(removeCount) {
                if (buffer.isNotEmpty()) {
                    buffer.removeAt(0)
                }
            }
        }
        return buffer
    }

    fun limitProbeHistory(history: MutableList<Any>): MutableList<Any> {
        return limitBufferSize(history, MAX_PROBE_HISTORY)
    }

    fun limitWaveformData(dataPoints: MutableList<Double>): MutableList<Double> {
        return limitBufferSize(dataPoints, MAX_WAVEFORM_POINTS)
    }

    fun <K, V> limitCacheSize(cache: MutableMap<K, V>, maxSize: Int): MutableMap<K, V> {
        if (cache.size > maxSize) {
            Log.w("DataBufferManager", "Cache size ${cache.size} exceeds max $maxSize, removing old entries")
            val removeCount = cache.size - maxSize
            val keysToRemove = cache.keys.take(removeCount)
            keysToRemove.forEach { cache.remove(it) }
        }
        return cache
    }

    fun limitProbeCache(cache: MutableMap<String, Any>): MutableMap<String, Any> {
        return limitCacheSize(cache, MAX_CACHE_ENTRIES)
    }

    fun clearOldData(buffer: MutableList<Any>, keepCount: Int) {
        if (buffer.size > keepCount) {
            Log.d("DataBufferManager", "Clearing old data, keeping last $keepCount items")
            val removeCount = buffer.size - keepCount
            repeat(removeCount) {
                if (buffer.isNotEmpty()) {
                    buffer.removeAt(0)
                }
            }
        }
    }

    fun getMemoryUsageMB(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    fun shouldCleanup(): Boolean {
        val usageMB = getMemoryUsageMB()
        if (usageMB > CLEANUP_THRESHOLD_MB) {
            Log.w("DataBufferManager", "Memory usage ${usageMB}MB exceeds threshold ${CLEANUP_THRESHOLD_MB}MB")
            return true
        }
        return false
    }

    fun triggerCleanup() {
        Log.d("DataBufferManager", "Triggering garbage collection")
        System.gc()
    }
}
