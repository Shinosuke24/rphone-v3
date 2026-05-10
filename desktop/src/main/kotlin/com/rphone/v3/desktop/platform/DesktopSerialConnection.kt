package com.rphone.v3.desktop.platform

import com.fazecast.jSerialComm.SerialPort
import com.rphone.v3.core.platform.SerialConnection
import com.rphone.v3.core.platform.SerialDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

/**
 * Windows/Desktop implementation of SerialConnection using jSerialComm
 */
class DesktopSerialConnection : SerialConnection {
    
    private val logger = LoggerFactory.getLogger(this::class.java)
    private var serialPort: SerialPort? = null
    private val receiveFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 100)
    private val executor = Executors.newSingleThreadExecutor()
    private val readBuffer = StringBuilder()
    
    override suspend fun connect(devicePath: String): Boolean {
        return try {
            val port = SerialPort.getCommPort(devicePath)
            port.setComPortParameters(
                115200,         // Baud rate (ESP commonly uses 115200)
                8,              // Data bits
                1,              // Stop bits
                0               // No parity
            )
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)
            
            if (port.openPort()) {
                serialPort = port
                logger.info("Connected to $devicePath")
                startReadingThread()
                true
            } else {
                logger.error("Failed to open port: $devicePath")
                false
            }
        } catch (e: Exception) {
            logger.error("Error connecting to $devicePath", e)
            false
        }
    }
    
    override suspend fun disconnect(): Boolean {
        return try {
            serialPort?.closePort()
            serialPort = null
            readBuffer.clear()
            logger.info("Disconnected")
            true
        } catch (e: Exception) {
            logger.error("Error disconnecting", e)
            false
        }
    }
    
    override suspend fun send(data: ByteArray): Boolean {
        return try {
            serialPort?.writeBytes(data, data.size)
            true
        } catch (e: Exception) {
            logger.error("Error sending data", e)
            false
        }
    }
    
    override fun receive(): Flow<ByteArray> {
        return receiveFlow.asSharedFlow()
    }
    
    override fun isConnected(): Boolean {
        return serialPort?.isOpen == true
    }
    
    override suspend fun getAvailableDevices(): List<SerialDevice> {
        return try {
            SerialPort.getCommPorts().map { port ->
                val descName = port.descriptivePortName ?: port.systemPortName
                // Auto-detect Bluetooth devices (Windows lists them as "COM" ports with "Bluetooth" in description)
                val displayName = if (descName.contains("Bluetooth", ignoreCase = true)) {
                    "${port.systemPortName} [Bluetooth]"
                } else {
                    descName
                }
                
                SerialDevice(
                    path = port.systemPortName,
                    name = displayName
                )
            }
        } catch (e: Exception) {
            logger.error("Error getting available devices", e)
            emptyList()
        }
    }
    
    /**
     * Get only Bluetooth COM ports
     * Windows automatically exposes Bluetooth serial devices as COM ports
     */
    suspend fun getBluetoothDevices(): List<SerialDevice> {
        return getAvailableDevices().filter { it.name.contains("Bluetooth", ignoreCase = true) }
    }
    
    /**
     * Get only USB/wired COM ports
     */
    suspend fun getUsbDevices(): List<SerialDevice> {
        return getAvailableDevices().filter { !it.name.contains("Bluetooth", ignoreCase = true) }
    }
    
    private fun startReadingThread() {
        executor.submit {
            val buffer = ByteArray(1024)
            while (isConnected()) {
                try {
                    val bytesRead = serialPort?.readBytes(buffer, buffer.size)
                    if (bytesRead != null && bytesRead > 0) {
                        val chunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                        readBuffer.append(chunk)
                        var newlineIndex = readBuffer.indexOf("\n")
                        while (newlineIndex >= 0) {
                            val line = readBuffer.substring(0, newlineIndex).trim()
                            readBuffer.delete(0, newlineIndex + 1)
                            if (line.isNotBlank()) {
                                val data = line.toByteArray(Charsets.UTF_8)
                                if (!receiveFlow.tryEmit(data)) {
                                    logger.warn("Dropped serial packet because the receive buffer was full")
                                }
                            }
                            newlineIndex = readBuffer.indexOf("\n")
                        }
                    }
                } catch (e: Exception) {
                    if (isConnected()) {
                        logger.error("Error reading from serial port", e)
                    }
                }
            }
        }
    }
    
    /**
     * Convenience method to send a text command
     */
    suspend fun sendCommand(command: String): Boolean {
        val cmdWithNewline = "$command\n"
        return send(cmdWithNewline.toByteArray(Charsets.UTF_8))
    }
    
    /**
     * Convenience method to read available data as string
     */
    fun readData(): String {
        val data = readBuffer.toString()
        readBuffer.clear()
        return data
    }
    
    /**
     * Connect convenience method - accepts null device to use default/first available
     */
    suspend fun connect(device: SerialDevice?): Boolean {
        val devicePath = device?.path ?: run {
            val devices = getAvailableDevices()
            devices.firstOrNull()?.path ?: return false
        }
        return connect(devicePath)
    }
}
