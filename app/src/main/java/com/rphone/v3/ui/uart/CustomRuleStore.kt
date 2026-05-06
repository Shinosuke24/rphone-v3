package com.rphone.v3.ui.uart

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object CustomRuleStore {

    private const val KEY = "uart_custom_rules"
    private const val MAX_RULES = 50

    fun loadRules(prefs: SharedPreferences): List<CustomRule> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                CustomRule(
                    id      = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    label   = obj.optString("label", ""),
                    pattern = obj.optString("pattern", ""),
                    group   = obj.optInt("group", 0),
                    status  = obj.optString("status", "NORMAL")
                ).takeIf { it.label.isNotBlank() && it.pattern.isNotBlank() }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveRules(prefs: SharedPreferences, rules: List<CustomRule>) {
        val arr = JSONArray()
        rules.take(MAX_RULES).forEach { rule ->
            arr.put(JSONObject().apply {
                put("id",      rule.id)
                put("label",   rule.label)
                put("pattern", rule.pattern)
                put("group",   rule.group)
                put("status",  rule.status)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun deleteRule(prefs: SharedPreferences, id: String) {
        val rules = loadRules(prefs).filter { it.id != id }
        saveRules(prefs, rules)
    }

    fun addRule(prefs: SharedPreferences, rule: CustomRule) {
        val rules = loadRules(prefs).toMutableList()
        rules.add(rule)
        saveRules(prefs, rules)
    }
}
