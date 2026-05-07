package com.rphone.v3.desktop.scheduler

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Desktop Background Task Scheduler
 * Equivalent dengan Android WorkManager untuk periodic tasks
 * 
 * APK side: SupabasePollingWorker runs every 15 minutes
 * Desktop: ScheduledExecutorService runs background tasks
 */
class BackgroundTaskScheduler {
    
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val runningTasks = mutableMapOf<String, ScheduledFuture<*>>()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    /**
     * Schedule a periodic task
     * @param taskName Unique name for the task
     * @param initialDelayMs Delay sebelum task pertama kali run
     * @param intervalMs Interval antar runs (dalam milliseconds)
     * @param task Lambda function yang akan dijalankan
     */
    fun schedulePeriodicTask(
        taskName: String,
        initialDelayMs: Long = 0,
        intervalMs: Long,
        task: suspend () -> Unit
    ) {
        // Cancel existing task if any
        runningTasks[taskName]?.cancel(false)
        
        val future = scheduler.scheduleAtFixedRate({
            scope.launch {
                try {
                    task()
                } catch (e: Exception) {
                    println("Task '$taskName' failed: ${e.message}")
                }
            }
        }, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS)
        
        runningTasks[taskName] = future
    }
    
    /**
     * Schedule one-time task with delay
     * @param taskName Unique name for the task
     * @param delayMs Delay sebelum task run
     * @param task Lambda function yang akan dijalankan
     */
    fun scheduleOneTimeTask(
        taskName: String,
        delayMs: Long = 0,
        task: suspend () -> Unit
    ) {
        val future = scheduler.schedule({
            scope.launch {
                try {
                    task()
                } catch (e: Exception) {
                    println("One-time task '$taskName' failed: ${e.message}")
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        
        runningTasks[taskName] = future
    }
    
    /**
     * Cancel a scheduled task
     */
    fun cancelTask(taskName: String) {
        runningTasks[taskName]?.cancel(false)
        runningTasks.remove(taskName)
    }
    
    /**
     * Check if task is running
     */
    fun isTaskRunning(taskName: String): Boolean {
        return runningTasks[taskName]?.let { !it.isDone } ?: false
    }
    
    /**
     * Cleanup and shutdown scheduler
     */
    fun shutdown() {
        runningTasks.clear()
        scheduler.shutdownNow()
    }
    
    companion object {
        private var instance: BackgroundTaskScheduler? = null
        
        fun getInstance(): BackgroundTaskScheduler {
            if (instance == null) {
                instance = BackgroundTaskScheduler()
            }
            return instance!!
        }
        
        fun resetInstance() {
            instance?.shutdown()
            instance = null
        }
    }
}

/**
 * Supabase Cloud Polling Task
 * Background task untuk sync profiles dari Supabase setiap 15 menit
 * 
 * Equivalent dengan SupabasePollingWorker di APK
 */
class SupabaseCloudPollingTask(
    private val onDataReceived: (String) -> Unit = {},
    private val onError: (Exception) -> Unit = {}
) {
    
    fun start() {
        BackgroundTaskScheduler.getInstance().schedulePeriodicTask(
            taskName = "supabase_polling",
            initialDelayMs = 5000,  // Mulai 5 detik setelah startup
            intervalMs = 15 * 60 * 1000,  // Every 15 minutes (sama seperti APK)
            task = {
                try {
                    syncCloudProfiles()
                } catch (e: Exception) {
                    onError(e)
                }
            }
        )
    }
    
    fun stop() {
        BackgroundTaskScheduler.getInstance().cancelTask("supabase_polling")
    }
    
    private suspend fun syncCloudProfiles() {
        try {
            // Placeholder untuk actual Supabase sync
            // Implementasi actual akan call Supabase API dan sync profiles
            // Untuk sekarang, hanya log jika diperlukan
            println("[SupabasePollingTask] Syncing cloud profiles... (${ java.time.LocalDateTime.now()})")
            onDataReceived("Cloud sync completed")
        } catch (e: Exception) {
            onError(e)
        }
    }
}
