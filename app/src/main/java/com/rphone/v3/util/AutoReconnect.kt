package com.rphone.v3.util

import android.util.Log
import com.rphone.v3.connection.ConnectionManager
import com.rphone.v3.model.BtStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow

class AutoReconnect(
    private var connectionManager: ConnectionManager,
    private val lifecycleScope: CoroutineScope
) {

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 3000L
        private const val MAX_RECONNECT_DURATION_MS = 30000L
        private const val BACKOFF_MULTIPLIER = 1.5
    }

    private var reconnectJob: Job? = null
    private var retryAttempts = 0
    private var lastConnectionTime = 0L
    private val reconnectStatusFlow = MutableSharedFlow<ReconnectStatus>(extraBufferCapacity = 1)

    data class ReconnectStatus(
        val attempt: Int,
        val maxAttempts: Int,
        val isRetrying: Boolean,
        val message: String
    )

    fun setConnectionManager(manager: ConnectionManager) {
        connectionManager = manager
    }

    fun mulai() {
        monitorConnection()
    }

    fun berhenti() {
        reconnectJob?.cancel()
        reconnectJob = null
        retryAttempts = 0
    }

    fun getReconnectStatusFlow() = reconnectStatusFlow

    private fun monitorConnection() {
        reconnectJob = lifecycleScope.launch {
            var lastStatus = BtStatus.DISCONNECTED
            val startTime = System.currentTimeMillis()

            connectionManager.status.collectLatest { status ->
                when (status) {
                    BtStatus.CONNECTED -> {
                        lastConnectionTime = System.currentTimeMillis()
                        if (lastStatus != BtStatus.CONNECTED) {
                            Log.i("AutoReconnect", "Connection restored")
                            retryAttempts = 0
                        }
                    }
                    BtStatus.DISCONNECTED, BtStatus.ERROR -> {
                        if (lastStatus == BtStatus.CONNECTED) {
                            Log.w("AutoReconnect", "Connection lost, attempting reconnect...")
                            attemptReconnect(startTime)
                        }
                    }
                    else -> {
                        Log.d("AutoReconnect", "Status changed to: $status")
                    }
                }
                lastStatus = status
            }
        }
    }

    private suspend fun attemptReconnect(startTime: Long) {
        retryAttempts = 0

        while (retryAttempts < MAX_RETRY_ATTEMPTS) {
            val elapsedTime = System.currentTimeMillis() - startTime

            if (elapsedTime > MAX_RECONNECT_DURATION_MS) {
                Log.e("AutoReconnect", "Reconnect timeout after ${elapsedTime}ms")
                publishReconnectStatus(
                    attempt = retryAttempts,
                    maxAttempts = MAX_RETRY_ATTEMPTS,
                    isRetrying = false,
                    message = "Reconnect timeout"
                )
                break
            }

            retryAttempts++
            val delayMs = (RETRY_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, (retryAttempts - 1).toDouble())).toLong()

            publishReconnectStatus(
                attempt = retryAttempts,
                maxAttempts = MAX_RETRY_ATTEMPTS,
                isRetrying = true,
                message = "Retry attempt $retryAttempts/$MAX_RETRY_ATTEMPTS"
            )

            Log.d("AutoReconnect", "Retry attempt $retryAttempts/$MAX_RETRY_ATTEMPTS (delay: ${delayMs}ms)")

            delay(delayMs)

            val connected = connectionManager.connect(null)
            if (connected) {
                Log.i("AutoReconnect", "Reconnected successfully on attempt $retryAttempts")
                publishReconnectStatus(
                    attempt = retryAttempts,
                    maxAttempts = MAX_RETRY_ATTEMPTS,
                    isRetrying = false,
                    message = "Reconnected on attempt $retryAttempts"
                )
                retryAttempts = 0
                return
            }
        }

        Log.e("AutoReconnect", "Failed to reconnect after $MAX_RETRY_ATTEMPTS attempts")
        publishReconnectStatus(
            attempt = MAX_RETRY_ATTEMPTS,
            maxAttempts = MAX_RETRY_ATTEMPTS,
            isRetrying = false,
            message = "Failed to reconnect after $MAX_RETRY_ATTEMPTS attempts"
        )
    }

    private suspend fun publishReconnectStatus(
        attempt: Int,
        maxAttempts: Int,
        isRetrying: Boolean,
        message: String
    ) {
        reconnectStatusFlow.emit(
            ReconnectStatus(
                attempt = attempt,
                maxAttempts = maxAttempts,
                isRetrying = isRetrying,
                message = message
            )
        )
    }
}
