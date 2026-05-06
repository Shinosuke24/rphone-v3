package com.rphone.v3.platform

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.rphone.v3.core.platform.SerialConnection
import com.rphone.v3.core.platform.SerialDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.util.Log
import java.util.concurrent.Executors

/**
 * Android implementation of SerialConnection using USB Serial for Android library
 */
class AndroidSerialConnection(private val context: Context) : SerialConnection {
    
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: com.hoho.android.usbserial.driver.UsbSerialPort? = null
    private val receiveFlow = MutableSharedFlow<ByteArray>()
    private val executor = Executors.newSingleThreadExecutor()
    private val TAG = "AndroidSerialConnection"
    
    override suspend fun connect(devicePath: String): Boolean {
        return try {
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            
            if (availableDrivers.isEmpty()) {
                Log.e(TAG, "No USB drivers found")
                return false
            }
            
            val driver = availableDrivers[0]
            val connection = usbManager.openDevice(driver.device) ?: run {
                Log.e(TAG, "Unable to open device connection")
                return false
            }
            
            serialPort = driver.ports[0]
            serialPort?.open(connection)
            serialPort?.setParameters(9600, 8, 1, 0)
            
            Log.d(TAG, "Connected successfully")
            startReadingThread()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting", e)
            false
        }
    }
    
    override suspend fun disconnect(): Boolean {
        return try {
            serialPort?.close()
            serialPort = null
            Log.d(TAG, "Disconnected")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
            false
        }
    }
    
    override suspend fun send(data: ByteArray): Boolean {
        return try {
            serialPort?.write(data, 100)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data", e)
            false
        }
    }
    
    override fun receive(): Flow<ByteArray> {
        return receiveFlow.asSharedFlow()
    }
    
    override fun isConnected(): Boolean {
        return serialPort != null
    }
    
    override suspend fun getAvailableDevices(): List<SerialDevice> {
        return try {
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).map { driver ->
                val device = driver.device
                SerialDevice(
                    path = "${device.vendorId}:${device.productId}",
                    name = device.productName ?: "USB Device",
                    vendorId = device.vendorId,
                    productId = device.productId
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available devices", e)
            emptyList()
        }
    }
    
    private fun startReadingThread() {
        executor.submit {
            val buffer = ByteArray(1024)
            while (isConnected()) {
                try {
                    val bytesRead = serialPort?.read(buffer, 1000)
                    if (bytesRead != null && bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        try {
                            kotlinx.coroutines.runBlocking {
                                receiveFlow.emit(data)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error emitting data", e)
                        }
                    }
                } catch (e: Exception) {
                    if (isConnected()) {
                        Log.e(TAG, "Error reading from serial port", e)
                    }
                }
            }
        }
    }
}
