package com.rphone.v3.util

import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.rphone.v3.R
import com.rphone.v3.connection.ConnectionManager
import com.rphone.v3.connection.UsbSerialManager
import com.rphone.v3.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * FirmwareChecker — Cek versi firmware ESP32 setelah koneksi BT/OTG berhasil.
 *
 * Strategy dua lapis:
 *  1. Coba tanya ESP32 via "FW_VER?" → tunggu "FW_VER:x.x.x" (timeout 8 detik)
 *  2. Kalau ESP32 tidak balas (firmware lama tidak punya handler) →
 *     gunakan KNOWN_FIRMWARE_VERSION sebagai fallback
 *
 * Ubah KNOWN_FIRMWARE_VERSION setiap kali app di-release untuk firmware baru.
 * Kalau server punya versi lebih baru → overlay update muncul.
 */
class FirmwareChecker {

    private val TAG = "FirmwareChecker"

    companion object {
        /**
         * Versi firmware yang "diketahui" app ini.
         * Kalau ESP32 tidak punya handler FW_VER?, nilai ini dipakai untuk compare.
         * Update nilai ini setiap release app yang butuh firmware baru.
         */
        const val KNOWN_FIRMWARE_VERSION = "3.3.18"
    }

    // State
    private var sudahCek = false
    private var firmwareInfo: FirmwareUpdateHelper.FirmwareInfo? = null
    private var deviceFwVersion: String = ""

    // ─────────────────────────────────────────────────────────────
    // Entry point — panggil setelah CONNECTED
    // ─────────────────────────────────────────────────────────────

    fun cekSetelahKonek(
        scope: LifecycleCoroutineScope,
        connectionManager: ConnectionManager?,
        binding: ActivityMainBinding,
        context: android.content.Context,
        onCheckDone: (needsUpdate: Boolean) -> Unit = {}
    ) {
        if (sudahCek) return
        sudahCek = true

        scope.launch {
            try {
                val cm = connectionManager ?: run {
                    Log.w(TAG, "ConnectionManager null — skip firmware check")
                    onCheckDone(false)
                    return@launch
                }

                // Step 1: Coba tanya ESP32 versi firmware-nya
                delay(1_000)
                Log.d(TAG, "Mengirim FW_VER? ke ESP32...")
                cm.sendCommand("FW_VER?")

                val fwVersi = tunggiFwVerResponse(
                    scope     = scope,
                    dataFlow  = cm.dataFlow,
                    timeoutMs = 8_000,
                    onRetry   = {
                        cm.sendCommand("FW_VER?")
                        Log.d(TAG, "Retry FW_VER?...")
                    }
                )

                val versiYangDipakai: String
                if (fwVersi != null) {
                    // ESP32 balas — pakai versi dari device
                    versiYangDipakai = fwVersi
                    Log.i(TAG, "Firmware device (dari ESP32): $fwVersi")
                } else {
                    // ESP32 tidak balas — firmware lama, tidak punya handler FW_VER?
                    // Pakai KNOWN_FIRMWARE_VERSION sebagai fallback
                    versiYangDipakai = KNOWN_FIRMWARE_VERSION
                    Log.w(TAG, "ESP32 tidak balas FW_VER? — pakai fallback: $KNOWN_FIRMWARE_VERSION")
                }

                deviceFwVersion = versiYangDipakai

                // Step 2: Fetch firmware.json dari Supabase
                val info = FirmwareUpdateHelper.fetchFirmwareInfo()
                if (info == null) {
                    Log.w(TAG, "Gagal fetch firmware.json — skip check")
                    onCheckDone(false)
                    return@launch
                }

                firmwareInfo = info
                Log.i(TAG, "Firmware server: ${info.version} — device: $versiYangDipakai")

                // Step 3: Bandingkan
                if (versiYangDipakai.trim() != info.version.trim()) {
                    Log.w(TAG, "FIRMWARE MISMATCH: device=$versiYangDipakai server=${info.version}")
                    tampilkanOverlayFirmware(scope, binding, context, cm, info, versiYangDipakai)
                    onCheckDone(true)
                } else {
                    Log.i(TAG, "Firmware OK — versi cocok: $versiYangDipakai")
                    onCheckDone(false)
                }

            } catch (e: Exception) {
                Log.e(TAG, "cekSetelahKonek error: ${e.message}")
                onCheckDone(false)
            }
        }
    }

    /**
     * Reset state — panggil saat disconnect agar bisa cek ulang saat konek berikutnya.
     */
    fun reset() {
        sudahCek = false
        deviceFwVersion = ""
        firmwareInfo = null
    }

    // ─────────────────────────────────────────────────────────────
    // Tunggu response FW_VER dari dataFlow + retry paralel
    // ─────────────────────────────────────────────────────────────

    private suspend fun tunggiFwVerResponse(
        scope: LifecycleCoroutineScope,
        dataFlow: SharedFlow<String>,
        timeoutMs: Long,
        onRetry: suspend () -> Unit
    ): String? {
        var result: String? = null

        val retryJob: Job = scope.launch {
            while (true) {
                delay(2_000)
                if (result == null) onRetry()
            }
        }

        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                dataFlow.collect { line ->
                    if (line.startsWith("FW_VER:")) {
                        result = line.removePrefix("FW_VER:").trim()
                        throw kotlinx.coroutines.CancellationException("found")
                    }
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) { }

        retryJob.cancel()
        return result
    }

    // ─────────────────────────────────────────────────────────────
    // Tampilkan overlay firmware mismatch — wajib di main thread
    // ─────────────────────────────────────────────────────────────

    private fun tampilkanOverlayFirmware(
        scope: LifecycleCoroutineScope,
        binding: ActivityMainBinding,
        context: android.content.Context,
        cm: ConnectionManager,
        info: FirmwareUpdateHelper.FirmwareInfo,
        deviceVersi: String
    ) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val overlay   = binding.root.findViewById<View>(R.id.overlayFirmwareUpdate) ?: return@post
            val tvDevice  = binding.root.findViewById<TextView>(R.id.tvFwVersiDevice)
            val tvServer  = binding.root.findViewById<TextView>(R.id.tvFwVersiServer)
            val tvLog     = binding.root.findViewById<TextView>(R.id.tvFwChangelog)
            val btnUpdate = binding.root.findViewById<TextView>(R.id.btnUpdateFirmware)

            tvDevice?.text = "Device: $deviceVersi"
            tvServer?.text = "Server: ${info.version}"
            tvLog?.text    = info.changelog

            overlay.visibility = View.VISIBLE
            Log.d(TAG, "Overlay VISIBLE — device=$deviceVersi server=${info.version}")

            btnUpdate?.setOnClickListener {
                if (cm !is UsbSerialManager) {
                    android.widget.Toast.makeText(
                        context,
                        "Hubungkan kabel OTG terlebih dahulu untuk update firmware",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                overlay.visibility = View.GONE
                mulaiUpdateFirmware(scope, binding, context, cm, info)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Proses update firmware
    // ─────────────────────────────────────────────────────────────

    private fun mulaiUpdateFirmware(
        scope: LifecycleCoroutineScope,
        binding: ActivityMainBinding,
        context: android.content.Context,
        cm: ConnectionManager,
        info: FirmwareUpdateHelper.FirmwareInfo
    ) {
        val overlayFlash = binding.root.findViewById<View>(R.id.overlayFirmwareFlashing) ?: return
        val tvPersen     = binding.root.findViewById<TextView>(R.id.tvFwFlashPersen)
        val pbFlash      = binding.root.findViewById<android.widget.ProgressBar>(R.id.pbFwFlash)
        val tvStatus     = binding.root.findViewById<TextView>(R.id.tvFwFlashStatus)
        val dot1 = binding.root.findViewById<View>(R.id.fwDot1)
        val dot2 = binding.root.findViewById<View>(R.id.fwDot2)
        val dot3 = binding.root.findViewById<View>(R.id.fwDot3)

        overlayFlash.visibility = View.VISIBLE
        pbFlash?.progress = 0
        tvPersen?.text = "0%"
        tvStatus?.text = "Mempersiapkan..."

        val dotsJob = scope.launch {
            val dots = listOf(dot1, dot2, dot3)
            var i = 0
            while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) {
                val idx = i % 3
                dots.forEachIndexed { di, dot ->
                    dot?.animate()?.alpha(if (di == idx) 1f else 0.3f)?.setDuration(200)?.start()
                }
                i++
                delay(350)
            }
            dots.forEach { it?.alpha = 0.3f }
        }

        scope.launch {
            val downloaded = FirmwareUpdateHelper.downloadAllFirmware(
                context    = context,
                info       = info,
                onProgress = { progress ->
                    val displayProgress = progress / 2
                    pbFlash?.progress = displayProgress
                    tvPersen?.text = "$displayProgress%"
                },
                onStatus   = { status -> tvStatus?.text = status }
            )

            if (downloaded == null) {
                dotsJob.cancel()
                overlayFlash.visibility = View.GONE
                android.widget.Toast.makeText(
                    context,
                    "Gagal mengunduh firmware. Cek koneksi internet.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                binding.root.findViewById<View>(R.id.overlayFirmwareUpdate)?.visibility = View.VISIBLE
                return@launch
            }

            tvStatus?.text = "Mengirim ke ESP32..."

            val sukses = FirmwareUpdateHelper.flashAllFirmware(
                files       = downloaded,
                sendCommand = { cmd -> cm.sendCommand(cmd) },
                dataFlow    = cm.dataFlow,
                onProgress  = { progress ->
                    val displayProgress = 50 + progress / 2
                    pbFlash?.progress = displayProgress
                    tvPersen?.text = "$displayProgress%"
                },
                onStatus    = { status -> tvStatus?.text = status }
            )

            dotsJob.cancel()
            overlayFlash.visibility = View.GONE

            if (sukses) {
                android.widget.Toast.makeText(
                    context,
                    "✓ Firmware berhasil diperbarui! Reconnect ke ESP32.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                reset()
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Gagal flash firmware. Pastikan OTG terhubung dan coba lagi.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                binding.root.findViewById<View>(R.id.overlayFirmwareUpdate)?.visibility = View.VISIBLE
            }
        }
    }
}
