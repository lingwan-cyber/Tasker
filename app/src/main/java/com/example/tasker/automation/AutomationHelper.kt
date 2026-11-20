package com.example.tasker.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.tasker.data.AutomationRepository
import com.example.tasker.data.AutomationRule

data class AutomationPermissionStatus(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val dndAccess: Boolean,
    val postNotifications: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val locationServicesEnabled: Boolean
)

object AutomationHelper {
    private const val TAG = "TaskerAutomationHelper"
    private const val REQ_CODE_WIFI_CALLBACK = 4101
    private const val REQ_CODE_PROXIMITY_BASE = 4200

    fun getPermissionStatus(context: Context): AutomationPermissionStatus {
        val fineLoc = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val bgLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            fineLoc
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val dndAccess = nm.isNotificationPolicyAccessGranted

        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }

        return AutomationPermissionStatus(
            fineLocation = fineLoc,
            backgroundLocation = bgLoc,
            dndAccess = dndAccess,
            postNotifications = postNotif,
            ignoringBatteryOptimizations = ignoringBattery,
            locationServicesEnabled = locEnabled
        )
    }

    /**
     * Reads the currently connected Wi-Fi SSID if available (requires Fine Location + Location ON).
     */
    @Suppress("DEPRECATION")
    fun getCurrentWifiSsid(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps.transportInfo as? WifiInfo
            val fromCaps = AutomationEngine.normalizeSsid(wifiInfo?.ssid)
            if (fromCaps != null) return fromCaps
        }

        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            AutomationEngine.normalizeSsid(wm.connectionInfo?.ssid)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query WifiManager SSID", e)
            null
        }
    }

    /**
     * Fetches the best available current or last-known location.
     */
    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation(context: Context, onResult: (Location?) -> Unit) {
        val status = getPermissionStatus(context)
        if (!status.fineLocation) {
            onResult(null)
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val bestLast = getBestLastKnownLocation(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    lm.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (provider != null) {
                try {
                    val cancelSignal = CancellationSignal()
                    lm.getCurrentLocation(
                        provider,
                        cancelSignal,
                        ContextCompat.getMainExecutor(context)
                    ) { loc ->
                        onResult(loc ?: bestLast)
                    }
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation failed, falling back to last known", e)
                }
            }
        }

        onResult(bestLast)
    }

    @SuppressLint("MissingPermission")
    fun getBestLastKnownLocation(context: Context): Location? {
        val status = getPermissionStatus(context)
        if (!status.fineLocation) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val candidates = mutableListOf<Location>()
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }

        for (p in providers) {
            try {
                if (lm.allProviders.contains(p)) {
                    lm.getLastKnownLocation(p)?.let { candidates.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        return candidates.maxByOrNull { it.time }
    }

    /**
     * Synchronizes background monitoring state (Foreground Service + PendingIntent fallback callbacks).
     */
    fun syncMonitoring(context: Context, fromBoot: Boolean = false) {
        val appCtx = context.applicationContext
        val repo = AutomationRepository.getInstance(appCtx)
        val shouldRun = repo.monitoringEnabled.value && repo.hasEnabledRules()

        if (shouldRun) {
            registerFallbackCallbacks(appCtx, repo.rules.value)
            val intent = Intent(appCtx, AutomationMonitorService::class.java).apply {
                action = AutomationMonitorService.ACTION_START_OR_UPDATE
            }
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (e: Exception) {
                // On Android 15+, starting certain FGS from BOOT_COMPLETED may be restricted;
                // fallback PendingIntent network & proximity receivers remain active.
                Log.w(TAG, "Unable to start foreground service (fromBoot=$fromBoot); fallback receivers active", e)
            }
        } else {
            unregisterFallbackCallbacks(appCtx, repo.rules.value)
            appCtx.stopService(Intent(appCtx, AutomationMonitorService::class.java))
        }
    }

    fun requestImmediateEvaluation(context: Context) {
        val appCtx = context.applicationContext
        val intent = Intent(appCtx, AutomationMonitorService::class.java).apply {
            action = AutomationMonitorService.ACTION_EVALUATE_NOW
        }
        try {
            ContextCompat.startForegroundService(appCtx, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start service for immediate evaluation", e)
        }
    }

    private fun wifiPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AutomationEventReceiver::class.java).apply {
            action = AutomationEventReceiver.ACTION_WIFI_CHANGED
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, REQ_CODE_WIFI_CALLBACK, intent, flags)
    }

    private fun proximityPendingIntent(context: Context, ruleId: String): PendingIntent {
        val intent = Intent(context, AutomationEventReceiver::class.java).apply {
            action = AutomationEventReceiver.ACTION_PROXIMITY_ALERT
            putExtra(AutomationEventReceiver.EXTRA_RULE_ID, ruleId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val reqCode = REQ_CODE_PROXIMITY_BASE + (ruleId.hashCode() and 0x7FFF)
        return PendingIntent.getBroadcast(context, reqCode, intent, flags)
    }

    @SuppressLint("MissingPermission")
    fun registerFallbackCallbacks(context: Context, rules: List<AutomationRule>) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hasWifiRules = rules.any { it.enabled && it.triggerType.isWifi }
        val wifiPi = wifiPendingIntent(context)
        try {
            cm.unregisterNetworkCallback(wifiPi)
        } catch (_: Exception) {
        }
        if (hasWifiRules) {
            try {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                cm.registerNetworkCallback(request, wifiPi)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register PendingIntent Wi-Fi callback", e)
            }
        }

        val status = getPermissionStatus(context)
        if (status.fineLocation) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (rule in rules) {
                val pi = proximityPendingIntent(context, rule.id)
                try {
                    lm.removeProximityAlert(pi)
                } catch (_: Exception) {
                }
                if (rule.enabled && rule.triggerType.isLocation) {
                    try {
                        lm.addProximityAlert(
                            rule.latitude,
                            rule.longitude,
                            rule.radiusMeters.coerceAtLeast(25f),
                            -1L,
                            pi
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to register proximity alert for ${rule.name}", e)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun unregisterFallbackCallbacks(context: Context, rules: List<AutomationRule>) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(wifiPendingIntent(context))
        } catch (_: Exception) {
        }

        val status = getPermissionStatus(context)
        if (status.fineLocation) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (rule in rules) {
                try {
                    lm.removeProximityAlert(proximityPendingIntent(context, rule.id))
                } catch (_: Exception) {
                }
            }
        }
    }
}
