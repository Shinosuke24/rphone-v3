package com.rphone.v3.core.platform

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic interface for serial communication (USB/Bluetooth).
 * Implementations will handle Android USB Serial or Java desktop serial libraries.
 */
interface SerialConnection {
    
    /**
     * Connect to a device
     */
    suspend fun connect(devicePath: String): Boolean
    
    /**
     * Disconnect from device
     */
    suspend fun disconnect(): Boolean
    
    /**
     * Send raw data
     */
    suspend fun send(data: ByteArray): Boolean
    
    /**
     * Receive data as flow
     */
    fun receive(): Flow<ByteArray>
    
    /**
     * Check if connected
     */
    fun isConnected(): Boolean
    
    /**
     * Get list of available serial ports/devices
     */
    suspend fun getAvailableDevices(): List<SerialDevice>
}

/**
 * Represents a serial device
 */
data class SerialDevice(
    val path: String,
    val name: String,
    val vendorId: Int = -1,
    val productId: Int = -1
)
