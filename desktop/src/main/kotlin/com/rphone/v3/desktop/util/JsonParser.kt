package com.rphone.v3.desktop.util

/**
 * JSON parsing utilities — parity with APK com.rphone.v3.util.JsonParser
 */
object JsonParser {

    fun extractString(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groups?.get(1)?.value
    }

    fun extractDouble(json: String, key: String): Double? {
        val regex = Regex("\"$key\"\\s*:\\s*([-+]?[0-9]*\\.?[0-9]+)")
        return regex.find(json)?.groups?.get(1)?.value?.toDoubleOrNull()
    }

    fun extractInt(json: String, key: String): Int? {
        val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return regex.find(json)?.groups?.get(1)?.value?.toIntOrNull()
    }

    fun extractBoolean(json: String, key: String): Boolean? {
        val regex = Regex("\"$key\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
        return regex.find(json)?.groups?.get(1)?.value?.equals("true", ignoreCase = true)
    }

    fun extractArray(json: String, key: String): List<String> {
        val regex = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
        val match = regex.find(json) ?: return emptyList()
        val arrayContent = match.groups[1]?.value ?: return emptyList()
        return Regex("\"([^\"]*)\"").findAll(arrayContent).map { it.groups[1]?.value ?: "" }.toList()
    }

    fun toJson(map: Map<String, Any>): String {
        return buildString {
            append("{")
            map.entries.forEachIndexed { idx, (key, value) ->
                if (idx > 0) append(",")
                append("\"$key\":")
                when (value) {
                    is String -> append("\"$value\"")
                    is Number -> append(value)
                    is Boolean -> append(value)
                    else -> append("\"$value\"")
                }
            }
            append("}")
        }
    }
}
