package com.rphone.v3.util

import com.rphone.v3.model.ProbeMode

object ProbeValidator {

    fun validateMode(mode: String?): Boolean {
        if (mode.isNullOrBlank()) return false
        return ProbeMode.isValid(mode)
    }

    fun validateDataPoints(dataPoints: List<Double>?): Boolean {
        return dataPoints != null && dataPoints.isNotEmpty()
    }

    fun validateVoltageValue(voltage: Double): Boolean {
        return voltage >= 0.0 && voltage <= 1000.0
    }

    fun validateResistanceValue(resistance: Double): Boolean {
        return resistance >= 0.0 && resistance <= 10000000.0
    }

    fun validateVdropValue(vdrop: Double): Boolean {
        return vdrop >= 0.0 && vdrop <= 3.0
    }

    fun validateProbeCommand(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        return command in listOf("GET_VOLT", "GET_DIODE", "GET_OHM")
    }

    fun getModeFromCommand(command: String): ProbeMode? {
        return ProbeMode.fromCommand(command)
    }

    fun getCommandFromMode(mode: String): String? {
        return ProbeMode.fromString(mode)?.commandName
    }

    fun sanitizeMode(mode: String?): ProbeMode {
        val validated = ProbeMode.fromString(mode)
        return validated ?: ProbeMode.VOLT
    }
}
