package com.rphone.v3.ui.uart

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rphone.v3.connection.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

// ─── Model hasil parsing ─────────────────────────────────────────────────────

enum class ParsedItemStatus { NORMAL, OK, WARNING, ERROR }

data class ParsedItem(
    val id: String,
    val label: String,
    val value: String,
    val status: ParsedItemStatus = ParsedItemStatus.NORMAL
)

// Stub kompatibilitas untuk UartAiAnalyzer — agar tidak perlu ubah AI layer
data class TechnicianInfo(
    val chipset: String = "—",
    val vendor: String = "UNKNOWN",
    val powerRails: List<String> = emptyList(),
    val bootStage: String = "—",
    val errors: List<String> = emptyList(),
    val thermal: String = "—",
    val modem: String = "—",
    val storage: String = "—",
    val memory: String = "—",
    val device: String = "—",
    val customMatches: Map<String, String> = emptyMap()
)

// ─── ViewModel ───────────────────────────────────────────────────────────────

class UartViewModel : ViewModel() {

    enum class StreamState { IDLE, STREAMING, ANALISA }

    private val _streamState = MutableStateFlow(StreamState.IDLE)
    val streamState: StateFlow<StreamState> = _streamState

    private val _rawLogs = MutableLiveData<List<String>>(emptyList())
    val rawLogs: LiveData<List<String>> = _rawLogs

    private val _parsedItems = MutableLiveData<List<ParsedItem>>(emptyList())
    val parsedItems: LiveData<List<ParsedItem>> = _parsedItems

    private val _baudRate = MutableLiveData(115200)
    val baudRate: LiveData<Int> = _baudRate

    private var selectedBaudLabel: String? = null

    // Buffer — thread-safe
    private val logBuffer = ArrayDeque<String>()
    private val bufferLock = Any()

    private var rawLogDebounceJob: Job? = null
    private var observeJob: Job? = null
    private var idleJob: Job? = null
    private var parseJob: Job? = null
    private var adaData = false

    var detectedVendor = "UNKNOWN"
        private set

    // ═══════════════════════════════════════════════════════════════════
    // OBSERVE & BUFFER
    // ═══════════════════════════════════════════════════════════════════

    fun startObserving(cm: ConnectionManager) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            cm.dataFlow.collect { raw ->
                try {
                    val obj = JSONObject(raw)
                    if (obj.has("uart")) {
                        val line = obj.optString("uart", "").trim()
                        if (line.isNotBlank()) processLine(line)
                        return@collect
                    }
                    if (obj.has("uart2_baud")) {
                        val baud = obj.optInt("uart2_baud", 115200)
                        if (_baudRate.value != baud) _baudRate.postValue(baud)
                        return@collect
                    }
                } catch (e: Exception) {
                    val trimmed = raw.trim()
                    if (trimmed.isNotBlank()) {
                        // Buang log internal ESP32 yang spesifik saja
                        val isEsp32 = trimmed.matches(
                            Regex("^\\[(WIFI|BT|SYS|UART|HTTP|OTA|NVS|MEM|HEAP|TASK|ESP|I2C|SPI)\\].*",
                                RegexOption.IGNORE_CASE)
                        )
                        if (!isEsp32) processLine(trimmed)
                    }
                }
            }
        }
    }

    fun stopObserving() {
        observeJob?.cancel()
        idleJob?.cancel()
    }

    // ─── Auto-clean encoding MediaTek ────────────────────────
    private fun bersihkanLine(line: String): String {
        val total = line.length
        if (total == 0) return line
        val printable = line.count { it.code in 32..126 || it == '\t' }
        val rasio = printable.toFloat() / total
        // Jika >60% karakter aneh → buang baris ini
        if (rasio < 0.4f) return ""
        // Jika sebagian aneh → filter karakter non-printable saja
        return if (rasio < 0.85f) {
            line.filter { it.code in 32..126 || it == '\t' }
        } else {
            line
        }
    }

    private fun processLine(line: String) {
        val bersih = bersihkanLine(line)
        if (bersih.isBlank()) return
        adaData = true
        synchronized(bufferLock) {
            logBuffer.add(bersih)
            if (logBuffer.size > 1500) logBuffer.removeFirst()
        }

        rawLogDebounceJob?.cancel()
        rawLogDebounceJob = viewModelScope.launch {
            delay(300L)
            val snap = synchronized(bufferLock) { logBuffer.toList() }
            _rawLogs.postValue(snap)
        }

        if (_streamState.value == StreamState.IDLE)
            _streamState.value = StreamState.STREAMING

        // Idle 5 detik → trigger parse, setelah selesai state jadi ANALISA
        // sehingga tombol "Analisa AI" muncul untuk ditekan user
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(5000L)
            if (_streamState.value == StreamState.STREAMING && adaData) {
                triggerFullParse()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FULL PARSE — dipanggil setelah stream idle / stop manual
    // ═══════════════════════════════════════════════════════════════════

    private fun triggerFullParse() {
        parseJob?.cancel()
        parseJob = viewModelScope.launch(Dispatchers.IO) {
            val snapshot = synchronized(bufferLock) { logBuffer.toList() }
            if (snapshot.isEmpty()) return@launch
            val vendor = detectVendor(snapshot).also {
                if (it != "UNKNOWN") detectedVendor = it
            }
            val items = buildParsedItems(snapshot, vendor)
            _parsedItems.postValue(items)
            // Setelah parse selesai, set ANALISA agar tombol "Analisa AI" muncul
            // User harus tekan tombol untuk menjalankan analisa AI
            _streamState.value = StreamState.ANALISA
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DETECT VENDOR
    // ═══════════════════════════════════════════════════════════════════

    private fun detectVendor(snapshot: List<String>): String {
        for (line in snapshot) {
            val u = line.uppercase()
            when {
                u.contains("IMAGE_VARIANT_STRING") ||
                u.contains("PM: PM 0=") ||
                u.contains("S - BOOT INTERFACE:") ||
                u.contains("D - PBL_APPS_INIT_TIMESTAMP") ||
                u.contains("B - SBL1_ENTRY") -> return "QCOM"

                u.contains("PROCESSOR : MEDIATEK") ||
                u.contains("[PMIC]") ||
                u.contains("LATCH VCORE") ||
                u.contains("[MT6") ||
                u.contains("[ATF]") ||
                u.contains("PRELOADER") -> return "MTK"
            }
        }
        return "UNKNOWN"
    }

    // ═══════════════════════════════════════════════════════════════════
    // BUILD PARSED ITEMS — satu fungsi, output List<ParsedItem> terurut
    // ═══════════════════════════════════════════════════════════════════

    private fun buildParsedItems(snapshot: List<String>, vendor: String): List<ParsedItem> {
        val result = mutableListOf<ParsedItem>()

        fun add(id: String, label: String, value: String,
                status: ParsedItemStatus = ParsedItemStatus.NORMAL) {
            if (value.isNotBlank()) result.add(ParsedItem(id, label, value, status))
        }

        // ── CHIPSET ──────────────────────────────────────────────────────
        parseChipset(snapshot, vendor)?.let { add("chipset", "Chipset", it) }

        // ── BOOT INFO ────────────────────────────────────────────────────
        parseBootReason(snapshot)?.let { (v, s) -> add("boot_reason", "Boot Reason", v, s) }
        parseAbnormalBoot(snapshot)?.let { (v, s) -> add("abnormal_boot", "Abnormal Boot", v, s) }
        parseBootStage(snapshot)?.let { add("boot_stage", "Boot Stage", it) }
        parseBootSlot(snapshot)?.let { add("boot_slot", "Boot Slot", it) }
        parseBootSuccess(snapshot)?.let { (v, s) -> add("boot_success", "Boot Success", v, s) }
        parseRguReset(snapshot)?.let { (v, s) -> add("rgu_reset", "RGU Reset", v, s) }

        // ── POWER / BATTERY ──────────────────────────────────────────────
        parseBattery(snapshot, vendor)?.let { (v, s) -> add("battery", "Battery", v, s) }
        parseVbat(snapshot, vendor)?.let { (v, s) -> add("vbat", "VBAT", v, s) }
        parseUsbCable(snapshot)?.let { add("usb_cable", "USB Cable", it) }
        parseCharger(snapshot)?.let { (v, s) -> add("charger", "Charger", v, s) }
        parseRtc(snapshot)?.let { (v, s) -> add("rtc", "RTC", v, s) }

        // ── VOLTAGE RAILS (MTK) ──────────────────────────────────────────
        if (vendor == "MTK" || vendor == "UNKNOWN") {
            parseVoltageRailsMtk(snapshot).forEach { (id, label, mv, status) ->
                add(id, label, "${mv}mV", status)
            }
        }

        // ── VOLTAGE RAILS (QCOM) ─────────────────────────────────────────
        if (vendor == "QCOM" || vendor == "UNKNOWN") {
            parseVbattQcom(snapshot)?.let { (v, s) -> add("vbatt_qc", "Vbatt", v, s) }
            parsePowerOnReason(snapshot)?.let { add("pon_reason", "Power ON Reason", it) }
        }

        // ── PMIC ─────────────────────────────────────────────────────────
        parsePmic(snapshot, vendor).forEach { (id, label, v, s) -> add(id, label, v, s) }

        // ── STORAGE ──────────────────────────────────────────────────────
        parseStorage(snapshot, vendor)?.let { add("storage", "Storage", it) }

        // ── MEMORY / RAM ─────────────────────────────────────────────────
        parseMemory(snapshot, vendor)?.let { (v, s) -> add("memory", "Memory", v, s) }

        // ── MODEM ────────────────────────────────────────────────────────
        parseModem(snapshot, vendor)?.let { (v, s) -> add("modem", "Modem", v, s) }

        // ── DISPLAY ──────────────────────────────────────────────────────
        parseDisplay(snapshot, vendor)?.let { add("display", "Display", it) }

        // ── SECURITY ─────────────────────────────────────────────────────
        parseSecurity(snapshot, vendor)?.let { (v, s) -> add("security", "Security Boot", v, s) }

        // ── THERMAL ──────────────────────────────────────────────────────
        parseThermal(snapshot)?.let { (v, s) -> add("thermal", "Thermal", v, s) }

        // ── ERRORS & WARNINGS (selalu paling bawah) ──────────────────────
        parseErrors(snapshot).forEach { (id, label, v) ->
            add(id, label, v, ParsedItemStatus.ERROR)
        }
        parseWarnings(snapshot).forEach { (id, label, v) ->
            add(id, label, v, ParsedItemStatus.WARNING)
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════════
    // PARSER FUNCTIONS
    // Konvensi: return Pair(value, status) atau String atau null
    // ═══════════════════════════════════════════════════════════════════

    // ── Helper: extract angka setelah keyword, dengan validasi range ──

    private fun extractMv(line: String, keyword: String,
                          minVal: Long = 0, maxVal: Long = 5000): Long? {
        val idx = line.indexOf(keyword, ignoreCase = true)
        if (idx < 0) return null
        val after = line.substring(idx + keyword.length)
        val match = Regex("[=:\\s]*(\\d{3,7})\\s*(uV|mV)?", RegexOption.IGNORE_CASE)
            .find(after) ?: return null
        val raw = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val mv = if (unit == "uv" || raw > 10000) raw / 1000 else raw
        return if (mv in minVal..maxVal) mv else null
    }

    private fun extractAfter(line: String, keyword: String, maxLen: Int = 30): String? {
        val idx = line.indexOf(keyword, ignoreCase = true)
        if (idx < 0) return null
        val after = line.substring(idx + keyword.length)
            .trimStart('=', ':', ' ')
            .trim()
        return after.take(maxLen).ifBlank { null }
    }

    // ── CHIPSET ──────────────────────────────────────────────────────────

    private fun parseChipset(snapshot: List<String>, vendor: String): String? {
        for (line in snapshot) {
            // MTK — tag eksplisit [MT6xxx]
            Regex("\\[MT(\\d{4}[A-Z0-9]*)\\]", RegexOption.IGNORE_CASE).find(line)
                ?.let { return "MT${it.groupValues[1]}" }
            // MTK — Processor : MediaTek MT...
            Regex("Processor\\s*:\\s*(.{3,40})", RegexOption.IGNORE_CASE).find(line)
                ?.let { return it.groupValues[1].trim().take(30) }
            // MTK — bare MT\d{4}
            Regex("\\bMT(\\d{4}[A-Z0-9]*)\\b").find(line)
                ?.let { return "MT${it.groupValues[1]}" }
            // QCOM — IMAGE_VARIANT_STRING
            Regex("IMAGE_VARIANT_STRING=([A-Z0-9]+)", RegexOption.IGNORE_CASE).find(line)
                ?.let { return it.groupValues[1] }
            // QCOM — SM/SDM/MSM
            Regex("\\b(SM|SDM|MSM)(\\d{4}[A-Z0-9]*)\\b", RegexOption.IGNORE_CASE).find(line)
                ?.let { return "${it.groupValues[1].uppercase()}${it.groupValues[2]}" }
        }
        return null
    }

    // ── BOOT INFO ────────────────────────────────────────────────────────

    private fun parseBootReason(snapshot: List<String>): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            Regex("BOOT_REASON:\\s*(\\d)", RegexOption.IGNORE_CASE).find(line)?.let {
                val code = it.groupValues[1]
                val (reason, status) = when (code) {
                    "0" -> Pair("0 - Normal", ParsedItemStatus.OK)
                    "1" -> Pair("1 - WDT Reset", ParsedItemStatus.WARNING)
                    "2" -> Pair("2 - SW Reset", ParsedItemStatus.NORMAL)
                    "3" -> Pair("3 - HW Reset", ParsedItemStatus.NORMAL)
                    else -> Pair("code=$code", ParsedItemStatus.NORMAL)
                }
                return Pair(reason, status)
            }
        }
        return null
    }

    private fun parseAbnormalBoot(snapshot: List<String>): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            Regex("IS_ABNORMAL_BOOT:\\s*(\\d)", RegexOption.IGNORE_CASE).find(line)?.let {
                val v = it.groupValues[1]
                return if (v == "1") Pair("1 - ABNORMAL", ParsedItemStatus.ERROR)
                else Pair("0 - Normal", ParsedItemStatus.OK)
            }
        }
        return null
    }

    private fun parseBootStage(snapshot: List<String>): String? {
        val stages = listOf(
            "Boot Complete" to listOf("BOOT COMPLETED", "BOOT_COMPLETED", "BOOT_SUCCESS IS 1"),
            "System Server" to listOf("SYSTEM SERVER"),
            "Zygote"        to listOf("ZYGOTE"),
            "Android Init"  to listOf("INIT:", "STARTING SERVICE"),
            "Linux Kernel"  to listOf("LINUX VERSION", "KERNEL:"),
            "LK"            to listOf("[LK]", "LITTLE KERNEL"),
            "Preloader"     to listOf("PRELOADER"),
            "ARM TF/BL2"    to listOf("BL1", "BL2", "ARM_TF"),
            "ABL"           to listOf("ANDROID BOOTLOADER", "ABL"),
            "SBL1"          to listOf("SBL1, END", "SBL1, START"),
            "PBL"           to listOf("PBL, END", "PBL, START"),
        )
        var best = -1
        var bestStage = ""
        for (line in snapshot) {
            val u = line.uppercase()
            stages.forEachIndexed { idx, (label, keywords) ->
                if (idx > best && keywords.any { u.contains(it) }) {
                    best = idx; bestStage = label
                }
            }
        }
        return bestStage.ifBlank { null }
    }

    private fun parseBootSlot(snapshot: List<String>): String? {
        for (line in snapshot) {
            Regex("\\[AB\\].*?Current boot:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(line)
                ?.let { return it.groupValues[1] }
            Regex("\\[AB\\].*?ab_suffix:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(line)
                ?.let { return "suffix=${it.groupValues[1]}" }
        }
        return null
    }

    private fun parseBootSuccess(snapshot: List<String>): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            Regex("boot_success is\\s*(\\d)", RegexOption.IGNORE_CASE).find(line)?.let {
                val v = it.groupValues[1]
                return if (v == "1") Pair("1 - OK", ParsedItemStatus.OK)
                else Pair("0 - FAIL", ParsedItemStatus.ERROR)
            }
        }
        return null
    }

    private fun parseRguReset(snapshot: List<String>): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            val u = line.uppercase()
            if (u.contains("[RGU]") && u.contains("RST FROM")) {
                Regex("rst from:\\s*(.+)", RegexOption.IGNORE_CASE).find(line)?.let {
                    return Pair(it.groupValues[1].trim().take(25), ParsedItemStatus.WARNING)
                }
            }
        }
        return null
    }

    // ── POWER / BATTERY ──────────────────────────────────────────────────

    private fun parseBattery(snapshot: List<String>, vendor: String): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            val u = line.uppercase()
            if (u.contains("BAT IS EXIST") || u.contains("BATTERY EXIST"))
                return Pair("Exist", ParsedItemStatus.OK)
            if (u.contains("BATTERY GOOD"))
                return Pair("Good", ParsedItemStatus.OK)
        }
        return null
    }

    private fun parseVbat(snapshot: List<String>, vendor: String): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            // MTK: VBAT= atau VBAT :
            extractMv(line, "VBAT", minVal = 2500, maxVal = 4500)?.let { mv ->
                val status = when {
                    mv < 3000 -> ParsedItemStatus.ERROR
                    mv < 3400 -> ParsedItemStatus.WARNING
                    else      -> ParsedItemStatus.OK
                }
                return Pair("${mv}mV", status)
            }
            // VCHR_HV
            Regex("VCHR_HV[=:\\s]+(\\d{3,5})", RegexOption.IGNORE_CASE).find(line)?.let {
                val mv = it.groupValues[1].toLongOrNull() ?: return@let
                if (mv in 3000..5500)
                    return Pair("VCHR_HV: ${mv}mV", ParsedItemStatus.NORMAL)
            }
        }
        return null
    }

    private fun parseVbattQcom(snapshot: List<String>): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            Regex("PM:\\s*Vbatt:\\s*(\\d{3,5})", RegexOption.IGNORE_CASE).find(line)?.let {
                val mv = it.groupValues[1].toLongOrNull() ?: return@let
                val status = when {
                    mv < 3000  -> ParsedItemStatus.ERROR
                    mv < 3400  -> ParsedItemStatus.WARNING
                    mv <= 4400 -> ParsedItemStatus.OK
                    else       -> ParsedItemStatus.WARNING
                }
                return Pair("${mv}mV", status)
            }
            Regex("bq vbat_adc:\\s*(\\d{3,5})", RegexOption.IGNORE_CASE).find(line)?.let {
                val mv = it.groupValues[1].toLongOrNull() ?: return@let
                if (mv in 2500..4500) return Pair("BQ: ${mv}mV", ParsedItemStatus.NORMAL)
            }
        }
        return null
    }

    private fun parseUsbCable(snapshot: List<String>): String? {
        for (line in snapshot) {
            Regex("IsUsbCableIn[=:\\s]*(\\d)", RegexOption.IGNORE_CASE).find(line)
                ?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun parseCharger(snapshot: List<String>): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            val u = line.uppercase()
            if (u.contains("CHARGER MODULE ABSENT"))
                return Pair("Absent", ParsedItemStatus.WARNING)
            if (u.contains("NOT CHARGING"))
                return Pair("Not Charging", ParsedItemStatus.WARNING)
            if (u.contains("CHARGER_ENABLE_CHARGING ENABLE"))
                return Pair("Enabled", ParsedItemStatus.OK)
            Regex("chr_type[=:\\s]+(\\S+)", RegexOption.IGNORE_CASE).find(line)
                ?.let { return Pair(it.groupValues[1].trim().take(15), ParsedItemStatus.NORMAL) }
        }
        return null
    }

    private fun parseRtc(snapshot: List<String>): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            if (line.contains("2010/1/1"))
                return Pair("RESET - 2010/1/1", ParsedItemStatus.WARNING)
            Regex("RTC time[=:\\s]+(\\S+)", RegexOption.IGNORE_CASE).find(line)
                ?.let { return Pair(it.groupValues[1].take(20), ParsedItemStatus.NORMAL) }
        }
        return null
    }

    private fun parsePowerOnReason(snapshot: List<String>): String? {
        for (line in snapshot) {
            extractAfter(line, "PM: POWER ON by")?.let { return it }
            extractAfter(line, "PON REASON:PM0:")?.let { return it }
        }
        return null
    }

    // ── VOLTAGE RAILS MTK ────────────────────────────────────────────────
    // Hanya match pola "latch VCORE 850000" atau "VCORE= 850000"
    // Tidak pernah match angka random dari baris lain

    data class VoltageResult(val id: String, val label: String, val mv: Long,
                             val status: ParsedItemStatus)

    private fun parseVoltageRailsMtk(snapshot: List<String>): List<VoltageResult> {
        val rails = listOf(
            "vcore"       to "VCORE",
            "vproc"       to "VPROC",
            "vproc1"      to "VPROC1",
            "vproc2"      to "VPROC2",
            "vgpu"        to "VGPU",
            "vsram_proc"  to "VSRAM_PROC",
            "vsram_proc1" to "VSRAM_PROC1",
            "vsram_proc2" to "VSRAM_PROC2",
            "vsram_others"      to "VSRAM_OTHERS",
            "vsram_gpu"   to "VSRAM_GPU",
            "vmodem"      to "VMODEM",
            "vrf09"       to "VRF09",
            "vdram"       to "VDRAM",
            "vddq"        to "VDDQ",
            "vmddr"       to "VMDDR",
            "vio18"       to "VIO18",
        )

        // Pattern yang diterima:
        //   latch VCORE 850000
        //   VCORE= 850000
        //   VCORE : 850000
        //   [MT6315]latch VMODEM 800000
        val found = mutableMapOf<String, VoltageResult>()

        for (line in snapshot) {
            val u = line.uppercase()
            for ((id, name) in rails) {
                if (id in found) continue
                if (!u.contains(name)) continue

                // Hanya match jika ada pola latch/= sebelum angka
                val mv = Regex(
                    "(?:latch\\s+$name|$name\\s*[=:])\\s*(\\d{4,7})\\s*(uV|mV)?",
                    RegexOption.IGNORE_CASE
                ).find(line)?.let {
                    val raw = it.groupValues[1].toLongOrNull() ?: return@let null
                    val unit = it.groupValues[2].lowercase()
                    if (unit == "uv" || raw > 10000) raw / 1000 else raw
                } ?: continue

                if (mv !in 100..3300) continue   // di luar range fisik → skip

                val status = when {
                    mv < 500  -> ParsedItemStatus.WARNING
                    mv > 2500 -> ParsedItemStatus.WARNING
                    else      -> ParsedItemStatus.NORMAL
                }
                found[id] = VoltageResult(id, name, mv, status)
            }
        }
        return found.values.toList()
    }

    // ── PMIC ─────────────────────────────────────────────────────────────

    private data class PmicResult(val id: String, val label: String,
                                  val value: String, val status: ParsedItemStatus)

    private fun parsePmic(snapshot: List<String>, vendor: String): List<PmicResult> {
        val result = mutableListOf<PmicResult>()
        val found = mutableSetOf<String>()

        for (line in snapshot) {
            val u = line.uppercase()

            if ("pmic_mt6357" !in found && u.contains("[PMIC]MT6357 CHIP CODE")) {
                result.add(PmicResult("pmic_mt6357", "PMIC MT6357", "Detected", ParsedItemStatus.OK))
                found.add("pmic_mt6357")
            }
            if ("pmic_mt6359" !in found && u.contains("[PMIC] CHIP CODE")) {
                result.add(PmicResult("pmic_mt6359", "PMIC MT6359", "Detected", ParsedItemStatus.OK))
                found.add("pmic_mt6359")
            }
            if ("pmic_mt6315" !in found && u.contains("[MT6315]S3 RG_SLV_ID")) {
                result.add(PmicResult("pmic_mt6315", "PMIC MT6315", "Detected", ParsedItemStatus.OK))
                found.add("pmic_mt6315")
            }
            if ("pmic_ponsts" !in found) {
                Regex("\\[PMIC\\]PONSTS[=:\\s]+(0x[0-9A-Fa-f]+|\\d+)").find(line)?.let {
                    result.add(PmicResult("pmic_ponsts", "PMIC PONSTS", it.groupValues[1], ParsedItemStatus.NORMAL))
                    found.add("pmic_ponsts")
                }
            }
            if ("pmic_poffsts" !in found) {
                Regex("\\[PMIC\\]POFFSTS[=:\\s]+(0x[0-9A-Fa-f]+|\\d+)").find(line)?.let {
                    result.add(PmicResult("pmic_poffsts", "PMIC POFFSTS", it.groupValues[1], ParsedItemStatus.NORMAL))
                    found.add("pmic_poffsts")
                }
            }
            if ("pm0" !in found && u.contains("PM: PM 0=")) {
                extractAfter(line, "PM: PM 0=")?.let {
                    result.add(PmicResult("pm0", "PMIC PM0", it.take(15), ParsedItemStatus.OK))
                    found.add("pm0")
                }
            }
            if ("pm1" !in found && u.contains("PM: PM 1=")) {
                extractAfter(line, "PM: PM 1=")?.let {
                    result.add(PmicResult("pm1", "PMIC PM1", it.take(15), ParsedItemStatus.OK))
                    found.add("pm1")
                }
            }
        }
        return result
    }

    // ── STORAGE ──────────────────────────────────────────────────────────

    private fun parseStorage(snapshot: List<String>, vendor: String): String? {
        for (line in snapshot) {
            val u = line.uppercase()
            // Boot Interface (QC)
            extractAfter(line, "Boot Interface:")?.let { return it.take(20) }
            // UFS Inquiry (QC)
            extractAfter(line, "UFS INQUIRY ID:")?.let { return "UFS: ${it.take(20)}" }
            // UFS vendor (MTK)
            Regex("\\[UFS\\]\\s*vendor id:\\s*(0x[0-9A-Fa-f]+)", RegexOption.IGNORE_CASE).find(line)?.let {
                val id = it.groupValues[1].lowercase()
                val brand = when {
                    "1ad" in id -> "SK Hynix"
                    "198" in id -> "Micron"
                    "1ce" in id -> "Samsung"
                    "12c" in id -> "Kioxia"
                    "145" in id -> "SanDisk"
                    else        -> id
                }
                return "UFS: $brand"
            }
            // UFS speed
            Regex("\\[UFS\\].*?HS-G(\\d+)", RegexOption.IGNORE_CASE).find(line)
                ?.let { return "UFS HS-G${it.groupValues[1]}" }
            // eMMC size
            Regex("(?:SD0\\]\\s*Size|eMMC).*?(\\d{3,6})\\s*MB", RegexOption.IGNORE_CASE).find(line)?.let {
                val mb = it.groupValues[1].toIntOrNull() ?: return@let
                return "eMMC: ${mb / 1024}GB"
            }
        }
        return null
    }

    // ── MEMORY ───────────────────────────────────────────────────────────

    private fun parseMemory(snapshot: List<String>, vendor: String): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            val u = line.uppercase()
            // DRAM rank0 size hex (MTK)
            Regex("DRAM rank0 size:\\s*(0x[0-9A-Fa-f]+)", RegexOption.IGNORE_CASE).find(line)?.let {
                val bytes = it.groupValues[1].toLong(16)
                val gb = bytes / (1024L * 1024 * 1024)
                return Pair("${gb}GB", ParsedItemStatus.OK)
            }
            // Rank 0 size MB (QC)
            Regex("Rank 0 size\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(line)?.let {
                val mb = it.groupValues[1].toIntOrNull() ?: return@let
                return Pair("${mb}MB", ParsedItemStatus.OK)
            }
            if (u.contains("MEM TEST PASS"))
                return Pair("RAM Test: PASS", ParsedItemStatus.OK)
            if (u.contains("LPDDR5"))  return Pair("LPDDR5", ParsedItemStatus.NORMAL)
            if (u.contains("LPDDR4X")) return Pair("LPDDR4X", ParsedItemStatus.NORMAL)
            if (u.contains("LPDDR4"))  return Pair("LPDDR4", ParsedItemStatus.NORMAL)
        }
        return null
    }

    // ── MODEM ─────────────────────────────────────────────────────────────

    private fun parseModem(snapshot: List<String>, vendor: String): Pair<String, ParsedItemStatus>? {
        var romPass = false; var dspPass = false
        for (line in snapshot) {
            val u = line.uppercase()
            if (u.contains("MD1ROM AUTH PASS")) romPass = true
            if (u.contains("MD1DSP AUTH PASS")) dspPass = true
            if (u.contains("CCCI")) return Pair("CCCI Active", ParsedItemStatus.OK)
            if (u.contains("CP READY")) return Pair("CP Ready", ParsedItemStatus.OK)
        }
        if (romPass && dspPass) return Pair("ROM+DSP Auth OK", ParsedItemStatus.OK)
        if (romPass) return Pair("ROM Auth OK", ParsedItemStatus.OK)
        return null
    }

    // ── DISPLAY ──────────────────────────────────────────────────────────

    private fun parseDisplay(snapshot: List<String>, vendor: String): String? {
        for (line in snapshot) {
            val u = line.uppercase()
            if (u.contains("PRIMARY_DISPLAY_INIT OK")) return "Init OK"
            extractAfter(line, "Panel_name:")?.let { return it.take(25) }
            Regex("LCM\\s+\\[(.{3,30})\\]", RegexOption.IGNORE_CASE).find(line)
                ?.let { return it.groupValues[1].trim().take(25) }
        }
        return null
    }

    // ── SECURITY ─────────────────────────────────────────────────────────

    private fun parseSecurity(snapshot: List<String>, vendor: String): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            Regex("Secure Boot:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(line)?.let {
                val v = it.groupValues[1]
                val status = if (v.uppercase() == "ENABLED") ParsedItemStatus.OK
                             else ParsedItemStatus.NORMAL
                return Pair(v, status)
            }
            if (line.uppercase().contains("QSEE EXECUTION, START"))
                return Pair("QSEE Active", ParsedItemStatus.OK)
        }
        return null
    }

    // ── THERMAL ──────────────────────────────────────────────────────────

    private fun parseThermal(snapshot: List<String>): Pair<String, ParsedItemStatus>? {
        for (line in snapshot) {
            Regex("Tbatt:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(line)?.let {
                val raw = it.groupValues[1].toIntOrNull() ?: return@let
                val temp = if (raw > 100) raw / 10 else raw
                val status = if (temp > 45) ParsedItemStatus.WARNING else ParsedItemStatus.NORMAL
                return Pair("${temp}°C", status)
            }
            if (line.uppercase().contains("THERMAL") || line.uppercase().contains("TEMP")) {
                Regex("(\\d{2,3})\\s*°?[Cc]").find(line)?.let {
                    val t = it.groupValues[1].toIntOrNull() ?: return@let
                    val status = if (t > 45) ParsedItemStatus.WARNING else ParsedItemStatus.NORMAL
                    return Pair("${t}°C", status)
                }
            }
        }
        return null
    }

    // ── ERRORS ───────────────────────────────────────────────────────────

    private data class ErrorItem(val id: String, val label: String, val value: String)

    private fun parseErrors(snapshot: List<String>): List<ErrorItem> {
        val errors = mutableListOf<ErrorItem>()
        val found = mutableSetOf<String>()

        fun addOnce(id: String, label: String, value: String) {
            if (id !in found) { errors.add(ErrorItem(id, label, value)); found.add(id) }
        }

        for (line in snapshot) {
            val u = line.uppercase()
            if (u.contains("KERNEL PANIC") || u.contains("PANIC:"))
                addOnce("kernel_panic", "Kernel Panic", line.take(40))
            if (u.contains("DRAM_FATAL_ERR_FLAG"))
                addOnce("dram_fatal", "DRAM Fatal Error", "FLAG SET")
            if (u.contains("FATAL DRAM EXCEPTION"))
                addOnce("dram_exception", "DRAM Exception", "Found")
            if (u.contains("DRAM NOT FOUND"))
                addOnce("dram_not_found", "DRAM Not Found", "")
            if (u.contains("MEM TEST FAIL") || u.contains("COMPLEX R/W MEM TEST FAIL"))
                addOnce("mem_test_fail", "RAM Test", "FAIL")
            if (u.contains("VDRAM MISSING"))
                addOnce("vdram_missing", "VDRAM", "MISSING")
            if (u.contains("UFS") && (u.contains(" ERR") || u.contains(" FAIL")))
                addOnce("ufs_err", "UFS Error", line.take(30))
            if (u.contains("EMMC") && (u.contains(" ERR") || u.contains(" FAIL")))
                addOnce("emmc_err", "eMMC Error", line.take(30))
            if (u.contains("DSI") && u.contains("ERROR"))
                addOnce("dsi_error", "DSI Error", "Found")
            if (u.contains("POLLING SLEEPOUT_DONE ERROR"))
                addOnce("lcd_fail", "LCD Init", "FAIL")
            if (u.contains("BUG:") || u.contains("OOPS:"))
                addOnce("kernel_bug", "Kernel Bug/Oops", "Found")
            if (u.contains("SIGSEGV") || u.contains("SEGFAULT"))
                addOnce("segfault", "Segfault", "Found")
        }
        return errors
    }

    // ── WARNINGS ─────────────────────────────────────────────────────────

    private fun parseWarnings(snapshot: List<String>): List<ErrorItem> {
        val warnings = mutableListOf<ErrorItem>()
        val found = mutableSetOf<String>()

        fun addOnce(id: String, label: String, value: String) {
            if (id !in found) { warnings.add(ErrorItem(id, label, value)); found.add(id) }
        }

        for (line in snapshot) {
            val u = line.uppercase()
            if (u.contains("SPMI READ COMMAND FAILURE"))
                addOnce("spmi_fail", "SPMI Failure", "Detected")
            if (u.contains("[WARNING] SMALLER TX WIN") || u.contains("SMALLER TX WIN"))
                addOnce("tx_window", "TX Window", "Smaller Win")
            if (u.contains("WDT") && u.contains("RESET"))
                addOnce("wdt_reset", "WDT Reset", "Detected")
            if (u.contains("SN DECRYPTITON FAILED"))
                addOnce("sn_decrypt", "SN Decrypt", "FAIL")
            if (u.contains("SCM CALL") && u.contains("FAILED"))
                addOnce("scm_fail", "SCM Call", "FAIL")
            if (u.contains("LED_WR FAILED"))
                addOnce("led_fail", "LED Write", "FAIL")
        }
        return warnings
    }

    // ─── CUSTOM RULES (runtime, dari SharedPreferences) ──────────

    fun applyCustomRules(
        snapshot: List<String>,
        prefs: android.content.SharedPreferences
    ): List<ParsedItem> {
        val rules = CustomRuleStore.loadRules(prefs)
        if (rules.isEmpty()) return emptyList()

        val results = mutableListOf<ParsedItem>()
        val found   = mutableSetOf<String>()   // deduplicate per rule id

        for (line in snapshot) {
            for (rule in rules) {
                if (rule.id in found) continue
                try {
                    val match = Regex(rule.pattern, RegexOption.IGNORE_CASE).find(line)
                        ?: continue
                    val value = if (rule.group == 0) {
                        match.value.take(40)
                    } else {
                        match.groupValues.getOrElse(rule.group) { match.value }.take(40)
                    }
                    if (value.isBlank()) continue
                    results.add(ParsedItem(
                        id     = "custom_${rule.id}",
                        label  = rule.label,
                        value  = value,
                        status = rule.toStatus()
                    ))
                    found.add(rule.id)
                } catch (e: Exception) {
                    // Pattern tidak valid → skip
                }
            }
        }
        return results
    }

    fun mergeCustomRules(prefs: android.content.SharedPreferences) {
        val snapshot = synchronized(bufferLock) { logBuffer.toList() }
        if (snapshot.isEmpty()) return
        val customItems = applyCustomRules(snapshot, prefs)
        if (customItems.isEmpty()) return
        val current = _parsedItems.value ?: emptyList()
        // Hapus custom items lama, append yang baru
        val filtered = current.filter { !it.id.startsWith("custom_") }
        val newList = filtered + customItems
        if (newList != current) {
            _parsedItems.postValue(newList)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    fun setBaudRate(baud: Int, label: String? = null,
                   prefs: android.content.SharedPreferences? = null) {
        _baudRate.value = baud
        selectedBaudLabel = label
        prefs?.edit()?.apply {
            putInt("uart_baud_rate_val", baud)
            putString("uart_baud_label", label)
            apply()
        }
    }

    fun loadBaudRate(prefs: android.content.SharedPreferences) {
        _baudRate.value = prefs.getInt("uart_baud_rate_val", 115200)
        selectedBaudLabel = prefs.getString("uart_baud_label", null)
    }

    fun getBaudRateDisplayLabel(): String {
        val label = selectedBaudLabel
        return if (label != null) "$label ▾" else "${_baudRate.value ?: 115200} baud ▾"
    }

    fun getCurrentBaudRate(): Int = _baudRate.value ?: 115200

    fun getVendorFromBaudLabel(): String {
        val label = selectedBaudLabel?.uppercase() ?: return "ALL"
        return when {
            label.contains("QUALCOMM") || label.contains("QCOM") -> "QCOM"
            label.contains("MEDIATEK") || label.contains("MTK")  -> "MTK"
            else -> "ALL"
        }
    }

    fun startStreaming() {
        adaData = false
        _streamState.value = StreamState.STREAMING
        idleJob?.cancel()
        parseJob?.cancel()
    }

    fun stopStreaming() {
        idleJob?.cancel()
        parseJob?.cancel()
        if (adaData) {
            // Trigger parse, setelah selesai state akan jadi ANALISA
            // sehingga tombol "Analisa AI" muncul dan user harus menekannya
            triggerFullParse()
        } else {
            _streamState.value = StreamState.IDLE
        }
    }

    fun clearLogs() {
        adaData = false
        rawLogDebounceJob?.cancel()
        parseJob?.cancel()
        idleJob?.cancel()
        synchronized(bufferLock) { logBuffer.clear() }
        _rawLogs.postValue(emptyList())
        _parsedItems.postValue(emptyList())
        detectedVendor = "UNKNOWN"
        _streamState.value = StreamState.IDLE
    }

    fun resetToIdle() {
        _streamState.value = StreamState.IDLE
        idleJob?.cancel()
    }

    fun hasLog(): Boolean = synchronized(bufferLock) { logBuffer.isNotEmpty() }

    fun getFullLogText(): String = synchronized(bufferLock) { logBuffer.joinToString("\n") }

    fun getRawLogSample(maxLines: Int = 30): String =
        synchronized(bufferLock) { logBuffer.takeLast(maxLines).joinToString("\n") }

    fun getCurrentTechInfo(): TechnicianInfo {
        val items = _parsedItems.value ?: emptyList()
        val map = items.associate { it.id to it.value }
        return TechnicianInfo(
            chipset    = map["chipset"] ?: "—",
            vendor     = detectedVendor,
            powerRails = items.filter { it.id.startsWith("v") && it.value.endsWith("mV") }
                              .map { "${it.label}: ${it.value}" },
            bootStage  = map["boot_stage"] ?: "—",
            errors     = items.filter { it.status == ParsedItemStatus.ERROR }.map { it.value },
            thermal    = map["thermal"] ?: "—",
            modem      = map["modem"] ?: "—",
            storage    = map["storage"] ?: "—",
            memory     = map["memory"] ?: "—",
            device     = map["chipset"] ?: "—"
        )
    }

    fun getCurrentParsedFormatted(): String {
        return (_parsedItems.value ?: emptyList()).joinToString("\n") {
            "${it.label}: ${it.value}"
        }
    }

    override fun onCleared() {
        super.onCleared()
        observeJob?.cancel()
        idleJob?.cancel()
        rawLogDebounceJob?.cancel()
        parseJob?.cancel()
    }
}
