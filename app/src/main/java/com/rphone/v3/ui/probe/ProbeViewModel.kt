package com.rphone.v3.ui.probe

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rphone.v3.connection.ConnectionManager
import com.rphone.v3.model.BtStatus
import com.rphone.v3.model.ProbeData
import com.rphone.v3.model.ProbeMode
import com.rphone.v3.util.JsonParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class ProbeViewModel : ViewModel() {

    // ── State yang diobserve Fragment ──
    val activeMode  = MutableLiveData<ProbeMode>(ProbeMode.VOLT)
    val probeData   = MutableLiveData<ProbeData?>(null)
    val btStatus    = MutableLiveData<BtStatus>(BtStatus.DISCONNECTED)
    val historyPassif = MutableLiveData<List<ProbeHistoryItem>>(emptyList())  // DIODE / OHM
    val historyAktif  = MutableLiveData<List<ProbeHistoryItem>>(emptyList())  // VOLT

    // Task 26 — TTS event: Fragment observe ini untuk bacakan nilai
    val ttsEvent = MutableLiveData<Pair<String, ProbeMode>?>(null)
    val isHold      = MutableLiveData<Boolean>(false)
    val isSettling  = MutableLiveData<Boolean>(false)   // Fragment observe ini untuk UI "---"
    @Volatile private var isSettlingInternal = false
    @Volatile private var hasStartedMeasuring = false

    private var connectionManager: ConnectionManager? = null

    private var statusJob:   Job? = null
    private var dataJob:     Job? = null
    private var pollingJob:  Job? = null
    private var settlingJob: Job? = null

    // Stabilitas history
    private var stableDisplay   = ""
    private var stableStartTime = 0L
    private var lastShortDisplay = ""
    private val STABLE_DURATION_MS = 500L

    // Median filter
    private val diodeBuffer = ArrayDeque<Float>(5)
    private val ohmBuffer   = ArrayDeque<Float>(5)
    private var lastStableOhm = 0f
    private val MEDIAN_SIZE = 3  // turun dari 5 → lebih responsif (3×150ms=450ms vs 750ms)

    // Polling interval — 150ms cukup responsif, tidak spam ESP32
    private val POLL_INTERVAL_MS = 150L

    // Settle cooldown per mode — sinkron dengan firmware v3.3.8 smart duration
    // OHM↔DIODE: relay tidak gerak → 30ms
    // VOLT→DIODE/OHM: engage → 60ms
    // *→VOLT: release lebih lambat → 250ms
    private fun settleCooldown(from: ProbeMode, to: ProbeMode): Long = when {
        to == ProbeMode.VOLT                                  -> 250L
        from == ProbeMode.OHM  && to == ProbeMode.DIODE      -> 30L
        from == ProbeMode.DIODE && to == ProbeMode.OHM       -> 30L
        else                                                  -> 60L  // VOLT→DIODE/OHM
    }

    data class ProbeHistoryItem(
        val id: Long = System.currentTimeMillis(),
        val mode: ProbeMode,
        val display: String,
        val timestamp: Long = System.currentTimeMillis(),
        val label: String = "",
        val isPending: Boolean = true
    )

    // ================================================================
    // RESYNC — dipanggil saat Fragment resume, kirim SET_PROBE_* ulang
    // tanpa reset history/mode — sinkronkan firmware dengan activeMode Android
    // ================================================================

    fun resyncMode() {
        if (settlingJob?.isActive == true) {
            Log.d("ProbeVM", "resyncMode() SKIP — settling aktif")
            return
        }
        val mode = activeMode.value ?: ProbeMode.VOLT
        Log.d("ProbeVM", "resyncMode() mode=$mode cm=${connectionManager != null}")
        stopPolling()
        settlingJob?.cancel()
        hasStartedMeasuring = false
        resetStable()

        val relayCmd = when (mode) {
            ProbeMode.VOLT  -> "SET_PROBE_VOLT"
            ProbeMode.DIODE -> "SET_PROBE_DIODE"
            ProbeMode.OHM   -> "SET_PROBE_OHM"
        }
        connectionManager?.sendCommand(relayCmd)

        val cooldown = when (mode) {
            ProbeMode.VOLT  -> 250L
            else             -> 100L
        }
        isSettlingInternal = true
        isSettling.value = true
        settlingJob = viewModelScope.launch {
            delay(cooldown)
            if (isActive) {
                isSettlingInternal = false
                isSettling.value = false
                if (btStatus.value == BtStatus.CONNECTED && isHold.value == false) {
                    startPolling()
                }
            }
        }
    }

    // ================================================================
    // OBSERVING — dipanggil dari Fragment saat connectionManager berubah
    // ================================================================

    fun startObserving(cm: ConnectionManager) {
        if (cm === connectionManager && statusJob?.isActive == true) {
            Log.d("ProbeVM", "startObserving SKIP — same cm, already active")
            return
        }
        statusJob?.cancel()
        dataJob?.cancel()
        connectionManager = cm

        statusJob = viewModelScope.launch {
            var lastStatus: BtStatus? = null
            cm.status.collectLatest { status ->
                if (status == lastStatus) return@collectLatest
                lastStatus = status
                btStatus.value = status
                if (status == BtStatus.CONNECTED) {
                    Log.d("ProbeVM", "startObserving CONNECTED → resyncMode")
                    resyncMode()
                } else {
                    stopPolling()
                }
            }
        }

        dataJob = viewModelScope.launch {
            cm.dataFlow.collectLatest { json ->
                handleIncomingJson(json)
            }
        }
    }

    fun stopObserving() {
        statusJob?.cancel()
        dataJob?.cancel()
        pollingJob?.cancel()
        settlingJob?.cancel()
        statusJob   = null
        dataJob     = null
        pollingJob  = null
        settlingJob = null
        connectionManager = null
    }

    // ================================================================
    // JSON HANDLER — satu pintu masuk semua data dari ESP32
    // ================================================================

    private fun handleIncomingJson(json: String) {
        // Cek SETTLING duluan sebelum parse — cegah parse error
        if (json.contains("\"probe\":\"SETTLING\"")) {
            onSettlingReceived()
            return
        }

        val raw = JsonParser.parseProbeData(json) ?: return

        // Jika settling masih aktif — buang data, tunggu settle selesai
        if (isSettlingInternal) return

        val filtered = filterProbeData(raw)
        probeData.value = filtered
        addToHistory(filtered)
        checkContinuity(filtered)
    }

    // ================================================================
    // SETTLING — semua logika settle ada di sini, Fragment hanya observe
    // ================================================================

    private fun onSettlingReceived() {
        if (isSettlingInternal) return

        // settlingJob tidak aktif = SETTLING datang saat polling normal
        // → kirim SET_PROBE_* ulang untuk resync firmware (bisa terjadi setelah ESP32 restart)
        val resyncCmd = when (activeMode.value) {
            ProbeMode.VOLT  -> "SET_PROBE_VOLT"
            ProbeMode.DIODE -> "SET_PROBE_DIODE"
            ProbeMode.OHM   -> "SET_PROBE_OHM"
            else             -> null
        }
        if (resyncCmd != null) connectionManager?.sendCommand(resyncCmd)

        isSettlingInternal = true
        isSettling.value = true

        settlingJob?.cancel()
        val cooldown = when (activeMode.value) {
            ProbeMode.VOLT -> 250L
            else           -> 100L
        }
        settlingJob = viewModelScope.launch {
            delay(cooldown)
            if (isActive) {
                isSettlingInternal = false
                isSettling.value = false
            }
        }
    }

    // ================================================================
    // MODE SWITCH — satu fungsi, tidak ada double command
    // ================================================================

    fun switchMode(mode: ProbeMode) {
        if (activeMode.value == mode) return
        // TIDAK block saat isSettling — cancel settling lama, langsung switch
        // Ini penting agar tap tab saat BUZZ_PROBE_SAVED tidak diabaikan

        val fromMode = activeMode.value ?: ProbeMode.VOLT
        val cooldown = settleCooldown(fromMode, mode)

        stopPolling()
        settlingJob?.cancel()   // cancel settling lama jika ada
        probeData.value = null
        activeMode.value = mode
        // TIDAK clear history saat switch mode — history tetap tampil
        // Reset hanya via onResume (keluar-masuk tab)
        resetStable()
        hasStartedMeasuring = false
        isHold.value = false

        // Kirim relay command ke ESP32
        val relayCmd = when (mode) {
            ProbeMode.VOLT  -> "SET_PROBE_VOLT"
            ProbeMode.DIODE -> "SET_PROBE_DIODE"
            ProbeMode.OHM   -> "SET_PROBE_OHM"
        }
        connectionManager?.sendCommand(relayCmd)

        // Set settling dengan cooldown sesuai mode
        isSettlingInternal = true
        isSettling.value = true
        settlingJob = viewModelScope.launch {
            delay(cooldown)
            if (isActive) {
                isSettlingInternal = false
                isSettling.value = false
                if (btStatus.value == BtStatus.CONNECTED && isHold.value == false) {
                    startPolling()
                }
            }
        }
    }

    // ================================================================
    // POLLING — satu job, interval 150ms
    // ================================================================

    fun startPolling() {
        val callerStart = Thread.currentThread().stackTrace
            .getOrNull(3)?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"
        Log.d("ProbeVM", "startPolling() settling=${isSettling.value} hold=${isHold.value} status=${btStatus.value} caller=$callerStart")
        if (pollingJob?.isActive == true) return         // sudah jalan, skip
        if (isSettlingInternal) return             // jangan poll saat settling
        if (isHold.value == true) return                 // hold aktif
        if (btStatus.value != BtStatus.CONNECTED) return // belum konek

        pollingJob = viewModelScope.launch {
            while (isActive) {
                // Double-check guard di dalam loop — cegah kirim saat settling tiba-tiba aktif
                if (!isSettlingInternal && isHold.value == false) {
                    val cmd = activeMode.value?.commandName
                    if (cmd != null) connectionManager?.sendCommand(cmd)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        val callerStop = Thread.currentThread().stackTrace
            .getOrNull(3)?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"
        Log.d("ProbeVM", "stopPolling() pollingJob active=${pollingJob?.isActive} caller=$callerStop")
        pollingJob?.cancel()
        pollingJob = null
    }

    fun toggleHold() {
        val hold = isHold.value ?: false
        if (!hold) {
            isHold.value = true
            stopPolling()
        } else {
            isHold.value = false
            startPolling()
        }
    }

    fun clearHistory() {
        historyPassif.value = emptyList()
        historyAktif.value  = emptyList()
    }

    fun clearHistoryPassif() { historyPassif.value = emptyList() }
    fun clearHistoryAktif()  { historyAktif.value  = emptyList() }

    // ── Helper: cari item ada di list mana ──
    private fun liveDataFor(id: Long): MutableLiveData<List<ProbeHistoryItem>>? {
        return when {
            historyPassif.value?.any { it.id == id } == true -> historyPassif
            historyAktif.value?.any  { it.id == id } == true -> historyAktif
            else -> null
        }
    }

    fun confirmItem(id: Long) {
        val ld   = liveDataFor(id) ?: return
        val list = ld.value?.toMutableList() ?: return
        val idx  = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(isPending = false)
        ld.value = list
    }

    fun cancelItem(id: Long) {
        val ld = liveDataFor(id) ?: return
        ld.value = ld.value?.filter { it.id != id }
    }

    fun editItemValue(id: Long, newDisplay: String) {
        val ld   = liveDataFor(id) ?: return
        val list = ld.value?.toMutableList() ?: return
        val idx  = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(display = newDisplay, isPending = false)
        ld.value = list
    }

    fun editItemLabel(id: Long, newLabel: String) {
        val ld   = liveDataFor(id) ?: return
        val list = ld.value?.toMutableList() ?: return
        val idx  = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(label = newLabel, isPending = false)
        ld.value = list
    }

    fun deleteItem(id: Long) {
        val ld = liveDataFor(id) ?: return
        ld.value = ld.value?.filter { it.id != id }
    }

    // ── Simpan ke CSV — MediaStore (sama seperti UART, path Documents/RPhone) ──
    fun simpanKeFile(context: android.content.Context, namaKonektor: String): String? {
        return try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val fileName = "probe_${namaKonektor}_$timestamp.csv"

            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put("relative_path", android.os.Environment.DIRECTORY_DOCUMENTS + "/RPhone")
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(
                android.provider.MediaStore.Files.getContentUri("external"), contentValues
            ) ?: return null

            resolver.openOutputStream(uri)?.use { out ->
                val sb = StringBuilder()
                sb.append("Konektor,$namaKonektor\n")
                sb.append("Tanggal,$timestamp\n\n")
                sb.append("No,Kaki,Mode,Nilai\n")

                val passif   = historyPassif.value ?: emptyList()
                val aktif    = historyAktif.value  ?: emptyList()
                val allItems = (passif + aktif).sortedBy { it.timestamp }

                allItems.forEachIndexed { i, item ->
                    val kaki = when {
                        item.label.isNotEmpty() && item.label != "GND" -> item.label
                        item.display == "GND" || item.label == "GND"   -> "GND"
                        else -> "Kaki ${i + 1}"
                    }
                    sb.append("${i + 1},$kaki,${item.mode.name},${item.display}\n")
                }
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            }

            android.util.Log.d("ProbeVM", "Tersimpan MediaStore: $fileName")
            fileName
        } catch (e: Exception) {
            android.util.Log.e("ProbeVM", "Gagal simpan: ${e.message}")
            null
        }
    }

    // ================================================================
    // FILTER — median filter untuk DIODE & OHM
    // ================================================================

    private fun resetStable() {
        stableDisplay    = ""
        stableStartTime  = 0L
        lastShortDisplay = ""
        diodeBuffer.clear()
        ohmBuffer.clear()
        lastStableOhm = 0f
        voltBuffer.clear()
    }

    private fun medianOf(buf: ArrayDeque<Float>): Float {
        if (buf.isEmpty()) return 0f
        val sorted = buf.sorted()
        return sorted[sorted.size / 2]
    }

    private fun addToBuffer(buf: ArrayDeque<Float>, value: Float) {
        if (buf.size >= MEDIAN_SIZE) buf.removeFirst()
        buf.addLast(value)
    }

    private val voltBuffer  = ArrayDeque<Float>(5)

    private fun filterProbeData(data: ProbeData): ProbeData {
        val mode = activeMode.value ?: return data
        return when (mode) {
            ProbeMode.VOLT -> {
                // Paksa 0.00V jika di bawah threshold — noise floating input
                if (data.volt < 0.3f) {
                    voltBuffer.clear()
                    return data.copy(volt = 0f, display = "0.00 V")
                }
                // Median filter 3 sampel — stabilkan nilai VOLT
                addToBuffer(voltBuffer, data.volt)
                if (voltBuffer.size < MEDIAN_SIZE) return data  // belum cukup sampel, tampil apa adanya
                val median = medianOf(voltBuffer)
                val display = String.format(Locale.US, "%.2f V", median)
                data.copy(volt = median, display = display)
            }

            ProbeMode.DIODE -> {
                if (data.mode == "OPEN" || data.mode == "SHORT"
                    || data.display == "OL" || data.display == "0mV"
                    || data.display == "0.000 V") {
                    diodeBuffer.clear()
                    return data
                }
                val vdropMv = data.vdrop * 1000f
                addToBuffer(diodeBuffer, vdropMv)
                if (diodeBuffer.size < MEDIAN_SIZE) return data
                val median = medianOf(diodeBuffer)
                data.copy(vdrop = median / 1000f,
                    display = String.format(Locale.US, "%.3f V", median / 1000f))
            }

            ProbeMode.OHM -> {
                if (data.mode == "OPEN" || data.mode == "SHORT"
                    || data.display == "OL" || data.display == "0\u03A9") {
                    ohmBuffer.clear()
                    return data
                }
                addToBuffer(ohmBuffer, data.ohm)
                if (ohmBuffer.size < MEDIAN_SIZE) return data
                val median = medianOf(ohmBuffer)

                // Dead-band filter — cegah digit bergetar saat floating
                val deadband = when {
                    median >= 1_000_000f -> 5000f   // ±0.005MΩ
                    median >= 1_000f     -> 500f    // ±0.5KΩ
                    else                 -> 1.0f    // ±1.0Ω
                }
                if (lastStableOhm != 0f && kotlin.math.abs(median - lastStableOhm) < deadband) {
                    // Perubahan di bawah threshold — pakai nilai lama
                    val display = when {
                        lastStableOhm >= 1_000_000f ->
                            String.format(Locale.US, "%.2fM\u03A9", lastStableOhm / 1_000_000f)
                        lastStableOhm >= 1_000f ->
                            String.format(Locale.US, "%.3fK\u03A9", lastStableOhm / 1_000f)
                        else ->
                            String.format(Locale.US, "%.1f\u03A9", lastStableOhm)
                    }
                    return data.copy(ohm = lastStableOhm, display = display)
                }

                // Perubahan cukup signifikan — update nilai stabil
                lastStableOhm = median
                val display = when {
                    median >= 1_000_000f ->
                        String.format(Locale.US, "%.2fM\u03A9", median / 1_000_000f)
                    median >= 1_000f ->
                        String.format(Locale.US, "%.3fK\u03A9", median / 1_000f)
                    else ->
                        String.format(Locale.US, "%.1f\u03A9", median)
                }
                data.copy(ohm = median, display = display)
            }
        }
    }

    // ================================================================
    // HISTORY & CONTINUITY
    // ================================================================

    private fun addToHistory(data: ProbeData) {
        val mode = activeMode.value ?: return

        // Cek apakah nilai ini GND
        val isGnd = when (mode) {
            ProbeMode.VOLT  -> data.volt == 0f  // hanya 0.00V persis = GND
            ProbeMode.DIODE -> data.vdrop < 0.05f
            ProbeMode.OHM   -> (data.ohm == 0f || data.display == "0Ω" || data.display == "0.0Ω") && data.display != "OL"
        }

        // Validasi: OL/OPEN tidak masuk, VOLT 0f (noise paksa filter) tidak masuk
        val isValid = when (mode) {
            ProbeMode.VOLT  -> data.volt > 0f  // 0f = noise dipaksa filter, bukan ukuran nyata
            ProbeMode.DIODE -> data.display != "OL" && data.display != "OPEN"
            ProbeMode.OHM   -> data.display != "OL" && data.display != "OPEN"
        }
        if (!isValid) { resetStable(); return }

        // GND hanya boleh masuk setelah ada pengukuran valid non-GND dulu
        // Cegah GND masuk otomatis saat tab dibuka / resync
        if (isGnd && !hasStartedMeasuring) { return }

        // Nilai non-GND pertama → tandai user sudah mulai ngukur
        if (!isGnd) { hasStartedMeasuring = true }

        // Display yang masuk riwayat
        val displayForHistory = if (isGnd) "GND" else data.display

        val now = System.currentTimeMillis()
        if (displayForHistory != stableDisplay) {
            stableDisplay   = displayForHistory
            stableStartTime = now
            return
        }
        if (now - stableStartTime < STABLE_DURATION_MS) return

        val targetLd = if (mode == ProbeMode.VOLT) historyAktif else historyPassif
        val currentList = targetLd.value?.toMutableList() ?: mutableListOf()
        val newItem = ProbeHistoryItem(
            id = System.currentTimeMillis(),
            mode = mode,
            display = displayForHistory,
            label = if (isGnd) "GND" else "",
            isPending = true
        )
        currentList.add(0, newItem)
        targetLd.value = if (currentList.size > 20) currentList.take(20) else currentList
        connectionManager?.sendCommand("BUZZ_PROBE_SAVED")

        // Task 26 — emit TTS event (hanya VOLT & DIODE, bukan OHM)
        if (mode == ProbeMode.VOLT || mode == ProbeMode.DIODE) {
            ttsEvent.value = Pair(displayForHistory, mode)
        }

        stableDisplay   = displayForHistory   // block nilai sama masuk lagi
        stableStartTime = Long.MAX_VALUE       // freeze — tidak akan masuk lagi sampai nilai berubah
    }

    private fun checkContinuity(data: ProbeData) {
        val isShort = data.display == "0mV"
                || data.display == "0.000 V"
                || data.display == "0\u03A9"
        if (isShort && data.display != lastShortDisplay) {
            connectionManager?.sendCommand("BUZZ_CONTINUITY")
            lastShortDisplay = data.display
        } else if (!isShort) {
            lastShortDisplay = ""
        }
    }
}