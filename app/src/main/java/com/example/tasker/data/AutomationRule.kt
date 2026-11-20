package com.example.tasker.data

import org.json.JSONObject
import java.util.UUID

enum class TriggerType(val label: String) {
    WIFI_CONNECT("Wi-Fi Connected"),
    WIFI_DISCONNECT("Wi-Fi Disconnected"),
    LOCATION_ENTER("Arrive at Location"),
    LOCATION_EXIT("Leave Location");

    val isWifi: Boolean
        get() = this == WIFI_CONNECT || this == WIFI_DISCONNECT

    val isLocation: Boolean
        get() = this == LOCATION_ENTER || this == LOCATION_EXIT

    companion object {
        fun fromString(value: String?): TriggerType {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WIFI_CONNECT
        }
    }
}

data class AutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val triggerType: TriggerType = TriggerType.WIFI_CONNECT,
    val wifiSsid: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Float = 150f,
    val notificationVolumePercent: Int = 50,
    val customCommand: String = "",
    val lastTriggeredAt: Long = 0L,
    val lastResultSummary: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun effectiveCommand(): String {
        return customCommand.trim().ifBlank {
            "volume notification ${notificationVolumePercent.coerceIn(0, 100)}%"
        }
    }

    fun triggerSummary(): String {
        return when (triggerType) {
            TriggerType.WIFI_CONNECT -> "Join Wi-Fi \"${wifiSsid.ifBlank { "*" }}\""
            TriggerType.WIFI_DISCONNECT -> "Leave Wi-Fi \"${wifiSsid.ifBlank { "*" }}\""
            TriggerType.LOCATION_ENTER -> "Enter ${"%.4f".format(latitude)}, ${"%.4f".format(longitude)} (${radiusMeters.toInt()}m)"
            TriggerType.LOCATION_EXIT -> "Exit ${"%.4f".format(latitude)}, ${"%.4f".format(longitude)} (${radiusMeters.toInt()}m)"
        }
    }

    fun actionSummary(): String {
        return if (customCommand.isNotBlank()) {
            customCommand.trim()
        } else {
            val pct = notificationVolumePercent.coerceIn(0, 100)
            if (pct == 0) "Notif Vol → Mute (0%)" else "Notif Vol → $pct%"
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("enabled", enabled)
            put("triggerType", triggerType.name)
            put("wifiSsid", wifiSsid)
            put("latitude", latitude)
            put("longitude", longitude)
            put("radiusMeters", radiusMeters.toDouble())
            put("notificationVolumePercent", notificationVolumePercent)
            put("customCommand", customCommand)
            put("lastTriggeredAt", lastTriggeredAt)
            put("lastResultSummary", lastResultSummary)
            put("createdAt", createdAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationRule {
            return AutomationRule(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Untitled Rule"),
                enabled = json.optBoolean("enabled", true),
                triggerType = TriggerType.fromString(json.optString("triggerType", TriggerType.WIFI_CONNECT.name)),
                wifiSsid = json.optString("wifiSsid", ""),
                latitude = json.optDouble("latitude", 0.0),
                longitude = json.optDouble("longitude", 0.0),
                radiusMeters = json.optDouble("radiusMeters", 150.0).toFloat().coerceAtLeast(25f),
                notificationVolumePercent = json.optInt("notificationVolumePercent", 50).coerceIn(0, 100),
                customCommand = json.optString("customCommand", ""),
                lastTriggeredAt = json.optLong("lastTriggeredAt", 0L),
                lastResultSummary = json.optString("lastResultSummary", ""),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
