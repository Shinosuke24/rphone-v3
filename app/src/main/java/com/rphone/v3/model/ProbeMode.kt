package com.rphone.v3.model

enum class ProbeMode(val displayName: String, val commandName: String) {
    VOLT("TEGANGAN", "GET_VOLT"),
    DIODE("DIODA", "GET_DIODE"),
    OHM("RESISTANSI", "GET_OHM");

    companion object {
        fun fromString(value: String?): ProbeMode? {
            return values().find { 
                it.name.equals(value, ignoreCase = true) || 
                it.displayName.equals(value, ignoreCase = true)
            }
        }

        fun fromCommand(command: String?): ProbeMode? {
            return values().find { it.commandName.equals(command, ignoreCase = true) }
        }

        fun isValid(value: String?): Boolean {
            return fromString(value) != null
        }

        fun getAllModes(): List<String> {
            return values().map { it.name }
        }
    }
}
