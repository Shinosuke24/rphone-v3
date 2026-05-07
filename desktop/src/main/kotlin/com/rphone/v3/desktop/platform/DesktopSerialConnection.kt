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
    private val receiveFlow = MutableSharedFlow<ByteArray>()
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
                SerialDevice(
                    path = port.systemPortName,
                    name = port.descriptivePortName ?: port.systemPortName
                )
            }
        } catch (e: Exception) {
            logger.error("Error getting available devices", e)
            emptyList()
        }
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
                                try {
                                    kotlinx.coroutines.runBlocking {
                                        receiveFlow.emit(data)
                                    }
                                } catch (e: Exception) {
                                    logger.error("Error emitting data", e)
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
}
