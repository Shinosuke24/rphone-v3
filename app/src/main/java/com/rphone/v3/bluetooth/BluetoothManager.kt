package com.rphone.v3.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.rphone.v3.connection.ConnectionManager
import com.rphone.v3.model.BtStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

class BluetoothManager(private val context: Context) : ConnectionManager {

    companion object {
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val DEVICE_NAME = "ESP32_PowerMonitor"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var reader: BufferedReader? = null

    private val _status = MutableStateFlow(BtStatus.DISCONNECTED)
    override val status: StateFlow<BtStatus> = _status

    private val _dataFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val dataFlow: SharedFlow<String> = _dataFlow

    private var connectionAttemptCount = 0

    fun isBluetoothAvailable(): Boolean = btAdapter != null

    fun isBluetoothEnabled(): Boolean = btAdapter?.isEnabled == true

    fun hasBtPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getPairedDevice(): BluetoothDevice? {
        if (!hasBtPermission()) return null
        return try {
            btAdapter?.bondedDevices?.firstOrNull { it.name == DEVICE_NAME }
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun connect(device: Any?): Boolean {
        val targetDevice = device as? BluetoothDevice ?: getPairedDevice()
        if (targetDevice == null) return false

        return withContext(Dispatchers.IO) {
            _status.value = BtStatus.CONNECTING
            connectionAttemptCount++
            Log.d("BluetoothManager", "Connection attempt #$connectionAttemptCount")

            try {
                val s = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                btAdapter?.cancelDiscovery()
                s.connect()
                socket = s
                outputStream = s.outputStream
                reader = BufferedReader(InputStreamReader(s.inputStream))
                _status.value = BtStatus.CONNECTED
                connectionAttemptCount = 0
                startReading()
                true
            } catch (e: Exception) {
                Log.e("BluetoothManager", "Connect error: ${e.message}")
                _status.value = BtStatus.ERROR
                disconnect()
                false
            }
        }
    }

    private fun startReading() {
        scope.launch {
            try {
                val r = reader ?: return@launch
                while (socket?.isConnected == true) {
                    val line = withContext(Dispatchers.IO) {
                        try { r.readLine() } catch (e: Exception) { null }
                    } ?: break
                    if (line.isNotBlank()) {
                        _dataFlow.emit(line.trim())
                    }
                }
            } catch (e: Exception) {
                Log.e("BluetoothManager", "Read error: ${e.message}")
            } finally {
                _status.value = BtStatus.DISCONNECTED
                disconnect()
            }
        }
    }

    override fun sendCommand(command: String) {
        scope.launch {
            try {
                outputStream?.write("$command\n".toByteArray())
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e("BluetoothManager", "Send error: ${e.message}")
                _status.value = BtStatus.DISCONNECTED
            }
        }
    }

    override fun disconnect() {
        try {
            reader?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e("BluetoothManager", "Disconnect error: ${e.message}")
        } finally {
            socket = null
            outputStream = null
            reader = null
            _status.value = BtStatus.DISCONNECTED
        }
    }

    override fun cleanup() {
        disconnect()
    }

    fun getConnectionAttemptCount(): Int = connectionAttemptCount

    fun resetConnectionAttempts() {
        connectionAttemptCount = 0
    }
}
