package com.rphone.v3.ui.psu

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rphone.v3.connection.ConnectionManager
import com.rphone.v3.model.BtStatus
import com.rphone.v3.model.PsuData
import com.rphone.v3.util.JsonParser
import com.rphone.v3.waveid.database.WaveIDDatabase
import com.rphone.v3.waveid.engine.DtwMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class PreAnalisaResult(
    val status: String,       // "NORMAL", "SHORT_HALUS", "SHORT_HARD"
    val avgMa: Float,
    val maxMa: Float,
    val minMa: Float,
    val pola: String          // "stabil", "naik", "turun"
)

class PsuViewModel : ViewModel() {

    private val _psuData = MutableLiveData(PsuData())
    val psuData: LiveData<PsuData> = _psuData

    private val _btStatus = MutableLiveData(BtStatus.DISCONNECTED)
    val btStatus: LiveData<BtStatus> = _btStatus

    private val _power = MutableLiveData(0f)
    val power: LiveData<Float> = _power

    private val _capacity = MutableLiveData(0f)
    val capacity: LiveData<Float> = _capacity

    private val _ocpStatus = MutableLiveData("OFF")
    val ocpStatus: LiveData<String> = _ocpStatus

    private val _pwmEnabled = MutableLiveData(false)
    val pwmEnabled: LiveData<Boolean> = _pwmEnabled

    private val _pwmDur = MutableLiveData(2000)
    val pwmDur: LiveData<Int> = _pwmDur

    private val _ocpThreshold = MutableLiveData(3.0f)
    val ocpThreshold: LiveData<Float> = _ocpThreshold

    private val _ocpAutoReset = MutableLiveData<Unit>()
    val ocpAutoReset: LiveData<Unit> = _ocpAutoReset

    private val _hasilAutoMatch = MutableLiveData<List<DtwMatcher.HasilMatch>>(emptyList())
    val hasilAutoMatch: LiveData<List<DtwMatcher.HasilMatch>> = _hasilAutoMatch

    private val _preAnalisaResult = MutableLiveData<PreAnalisaResult?>(null)
    val preAnalisaResult: LiveData<PreAnalisaResult?> = _preAnalisaResult

    // Buffer arus live untuk DTW
    private val liveBuffer = mutableListOf<Float>()
    private var capacityAccum = 0f
    private var lastUpdateMs  = 0L

    // Snapshot waveform — diambil tepat saat recording berhenti,
    // sebelum dataJob mengotori buffer dengan data post-recording
    private var waveformSnapshot: List<Float> = emptyList()

    /** Panggil tepat sebelum stopAnalisa() untuk membekukan waveform hasil rekaman */
    fun snapshotWaveform() {
        waveformSnapshot = synchronized(liveBuffer) { liveBuffer.toList() }
    }

    /** Kembalikan waveform yang di-snapshot — bukan live buffer */
    fun getWaveformSnapshot(): List<Float> = waveformSnapshot

    // Job untuk cancel saat connectionManager berubah
    private var statusJob: Job? = null
    private var dataJob: Job? = null

    fun startObserving(connectionManager: ConnectionManager) {
        // Cancel coroutine lama sebelum start yang baru
        statusJob?.cancel()
        dataJob?.cancel()

        statusJob = viewModelScope.launch {
            connectionManager.status.collectLatest { status ->
                _btStatus.postValue(status)
            }
        }

        dataJob = viewModelScope.launch {
            connectionManager.dataFlow.collect { json ->
                val data = JsonParser.parsePsuData(json)
                if (data == null) {
                    val ocpEvent = JsonParser.parseOcpEvent(json)
                    if (ocpEvent != null) {
                        val (event, _) = ocpEvent
                        when (event) {
                            "trip"       -> _ocpStatus.postValue("TRIP")
                            "reset"      -> _ocpStatus.postValue("ON")
                            "auto_reset" -> {
                                _ocpStatus.postValue("ON")
                                _ocpAutoReset.postValue(Unit)
                            }
                            "on"  -> _ocpStatus.postValue("ON")
                            "off" -> _ocpStatus.postValue("OFF")
                        }
                    }
                    return@collect
                }
                _psuData.postValue(data)
                _power.postValue(data.volt * data.curr)

                val now = System.currentTimeMillis()
                if (lastUpdateMs > 0L) {
                    val dtHours = (now - lastUpdateMs) / 3_600_000f
                    capacityAccum += data.curr * 1000f * dtHours
                    _capacity.postValue(capacityAccum)
                }
                lastUpdateMs = now

                _pwmEnabled.postValue(data.pwmEnabled)
                _pwmDur.postValue(data.pwmDur)

                val status = when {
                    data.ocpTripped -> "TRIP"
                    data.ocpEnabled -> "ON"
                    else            -> "OFF"
                }
                _ocpStatus.postValue(status)

                synchronized(liveBuffer) {
                    liveBuffer.add(data.curr)
                }
            }
        }
    }

    fun stopObserving() {
        statusJob?.cancel()
        dataJob?.cancel()
        statusJob = null
        dataJob = null
    }

    fun jalankanAutoMatch(
        context: android.content.Context,
        scope: kotlinx.coroutines.CoroutineScope,
        filterBrand: String = "",
        filterModel: String = ""
    ) {
        scope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("rphone_prefs",
                android.content.Context.MODE_PRIVATE)
            val threshold = try {
                prefs.getFloat("dtw_threshold", 90f)
            } catch (e: ClassCastException) {
                prefs.getInt("dtw_threshold", 90).toFloat()
            }

            // Gunakan snapshot — bukan live buffer yang sudah terkontaminasi data post-recording
            val waveformUser = if (waveformSnapshot.isNotEmpty()) waveformSnapshot else getLiveWaveform()
            val dao = WaveIDDatabase.getInstance(context).profilArusDao()

            // Filter: brand+model jika keduanya ada, brand saja jika model kosong, fallback semua PSU
            val filteredProfil = when {
                filterBrand.isNotBlank() && filterModel.isNotBlank() ->
                    dao.getAllByModeAndChipset("PSU", filterBrand, filterModel)
                filterBrand.isNotBlank() ->
                    dao.getAllByMode("PSU").filter {
                        it.brand.equals(filterBrand, ignoreCase = true)
                    }
                else ->
                    dao.getAllSync().filter { it.modeRekam == "PSU" }
            }

            val hasil = DtwMatcher.cariKemiripan(
                waveformUser = waveformUser,
                database     = filteredProfil,
                threshold    = threshold
            )

            _hasilAutoMatch.postValue(hasil)
        }
    }

    fun resetHasilAutoMatch() {
        _hasilAutoMatch.value = emptyList()
    }

    fun simpanPreAnalisa(samples: List<Float>) {
        if (samples.isEmpty()) {
            _preAnalisaResult.postValue(null)
            return
        }
        val maSamples = samples.map { it * 1000f }
        val avg = maSamples.average().toFloat()
        val max = maSamples.max()
        val min = maSamples.min()

        val status = when {
            avg > 300f -> "SHORT_HARD"
            avg > 30f  -> "SHORT_HALUS"
            else       -> "NORMAL"
        }

        // Deteksi pola: bandingkan separuh awal vs separuh akhir
        val half = maSamples.size / 2
        val avgFirst = if (half > 0) maSamples.take(half).average().toFloat() else avg
        val avgLast  = if (half > 0) maSamples.drop(half).average().toFloat() else avg
        val pola = when {
            avgLast > avgFirst * 1.1f -> "naik"
            avgLast < avgFirst * 0.9f -> "turun"
            else                      -> "stabil"
        }

        _preAnalisaResult.postValue(
            PreAnalisaResult(
                status = status,
                avgMa  = avg,
                maxMa  = max,
                minMa  = min,
                pola   = pola
            )
        )
    }

    fun resetPreAnalisa() {
        _preAnalisaResult.value = null
    }

    fun getLiveWaveform(): List<Float> {
        return synchronized(liveBuffer) { liveBuffer.toList() }
    }

    fun resetLiveBuffer() {
        synchronized(liveBuffer) { liveBuffer.clear() }
        capacityAccum = 0f
        lastUpdateMs  = 0L
        _capacity.value = 0f
    }
}
