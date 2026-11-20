package com.example.tasker.automation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tasker.MainActivity
import com.example.tasker.R
import com.example.tasker.data.AutomationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AutomationMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: AutomationRepository

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var locationListener: LocationListener? = null
    private var lastKnownSsid: String? = null
    private var hasInitializedWifi = false
    private var locationInsideState: Map<String, Boolean> = emptyMap()
    private var lastStatusLine: String = "Listening for Wi-Fi & location triggers"

    override fun onCreate() {
        super.onCreate()
        repository = AutomationRepository.getInstance(this)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!repository.monitoringEnabled.value || !repository.hasEnabledRules()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely()
        registerWifiMonitor()
        registerLocationMonitor()

        val forceEvaluate = intent?.action == ACTION_EVALUATE_NOW
        evaluateCurrentSnapshot(forceEvaluate = forceEvaluate)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterWifiMonitor()
        unregisterLocationMonitor()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()
        val permStatus = AutomationHelper.getPermissionStatus(this)
        val needLocationFgs = permStatus.fineLocation && repository.hasEnabledLocationRules()

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    val fgsType = if (needLocationFgs) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    }
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    if (needLocationFgs) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                }
                else -> {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location FGS type denied, falling back to specialUse/default", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun registerWifiMonitor() {
        val hasWifiRules = repository.rules.value.any { it.enabled && it.triggerType.isWifi }
        if (!hasWifiRules) {
            unregisterWifiMonitor()
            return
        }
        if (wifiCallback != null) return

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiInfo
                    val ssid = AutomationEngine.normalizeSsid(info?.ssid)
                        ?: AutomationHelper.getCurrentWifiSsid(this@AutomationMonitorService)
                    handleWifiObserved(ssid, forceEvaluate = false)
                }

                override fun onLost(network: Network) {
                    val remaining = AutomationHelper.getCurrentWifiSsid(this@AutomationMonitorService)
                    handleWifiObserved(remaining, forceEvaluate = false)
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val ssid = AutomationHelper.getCurrentWifiSsid(this@AutomationMonitorService)
                    handleWifiObserved(ssid, forceEvaluate = false)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ssid = AutomationHelper.getCurrentWifiSsid(this@AutomationMonitorService)
                    handleWifiObserved(ssid, forceEvaluate = false)
                }

                override fun onLost(network: Network) {
                    val remaining = AutomationHelper.getCurrentWifiSsid(this@AutomationMonitorService)
                    handleWifiObserved(remaining, forceEvaluate = false)
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            wifiCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Wi-Fi NetworkCallback", e)
        }
    }

    private fun unregisterWifiMonitor() {
        val cb = wifiCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        wifiCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationMonitor() {
        val hasLocationRules = repository.hasEnabledLocationRules()
        val perms = AutomationHelper.getPermissionStatus(this)
        if (!hasLocationRules || !perms.fineLocation) {
            unregisterLocationMonitor()
            return
        }
        if (locationListener != null) return

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocationObserved(location.latitude, location.longitude, forceEvaluate = false)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.allProviders.contains(LocationManager.FUSED_PROVIDER)
            ) {
                add(LocationManager.FUSED_PROVIDER)
            } else {
                if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                    add(LocationManager.NETWORK_PROVIDER)
                }
                if (lm.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                    add(LocationManager.PASSIVE_PROVIDER)
                }
                if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                    add(LocationManager.GPS_PROVIDER)
                }
            }
        }

        var registeredAny = false
        for (provider in providers) {
            try {
                lm.requestLocationUpdates(
                    provider,
                    LOCATION_MIN_TIME_MS,
                    LOCATION_MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper()
                )
                registeredAny = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not request location updates from $provider", e)
            }
        }

        if (registeredAny) {
            locationListener = listener
        }
    }

    private fun unregisterLocationMonitor() {
        val listener = locationListener ?: return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }
        locationListener = null
    }

    private fun evaluateCurrentSnapshot(forceEvaluate: Boolean) {
        val currentSsid = AutomationHelper.getCurrentWifiSsid(this)
        handleWifiObserved(currentSsid, forceEvaluate = forceEvaluate)

        if (repository.hasEnabledLocationRules()) {
            AutomationHelper.fetchCurrentLocation(this) { loc ->
                if (loc != null) {
                    handleLocationObserved(loc.latitude, loc.longitude, forceEvaluate = forceEvaluate)
                }
            }
        }
    }

    private fun handleWifiObserved(currentSsid: String?, forceEvaluate: Boolean) {
        val prevSsid = if (hasInitializedWifi) lastKnownSsid else null
        hasInitializedWifi = true
        lastKnownSsid = currentSsid
        repository.updateObservedWifiSsid(currentSsid)

        val matching = AutomationEngine.evaluateWifiTransition(
            rules = repository.rules.value,
            previousSsid = prevSsid,
            currentSsid = currentSsid,
            forceEvaluateCurrent = forceEvaluate
        )

        if (matching.isNotEmpty()) {
            val reason = if (currentSsid != null) "Wi-Fi: $currentSsid" else "Wi-Fi disconnected"
            serviceScope.launch {
                val results = AutomationEngine.executeRules(this@AutomationMonitorService, matching, reason)
                results.lastOrNull()?.let { (rule, summary) ->
                    lastStatusLine = "${rule.name}: $summary"
                    updateNotification()
                }
            }
        } else {
            updateNotification()
        }
    }

    private fun handleLocationObserved(latitude: Double, longitude: Double, forceEvaluate: Boolean) {
        repository.updateObservedLocation(latitude, longitude)

        val (matching, nextInside) = AutomationEngine.evaluateLocationTransition(
            rules = repository.rules.value,
            latitude = latitude,
            longitude = longitude,
            previousInsideByRuleId = locationInsideState,
            triggerEnterOnInitialFix = true,
            forceEvaluateCurrent = forceEvaluate
        )
        locationInsideState = nextInside

        if (matching.isNotEmpty()) {
            val reason = "Location ${"%.4f".format(latitude)}, ${"%.4f".format(longitude)}"
            serviceScope.launch {
                val results = AutomationEngine.executeRules(this@AutomationMonitorService, matching, reason)
                results.lastOrNull()?.let { (rule, summary) ->
                    lastStatusLine = "${rule.name}: $summary"
                    updateNotification()
                }
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Automation Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors Wi-Fi and location rules to adjust notification volume automatically"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, piFlags)

        val activeCount = repository.rules.value.count { it.enabled }
        val wifiPart = lastKnownSsid?.let { "Wi-Fi: $it" } ?: "No Wi-Fi"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume)
            .setContentTitle("Tasker Auto Rules ($activeCount active)")
            .setContentText("$wifiPart • $lastStatusLine")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$wifiPart\n$lastStatusLine"))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "TaskerMonitorService"
        private const val CHANNEL_ID = "tasker_automation_monitor"
        private const val NOTIFICATION_ID = 7001

        private const val LOCATION_MIN_TIME_MS = 60_000L
        private const val LOCATION_MIN_DISTANCE_M = 50f

        const val ACTION_START_OR_UPDATE = "com.example.tasker.action.START_OR_UPDATE_MONITOR"
        const val ACTION_EVALUATE_NOW = "com.example.tasker.action.EVALUATE_NOW"
    }
}
