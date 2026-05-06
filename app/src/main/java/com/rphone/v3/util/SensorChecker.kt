package com.rphone.v3.util

import android.content.Context
import android.util.Log
import com.rphone.v3.connection.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * SensorChecker — Cek keberadaan INA3221 (0x40) dan ADS1115 (0x48) di bus I2C ESP32.
 *
 * Strategy:
 *  1. Langsung mulai collect dataFlow SEBELUM kirim command (hindari race condition).
 *  2. Kirim "SENSOR_CHECK" segera, lalu retry tiap 3 detik selama collect berlangsung.
 *  3. Tunggu response JSON {"type":"sensor_status",...} — timeout total 10 detik.
 *  4. Jika ada sensor yang tidak ditemukan → tampilkan AlertDialog peringatan.
 *  5. Jika ESP32 tidak balas (firmware lama) → silent skip via onCheckDone(null).
 *
 * Kenapa collect-first: dataFlow adalah SharedFlow — data yang di-emit sebelum collect()
 * dipanggil akan HILANG. Collect harus aktif lebih dulu sebelum command dikirim.
 *
 * JSON yang diharapkan dari ESP32:
 *   {"type":"sensor_status","ina3221":true,"ads1115":false,"ina_addr":"0x40","ads_addr":"0x48"}
 */
class SensorChecker {

    private val TAG = "SensorChecker"

    private var sudahCek = false

    data class SensorStatus(
        val ina3221Ok: Boolean,
        val ads1115Ok: Boolean,
        val inaAddr: String = "0x40",
        val adsAddr: String = "0x48"
    )

    // ─────────────────────────────────────────────────────────────
    // Entry point — panggil setelah CONNECTED
    // ─────────────────────────────────────────────────────────────

    fun cekSetelahKonek(
        scope: CoroutineScope,
        connectionManager: ConnectionManager?,
        context: Context,
        onCheckDone: (status: SensorStatus?) -> Unit = {}
    ) {
        if (sudahCek) return
        sudahCek = true

        val cm = connectionManager ?: run {
            Log.w(TAG, "ConnectionManager null — skip sensor check")
            onCheckDone(null)
            return
        }

        var result: SensorStatus? = null
        var collectDone = false

        // ── Coroutine 1: collect dataFlow (harus mulai duluan!) ──
        val collectJob = scope.launch {
            try {
                withTimeoutOrNull(10_000L) {
                    cm.dataFlow.collect { line ->
                        if (line.contains("\"sensor_status\"")) {
                            try {
                                val json = JSONObject(line)
                                if (json.optString("type") == "sensor_status") {
                                    result = SensorStatus(
                                        ina3221Ok = json.optBoolean("ina3221", false),
                                        ads1115Ok  = json.optBoolean("ads1115", false),
                                        inaAddr    = json.optString("ina_addr", "0x40"),
                                        adsAddr    = json.optString("ads_addr", "0x48")
                                    )
                                    Log.i(TAG, "sensor_status: ina=${result?.ina3221Ok} ads=${result?.ads1115Ok}")
                                    throw CancellationException("found")
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.w(TAG, "JSON parse error: ${e.message} | line=$line")
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Normal — data ditemukan atau timeout
            } finally {
                collectDone = true
            }
        }

        // ── Coroutine 2: kirim command + retry, sambil menunggu collectJob ──
        scope.launch {
            // 100ms agar collectJob sudah aktif subscribe sebelum command dikirim
            delay(100)

            Log.d(TAG, "Mengirim SENSOR_CHECK ke ESP32...")
            cm.sendCommand("SENSOR_CHECK")

            var attempt = 1
            while (!collectDone && attempt <= 3) {
                delay(3_000)
                if (!collectDone) {
                    Log.d(TAG, "Retry SENSOR_CHECK #$attempt...")
                    cm.sendCommand("SENSOR_CHECK")
                    attempt++
                }
            }

            collectJob.join()

            val status = result
            if (status == null) {
                Log.w(TAG, "Tidak ada response sensor_status — firmware mungkin < v3.3.19, skip")
                onCheckDone(null)
                return@launch
            }

            onCheckDone(status)

            if (!status.ina3221Ok || !status.ads1115Ok) {
                tampilkanDialogSensorError(context, status)
            }
        }
    }

    /**
     * Reset state — panggil saat disconnect agar bisa cek ulang saat konek berikutnya.
     */
    fun reset() {
        sudahCek = false
    }

    // ─────────────────────────────────────────────────────────────
    // Dialog peringatan sensor tidak ditemukan
    // ─────────────────────────────────────────────────────────────

    private fun tampilkanDialogSensorError(context: Context, status: SensorStatus) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val sensorErrors = buildList {
                if (!status.ina3221Ok) add("• INA3221 (${status.inaAddr}) — tidak ditemukan")
                if (!status.ads1115Ok) add("• ADS1115 (${status.adsAddr}) — tidak ditemukan")
            }

            val pesan = buildString {
                appendLine("Sensor berikut tidak terdeteksi di bus I2C ESP32:")
                appendLine()
                sensorErrors.forEach { appendLine(it) }
                appendLine()
                append("Periksa koneksi kabel SDA/SCL (GPIO 21/22) dan pastikan sensor mendapat daya.")
            }

            android.app.AlertDialog.Builder(context)
                .setTitle("⚠ Sensor Tidak Ditemukan")
                .setMessage(pesan)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .setCancelable(true)
                .show()
        }
    }
}
