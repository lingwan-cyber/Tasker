package com.example.tasker.automation

import android.content.Context
import android.util.Log
import com.example.tasker.data.AutomationRepository
import com.example.tasker.data.AutomationRule
import com.example.tasker.data.TriggerType
import com.example.tasker.runner.ShellExecutor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object AutomationEngine {
    private const val TAG = "TaskerAutomation"
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Normalizes an Android Wi-Fi SSID (strips surrounding quotes and treats Android's
     * "<unknown ssid>" / "0x" placeholders as null).
     */
    fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val stripped = raw.trim().removePrefix("\"").removeSuffix("\"").trim()
        if (stripped.isEmpty()) return null
        if (stripped.equals("<unknown ssid>", ignoreCase = true)) return null
        if (stripped.equals("0x", ignoreCase = true)) return null
        return stripped
    }

    fun ssidMatches(ruleSsid: String, observedSsid: String?): Boolean {
        val normObserved = normalizeSsid(observedSsid) ?: return false
        val normRule = normalizeSsid(ruleSsid) ?: return true // empty or wildcard matches any Wi-Fi
        if (normRule == "*") return true
        return normRule.equals(normObserved, ignoreCase = true)
    }

    /**
     * Evaluates Wi-Fi connect/disconnect rules against previous and current SSID.
     */
    fun evaluateWifiTransition(
        rules: List<AutomationRule>,
        previousSsid: String?,
        currentSsid: String?,
        forceEvaluateCurrent: Boolean = false
    ): List<AutomationRule> {
        val prev = normalizeSsid(previousSsid)
        val curr = normalizeSsid(currentSsid)

        if (!forceEvaluateCurrent && prev.equals(curr, ignoreCase = true)) {
            return emptyList()
        }

        val triggered = mutableListOf<AutomationRule>()
        for (rule in rules) {
            if (!rule.enabled || !rule.triggerType.isWifi) continue
            when (rule.triggerType) {
                TriggerType.WIFI_CONNECT -> {
                    val matchesNow = curr != null && ssidMatches(rule.wifiSsid, curr)
                    val matchedBefore = prev != null && ssidMatches(rule.wifiSsid, prev)
                    if (matchesNow && (forceEvaluateCurrent || !matchedBefore)) {
                        triggered.add(rule)
                    }
                }
                TriggerType.WIFI_DISCONNECT -> {
                    val matchedBefore = prev != null && ssidMatches(rule.wifiSsid, prev)
                    val matchesNow = curr != null && ssidMatches(rule.wifiSsid, curr)
                    if (matchedBefore && !matchesNow) {
                        triggered.add(rule)
                    }
                }
                else -> Unit
            }
        }
        return triggered
    }

    /**
     * Haversine distance between two lat/lng coordinates in meters.
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2) +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Evaluates geofence enter/exit transitions using previous inside/outside state per rule.
     * Returns (triggeredRules, updatedInsideStateMap).
     */
    fun evaluateLocationTransition(
        rules: List<AutomationRule>,
        latitude: Double,
        longitude: Double,
        previousInsideByRuleId: Map<String, Boolean>,
        triggerEnterOnInitialFix: Boolean = true,
        forceEvaluateCurrent: Boolean = false
    ): Pair<List<AutomationRule>, Map<String, Boolean>> {
        val nextState = previousInsideByRuleId.toMutableMap()
        val triggered = mutableListOf<AutomationRule>()

        for (rule in rules) {
            if (!rule.enabled || !rule.triggerType.isLocation) continue
            val distance = calculateDistanceMeters(latitude, longitude, rule.latitude, rule.longitude)
            val isInside = distance <= rule.radiusMeters
            val wasInside = previousInsideByRuleId[rule.id]
            nextState[rule.id] = isInside

            when (rule.triggerType) {
                TriggerType.LOCATION_ENTER -> {
                    val enteredNow = when {
                        forceEvaluateCurrent -> isInside
                        wasInside == null -> isInside && triggerEnterOnInitialFix
                        else -> !wasInside && isInside
                    }
                    if (enteredNow) {
                        triggered.add(rule)
                    }
                }
                TriggerType.LOCATION_EXIT -> {
                    val exitedNow = wasInside == true && !isInside
                    if (exitedNow) {
                        triggered.add(rule)
                    }
                }
                else -> Unit
            }
        }

        return triggered to nextState
    }

    /**
     * Executes a list of triggered rules and persists their execution summary.
     */
    suspend fun executeRules(
        context: Context,
        rules: List<AutomationRule>,
        reason: String
    ): List<Pair<AutomationRule, String>> {
        val repo = AutomationRepository.getInstance(context)
        val results = mutableListOf<Pair<AutomationRule, String>>()

        for (rule in rules) {
            val cmd = rule.effectiveCommand()
            val res = ShellExecutor.execute(cmd, asRoot = false, context = context.applicationContext)
            val summary = if (res.exitCode == 0) {
                val out = res.stdout.lines().firstOrNull()?.take(80).orEmpty()
                if (out.isNotBlank()) "✓ $out ($reason)" else "✓ Executed ($reason)"
            } else {
                val err = res.stderr.lines().firstOrNull()?.take(80) ?: "Exit ${res.exitCode}"
                "✗ $err ($reason)"
            }
            Log.i(TAG, "Rule '${rule.name}' triggered [$reason] -> $summary")
            repo.recordTrigger(rule.id, summary)
            results.add(rule to summary)
        }
        return results
    }
}
