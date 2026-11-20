package com.example.tasker.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import com.example.tasker.data.AutomationRepository
import com.example.tasker.data.TriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles OS-delivered PendingIntent events for Wi-Fi network changes and LocationManager
 * proximity alerts even when the app process was stopped or rebooted.
 */
class AutomationEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        val repo = AutomationRepository.getInstance(appCtx)
        if (!repo.monitoringEnabled.value) return

        when (intent.action) {
            ACTION_WIFI_CHANGED -> {
                val previousSsid = repo.lastWifiSsid.value
                val currentSsid = AutomationHelper.getCurrentWifiSsid(appCtx)
                repo.updateObservedWifiSsid(currentSsid)

                val matching = AutomationEngine.evaluateWifiTransition(
                    rules = repo.rules.value,
                    previousSsid = previousSsid,
                    currentSsid = currentSsid,
                    forceEvaluateCurrent = false
                )
                if (matching.isNotEmpty()) {
                    val pendingResult = goAsync()
                    val reason = if (currentSsid != null) "Wi-Fi: $currentSsid" else "Wi-Fi disconnected"
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            AutomationEngine.executeRules(appCtx, matching, reason)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error executing Wi-Fi rules in receiver", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            ACTION_PROXIMITY_ALERT -> {
                val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return
                val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
                val rule = repo.getById(ruleId) ?: return
                if (!rule.enabled) return

                val shouldFire = (entering && rule.triggerType == TriggerType.LOCATION_ENTER) ||
                    (!entering && rule.triggerType == TriggerType.LOCATION_EXIT)

                if (shouldFire) {
                    val pendingResult = goAsync()
                    val reason = if (entering) "Entered geofence" else "Exited geofence"
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            AutomationEngine.executeRules(appCtx, listOf(rule), reason)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error executing proximity rule in receiver", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "TaskerAutoReceiver"
        const val ACTION_WIFI_CHANGED = "com.example.tasker.action.WIFI_CHANGED"
        const val ACTION_PROXIMITY_ALERT = "com.example.tasker.action.PROXIMITY_ALERT"
        const val EXTRA_RULE_ID = "extra_rule_id"
    }
}
