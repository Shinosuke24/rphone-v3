package com.rphone.v3.ui.usb

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rphone.v3.connection.ConnectionManager
import com.rphone.v3.model.BtStatus
import com.rphone.v3.model.UsbData
import com.rphone.v3.util.JsonParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UsbViewModel : ViewModel() {

    private val _usbData = MutableLiveData(UsbData())
    val usbData: LiveData<UsbData> = _usbData

    private val _btStatus = MutableLiveData(BtStatus.DISCONNECTED)
    val btStatus: LiveData<BtStatus> = _btStatus

    private val _power = MutableLiveData(0f)
    val power: LiveData<Float> = _power

    private val _capacity = MutableLiveData(0f)
    val capacity: LiveData<Float> = _capacity

    private val _ocpStatus = MutableLiveData("OFF")
    val ocpStatus: LiveData<String> = _ocpStatus

    private var statusJob: Job? = null
    private var dataJob: Job? = null

    private var capacityAccum  = 0f
    private var lastUpdateMs   = 0L

    // ── Charge protocol voting ──────────────────────────────────
    private val VOTE_BUFFER_SIZE = 5
    private val VOTE_MIN_COUNT   = 3
    private val chargeVoteBuffer = ArrayDeque<String>(VOTE_BUFFER_SIZE)
    private var lastStableCharge = "Standard Charging"

    fun startObserving(connectionManager: ConnectionManager) {
        statusJob?.cancel()
        dataJob?.cancel()

        statusJob = viewModelScope.launch {
            connectionManager.status.collectLatest { status ->
                _btStatus.postValue(status)
            }
        }

        dataJob = viewModelScope.launch {
            connectionManager.dataFlow.collect { json ->
                val data = JsonParser.parseUsbData(json) ?: return@collect

                val stableCharge = voteCharge(data.charge)
                val stableData   = data.copy(charge = stableCharge)
                _usbData.postValue(stableData)

                val p = stableData.volt * stableData.curr
                _power.postValue(p)

                val now = System.currentTimeMillis()
                if (lastUpdateMs > 0L) {
                    val dtHours = (now - lastUpdateMs) / 3_600_000f
                    capacityAccum += stableData.curr * 1000f * dtHours
                    _capacity.postValue(capacityAccum)
                }
                lastUpdateMs = now

                try {
                    val jsonObj = org.json.JSONObject(json)
                    when {
                        jsonObj.optString("ocp") == "trip"  -> _ocpStatus.postValue("TRIP")
                        jsonObj.optString("ocp") == "reset" -> _ocpStatus.postValue("OFF")
                        jsonObj.has("ocp") && jsonObj.get("ocp") is Boolean -> {
                            _ocpStatus.postValue(
                                if (jsonObj.getBoolean("ocp")) "ON" else "OFF"
                            )
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    fun stopObserving() {
        statusJob?.cancel()
        dataJob?.cancel()
        statusJob = null
        dataJob = null
    }

    fun resetStats() {
        capacityAccum    = 0f
        lastUpdateMs     = 0L
        _capacity.value  = 0f
        chargeVoteBuffer.clear()
        lastStableCharge = "Standard Charging"
    }

    private fun voteCharge(newValue: String): String {
        if (chargeVoteBuffer.size >= VOTE_BUFFER_SIZE) {
            chargeVoteBuffer.removeFirst()
        }
        chargeVoteBuffer.addLast(newValue)

        val freq   = chargeVoteBuffer.groupingBy { it }.eachCount()
        val winner = freq.entries
            .filter  { it.value >= VOTE_MIN_COUNT }
            .maxByOrNull { it.value }
            ?.key

        if (winner != null) lastStableCharge = winner
        return lastStableCharge
    }
}
