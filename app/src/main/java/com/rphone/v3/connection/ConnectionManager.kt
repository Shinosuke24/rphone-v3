package com.rphone.v3.connection

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import com.rphone.v3.model.BtStatus

interface ConnectionManager {
    
    companion object {
        const val CONNECT_TIMEOUT_MS = 10000L  // 10 seconds
        const val READ_TIMEOUT_MS = 5000L      // 5 seconds
    }

    // State & data flows
    val status: StateFlow<BtStatus>
    val dataFlow: SharedFlow<String>
    
    // Connection methods
    suspend fun connect(device: Any?): Boolean
    fun disconnect()
    fun sendCommand(command: String)
    
    // Utilities
    fun isConnected(): Boolean = status.value == BtStatus.CONNECTED
    fun cleanup()
}
