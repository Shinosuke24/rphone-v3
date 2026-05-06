package com.rphone.v3.connection

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
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
import java.io.IOException

class UsbSerialManager(private val context: Context) : ConnectionManager {

    companion object {
        private const val TAG           = "UsbSerialManager"
        private const val BAUD_RATE     = 115200
        private const val WRITE_TIMEOUT = 2000
        const val ACTION_USB_PERMISSION = "com.rphone.v3.USB_PERMISSION"
    }

    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _status   = MutableStateFlow(BtStatus.DISCONNECTED)
    override val status: StateFlow<BtStatus> = _status

    private val _dataFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val dataFlow: SharedFlow<String> = _dataFlow

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val readBuffer = StringBuilder()

    // ── Device discovery ─────────────────────────────────────────

    fun findUsbDevice(): UsbDevice? {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        return drivers.firstOrNull()?.device
    }

    // ── Permission ───────────────────────────────────────────────

    fun hasPermission(device: UsbDevice): Boolean =
        usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )
        usbManager.requestPermission(device, pi)
        Log.d(TAG, "USB permission requested for ${device.deviceName}")
    }

    // ── Connect ──────────────────────────────────────────────────

    override suspend fun connect(device: Any?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                _status.value = BtStatus.CONNECTING

                val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                if (drivers.isEmpty()) {
                    Log.e(TAG, "No USB serial drivers found")
                    _status.value = BtStatus.ERROR
                    return@withContext false
                }

                val driver     = drivers.first()
                val usbDevice  = driver.device

                // Guard: permission harus sudah di-grant sebelum openDevice
                if (!usbManager.hasPermission(usbDevice)) {
                    Log.e(TAG, "USB permission not granted — call requestPermission() first")
                    _status.value = BtStatus.DISCONNECTED
                    return@withContext false
                }

                val connection = usbManager.openDevice(usbDevice)
                if (connection == null) {
                    Log.e(TAG, "openDevice returned null after permission granted")
                    _status.value = BtStatus.ERROR
                    return@withContext false
                }

                val serialPort = driver.ports.first()
                serialPort.open(connection)
                serialPort.setParameters(
                    BAUD_RATE,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                port = serialPort

                ioManager = SerialInputOutputManager(
                    serialPort,
                    object : SerialInputOutputManager.Listener {
                        override fun onNewData(data: ByteArray) {
                            val chunk = String(data, Charsets.UTF_8)
                            readBuffer.append(chunk)
                            var idx: Int
                            while (readBuffer.indexOf('\n').also { idx = it } >= 0) {
                                val line = readBuffer.substring(0, idx).trim()
                                readBuffer.delete(0, idx + 1)
                                if (line.isNotBlank()) {
                                    scope.launch { _dataFlow.emit(line) }
                                }
                            }
                        }
                        override fun onRunError(e: Exception) {
                            Log.e(TAG, "Serial read error: ${e.message}")
                            _status.value = BtStatus.DISCONNECTED
                            disconnect()
                        }
                    }
                )
                ioManager?.start()

                _status.value = BtStatus.CONNECTED
                Log.d(TAG, "USB Serial connected at $BAUD_RATE baud")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Connect error: ${e.message}", e)
                _status.value = BtStatus.ERROR
                false
            }
        }
    }

    // ── Send ─────────────────────────────────────────────────────

    override fun sendCommand(command: String) {
        scope.launch {
            try {
                val bytes = "$command\n".toByteArray(Charsets.UTF_8)
                port?.write(bytes, WRITE_TIMEOUT)
                Log.d(TAG, "Sent: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Send error: ${e.message}")
                _status.value = BtStatus.DISCONNECTED
            }
        }
    }

    // ── Disconnect ───────────────────────────────────────────────

    override fun disconnect() {
        try {
            ioManager?.stop()
            ioManager = null
            port?.close()
            port = null
            readBuffer.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        } finally {
            _status.value = BtStatus.DISCONNECTED
        }
    }

    override fun cleanup() = disconnect()
}
