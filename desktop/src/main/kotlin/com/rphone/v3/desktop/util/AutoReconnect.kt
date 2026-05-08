package com.rphone.v3.desktop.util

import com.rphone.v3.desktop.platform.DesktopSerialConnection
import kotlinx.coroutines.*
import java.util.logging.Logger

/**
 * AutoReconnect — Automatic reconnection with exponential backoff.
 *
 * Strategy:
 *  - Max 5 retry attempts
 *  - Initial delay: 3 seconds
 *  - Exponential backoff: delay × 1.5 per retry
 *  - Maximum reconnect window: 30 seconds
 */
class AutoReconnect(
    private val serialConnection: DesktopSerialConnection
) {
    private val logger = Logger.getLogger(AutoReconnect::class.java.name)

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 3000L
        private const val MAX_RECONNECT_DURATION_MS = 30000L
        private const val BACKOFF_MULTIPLIER = 1.5
    }

    private var reconnectJob: Job? = null
    private var retryAttempts = 0
    private var lastConnectionTime = 0L
    private val reconnectStatusList = mutableListOf<ReconnectStatus>()

    data class ReconnectStatus(
        val attempt: Int,
        val maxAttempts: Int,
        val isRetrying: Boolean,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun mulai() {
        logger.info("AutoReconnect started")
    }

    fun berhenti() {
        reconnectJob?.cancel()
        reconnectJob = null
        retryAttempts = 0
        logger.info("AutoReconnect stopped")
    }

    fun isConnected(): Boolean {
        return serialConnection.isConnected()
    }

    suspend fun handleDisconnection(): Boolean {
        logger.warning("Connection lost, attempting reconnect...")
        return attemptReconnect()
    }

    private suspend fun attemptReconnect(): Boolean {
        val startTime = System.currentTimeMillis()
        retryAttempts = 0

        while (retryAttempts < MAX_RETRY_ATTEMPTS) {
            val elapsedTime = System.currentTimeMillis() - startTime

            if (elapsedTime > MAX_RECONNECT_DURATION_MS) {
                logger.severe("Reconnect timeout after ${elapsedTime}ms")
                publishReconnectStatus(
                    attempt = retryAttempts,
                    maxAttempts = MAX_RETRY_ATTEMPTS,
                    isRetrying = false,
                    message = "Reconnect timeout"
                )
                return false
            }

            retryAttempts++
            val delayMs = (RETRY_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, (retryAttempts - 1).toDouble())).toLong()

            publishReconnectStatus(
                attempt = retryAttempts,
                maxAttempts = MAX_RETRY_ATTEMPTS,
                isRetrying = true,
                message = "Retry attempt $retryAttempts/$MAX_RETRY_ATTEMPTS (delay: ${delayMs}ms)"
            )

            logger.info("Retry attempt $retryAttempts/$MAX_RETRY_ATTEMPTS (delay: ${delayMs}ms)")

            delay(delayMs)

            val connected = serialConnection.connect(null)
            if (connected) {
                lastConnectionTime = System.currentTimeMillis()
                logger.info("Reconnected successfully on attempt $retryAttempts")
                publishReconnectStatus(
                    attempt = retryAttempts,
                    maxAttempts = MAX_RETRY_ATTEMPTS,
                    isRetrying = false,
                    message = "Reconnected on attempt $retryAttempts"
                )
                retryAttempts = 0
                return true
            }
        }

        logger.severe("Failed to reconnect after $MAX_RETRY_ATTEMPTS attempts")
        publishReconnectStatus(
            attempt = MAX_RETRY_ATTEMPTS,
            maxAttempts = MAX_RETRY_ATTEMPTS,
            isRetrying = false,
            message = "Failed to reconnect after $MAX_RETRY_ATTEMPTS attempts"
        )
        return false
    }

    private fun publishReconnectStatus(
        attempt: Int,
        maxAttempts: Int,
        isRetrying: Boolean,
        message: String
    ) {
        val status = ReconnectStatus(
            attempt = attempt,
            maxAttempts = maxAttempts,
            isRetrying = isRetrying,
            message = message
        )
        reconnectStatusList.add(status)
        logger.info("ReconnectStatus: $message")
    }

    fun getReconnectHistory(): List<ReconnectStatus> {
        return reconnectStatusList.toList()
    }

    fun clearHistory() {
        reconnectStatusList.clear()
    }
}
