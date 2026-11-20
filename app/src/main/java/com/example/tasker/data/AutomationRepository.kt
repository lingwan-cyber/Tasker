package com.example.tasker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class AutomationRepository(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _rules = MutableStateFlow<List<AutomationRule>>(emptyList())
    val rules: StateFlow<List<AutomationRule>> = _rules.asStateFlow()

    private val _monitoringEnabled = MutableStateFlow(false)
    val monitoringEnabled: StateFlow<Boolean> = _monitoringEnabled.asStateFlow()

    private val _lastWifiSsid = MutableStateFlow<String?>(null)
    val lastWifiSsid: StateFlow<String?> = _lastWifiSsid.asStateFlow()

    private val _lastLocationText = MutableStateFlow<String?>(null)
    val lastLocationText: StateFlow<String?> = _lastLocationText.asStateFlow()

    init {
        load()
    }

    private fun load() {
        _monitoringEnabled.value = prefs.getBoolean(KEY_MONITORING_ENABLED, false)
        _lastWifiSsid.value = prefs.getString(KEY_LAST_WIFI_SSID, null)
        _lastLocationText.value = prefs.getString(KEY_LAST_LOCATION, null)

        val jsonString = prefs.getString(KEY_RULES, null)
        if (jsonString.isNullOrBlank()) {
            _rules.value = emptyList()
            return
        }
        try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<AutomationRule>()
            for (i in 0 until array.length()) {
                list.add(AutomationRule.fromJson(array.getJSONObject(i)))
            }
            _rules.value = list
        } catch (e: Exception) {
            _rules.value = emptyList()
        }
    }

    fun getById(id: String): AutomationRule? = _rules.value.firstOrNull { it.id == id }

    fun save(rule: AutomationRule) {
        val current = _rules.value.toMutableList()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            current[idx] = rule
        } else {
            current.add(0, rule)
        }
        persistRules(current)
        _rules.value = current
    }

    fun delete(id: String) {
        val updated = _rules.value.filterNot { it.id == id }
        persistRules(updated)
        _rules.value = updated
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        val updated = _rules.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persistRules(updated)
        _rules.value = updated
    }

    fun recordTrigger(id: String, summary: String, timestamp: Long = System.currentTimeMillis()) {
        val updated = _rules.value.map {
            if (it.id == id) {
                it.copy(lastTriggeredAt = timestamp, lastResultSummary = summary)
            } else {
                it
            }
        }
        persistRules(updated)
        _rules.value = updated
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply()
        _monitoringEnabled.value = enabled
    }

    fun updateObservedWifiSsid(ssid: String?) {
        prefs.edit().putString(KEY_LAST_WIFI_SSID, ssid).apply()
        _lastWifiSsid.value = ssid
    }

    fun updateObservedLocation(latitude: Double, longitude: Double) {
        val formatted = "${"%.5f".format(latitude)}, ${"%.5f".format(longitude)}"
        prefs.edit().putString(KEY_LAST_LOCATION, formatted).apply()
        _lastLocationText.value = formatted
    }

    fun hasEnabledRules(): Boolean = _rules.value.any { it.enabled }

    fun hasEnabledLocationRules(): Boolean = _rules.value.any { it.enabled && it.triggerType.isLocation }

    private fun persistRules(items: List<AutomationRule>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "tasker_automation_prefs"
        private const val KEY_RULES = "automation_rules_list"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_LAST_WIFI_SSID = "last_wifi_ssid"
        private const val KEY_LAST_LOCATION = "last_location_text"

        @Volatile
        private var instance: AutomationRepository? = null

        fun getInstance(context: Context): AutomationRepository {
            return instance ?: synchronized(this) {
                instance ?: AutomationRepository(context).also { instance = it }
            }
        }
    }
}
