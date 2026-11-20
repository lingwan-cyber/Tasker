package com.example.tasker.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.tasker.R
import com.example.tasker.automation.AutomationHelper
import com.example.tasker.automation.AutomationPermissionStatus
import com.example.tasker.data.AutomationRule
import com.example.tasker.data.TriggerType
import com.example.tasker.runner.ShellExecutor
import com.example.tasker.runner.VolumeController
import com.example.tasker.runner.VolumeStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AutomationTabContent(
    rules: List<AutomationRule>,
    monitoringEnabled: Boolean,
    observedWifiSsid: String?,
    observedLocationText: String?,
    onToggleMonitoring: (Boolean) -> Unit,
    onEvaluateNow: () -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onEditRule: (AutomationRule) -> Unit,
    onDeleteRule: (AutomationRule) -> Unit,
    onCreateRule: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var permStatus by remember { mutableStateOf(AutomationHelper.getPermissionStatus(context)) }
    var volumeStatus by remember { mutableStateOf(VolumeController.getVolumeStatus(context)) }
    var liveWifiSsid by remember { mutableStateOf(AutomationHelper.getCurrentWifiSsid(context)) }
    var showPermissionsExpanded by remember { mutableStateOf(false) }

    fun refreshLiveStatus() {
        permStatus = AutomationHelper.getPermissionStatus(context)
        volumeStatus = VolumeController.getVolumeStatus(context)
        liveWifiSsid = AutomationHelper.getCurrentWifiSsid(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshLiveStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fineLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshLiveStatus()
    }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshLiveStatus()
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshLiveStatus()
    }

    val displayedWifi = liveWifiSsid ?: observedWifiSsid
    val allCoreGranted = permStatus.fineLocation && permStatus.backgroundLocation &&
        permStatus.dndAccess && permStatus.postNotifications && permStatus.locationServicesEnabled

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Background Monitor Status & Master Switch Card
        item(key = "monitor_card") {
            MonitorStatusCard(
                monitoringEnabled = monitoringEnabled,
                activeRuleCount = rules.count { it.enabled },
                volumeStatus = volumeStatus,
                wifiSsid = displayedWifi,
                locationText = observedLocationText,
                onToggleMonitoring = { enabled ->
                    if (enabled && rules.none { it.enabled }) {
                        Toast.makeText(
                            context,
                            "Enable or create at least one rule first.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    onToggleMonitoring(enabled)
                    refreshLiveStatus()
                },
                onCheckNow = {
                    refreshLiveStatus()
                    if (monitoringEnabled) {
                        onEvaluateNow()
                        Toast.makeText(context, "Checking Wi-Fi & location rules...", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Refreshed status", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // 2. Permissions Checklist Card
        item(key = "permissions_card") {
            PermissionsChecklistCard(
                status = permStatus,
                allCoreGranted = allCoreGranted,
                expanded = !allCoreGranted || showPermissionsExpanded,
                onToggleExpand = { showPermissionsExpanded = !showPermissionsExpanded },
                onRequestFineLocation = {
                    fineLocationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onRequestBackgroundLocation = {
                    if (!permStatus.fineLocation) {
                        Toast.makeText(context, "Grant Location permission first.", Toast.LENGTH_SHORT).show()
                        fineLocationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                },
                onOpenDndSettings = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Open Settings -> Notifications -> Do Not Disturb access", Toast.LENGTH_LONG).show()
                    }
                },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenLocationSettings = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (_: Exception) {
                    }
                },
                onOpenBatterySettings = {
                    @SuppressLint("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }
            )
        }

        // 3. Rules list or empty state
        if (rules.isEmpty()) {
            item(key = "empty_rules") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volume),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No Auto Rules Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Automatically adjust notification volume in the background when joining/leaving Wi-Fi or entering/exiting a location.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(onClick = onCreateRule) {
                            Text("Create First Auto Rule")
                        }
                    }
                }
            }
        } else {
            items(rules, key = { it.id }) { rule ->
                AutomationRuleCard(
                    rule = rule,
                    onToggleEnabled = { enabled -> onToggleRule(rule.id, enabled) },
                    onTest = {
                        coroutineScope.launch {
                            val res = ShellExecutor.execute(rule.effectiveCommand(), false, context)
                            refreshLiveStatus()
                            val msg = if (res.exitCode == 0) {
                                "✓ ${res.stdout.lines().firstOrNull() ?: "Applied"}"
                            } else {
                                "✗ ${res.stderr.lines().firstOrNull() ?: "Exit ${res.exitCode}"}"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    onEdit = { onEditRule(rule) },
                    onDelete = { onDeleteRule(rule) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

@Composable
private fun MonitorStatusCard(
    monitoringEnabled: Boolean,
    activeRuleCount: Int,
    volumeStatus: VolumeStatus,
    wifiSsid: String?,
    locationText: String?,
    onToggleMonitoring: (Boolean) -> Unit,
    onCheckNow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (monitoringEnabled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Background Monitor",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (monitoringEnabled) {
                            if (activeRuleCount > 0) "Active • Watching $activeRuleCount rule(s)" else "On • No enabled rules"
                        } else {
                            "Paused • Tap switch to monitor in background"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = monitoringEnabled,
                    onCheckedChange = onToggleMonitoring
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // Live telemetry chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volume),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Notif: ${volumeStatus.current}/${volumeStatus.max} (${volumeStatus.percent}%)" +
                                if (volumeStatus.dndActive) " • DND" else "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_wifi),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = wifiSsid ?: "No Wi-Fi / Unknown",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            if (!locationText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Last location: $locationText",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onCheckNow,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                Text(
                    text = if (monitoringEnabled) "Check & Apply Matching Rules Now" else "Refresh Status",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PermissionsChecklistCard(
    status: AutomationPermissionStatus,
    allCoreGranted: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRequestFineLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onOpenDndSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (allCoreGranted) "Permissions Ready ✓" else "Permissions Required for Background Rules",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (allCoreGranted) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = onToggleExpand,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(if (expanded) "Hide" else "Manage", fontSize = 12.sp)
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                PermissionRow(
                    title = "Location (Fine)",
                    subtitle = "Needed for geofences and reading Wi-Fi SSID",
                    granted = status.fineLocation,
                    actionLabel = "Grant",
                    onAction = onRequestFineLocation
                )
                PermissionRow(
                    title = "Background Location",
                    subtitle = "Set to 'Allow all the time' for background triggers",
                    granted = status.backgroundLocation,
                    actionLabel = "Grant",
                    onAction = onRequestBackgroundLocation
                )
                PermissionRow(
                    title = "Do Not Disturb Access",
                    subtitle = "Needed to mute (0%) or adjust volume during DND",
                    granted = status.dndAccess,
                    actionLabel = "Settings",
                    onAction = onOpenDndSettings
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        title = "Notifications",
                        subtitle = "Shows quiet status notification while monitoring",
                        granted = status.postNotifications,
                        actionLabel = "Grant",
                        onAction = onRequestNotifications
                    )
                }
                if (!status.locationServicesEnabled) {
                    PermissionRow(
                        title = "Device Location Toggle",
                        subtitle = "Android requires system Location ON to read Wi-Fi SSID",
                        granted = false,
                        actionLabel = "Enable",
                        onAction = onOpenLocationSettings
                    )
                }
                PermissionRow(
                    title = "Battery Unrestricted (Optional)",
                    subtitle = "Prevents OEM Doze mode from delaying background rules",
                    granted = status.ignoringBatteryOptimizations,
                    actionLabel = "Allow",
                    onAction = onOpenBatterySettings
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = (if (granted) "✓ " else "• ") + title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (granted) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (!granted) {
            FilledTonalButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text(actionLabel, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun AutomationRuleCard(
    rule: AutomationRule,
    onToggleEnabled: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = if (rule.enabled) 0.55f else 0.25f
            )
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Trigger chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (rule.triggerType.isWifi) R.drawable.ic_wifi else R.drawable.ic_location
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = rule.triggerSummary(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action chip
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_volume),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = rule.actionSummary(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            if (rule.lastTriggeredAt > 0L && rule.lastResultSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                val formattedTime = remember(rule.lastTriggeredAt) {
                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(rule.lastTriggeredAt))
                }
                Text(
                    text = "Last ($formattedTime): ${rule.lastResultSummary}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onTest,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Test Action", fontSize = 12.sp)
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_edit),
                        contentDescription = "Edit Rule",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = "Delete Rule",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AutomationRuleEditDialog(
    initial: AutomationRule?,
    onDismiss: () -> Unit,
    onSave: (AutomationRule) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var triggerType by remember { mutableStateOf(initial?.triggerType ?: TriggerType.WIFI_CONNECT) }
    var wifiSsid by remember { mutableStateOf(initial?.wifiSsid ?: "") }
    var latText by remember {
        mutableStateOf(
            if (initial != null && initial.triggerType.isLocation) initial.latitude.toString() else ""
        )
    }
    var lngText by remember {
        mutableStateOf(
            if (initial != null && initial.triggerType.isLocation) initial.longitude.toString() else ""
        )
    }
    var radiusText by remember { mutableStateOf((initial?.radiusMeters?.toInt() ?: 150).toString()) }
    var volumePercent by remember { mutableFloatStateOf((initial?.notificationVolumePercent ?: 30).toFloat()) }
    var customCommand by remember { mutableStateOf(initial?.customCommand ?: "") }
    var isFetchingLocation by remember { mutableStateOf(false) }

    val canSave = when {
        triggerType.isWifi -> true // blank SSID acts as wildcard (*)
        else -> latText.toDoubleOrNull() != null && lngText.toDoubleOrNull() != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) "New Auto Rule" else "Edit Auto Rule",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Name") },
                    placeholder = { Text("e.g. Office Quiet / Home Loud") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Trigger Condition:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(TriggerType.entries) { type ->
                        FilterChip(
                            selected = triggerType == type,
                            onClick = { triggerType = type },
                            label = { Text(type.label, fontSize = 12.sp) }
                        )
                    }
                }

                if (triggerType.isWifi) {
                    OutlinedTextField(
                        value = wifiSsid,
                        onValueChange = { wifiSsid = it },
                        label = { Text("Wi-Fi Network Name (SSID)") },
                        placeholder = { Text("e.g. OfficeWiFi (or blank for any)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            val current = AutomationHelper.getCurrentWifiSsid(context)
                            if (current != null) {
                                wifiSsid = current
                                if (name.isBlank()) name = "$current Volume"
                            } else {
                                Toast.makeText(
                                    context,
                                    "Could not read Wi-Fi SSID. Ensure Wi-Fi, Location permission, and system Location are enabled.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_wifi),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Use Current Wi-Fi", fontSize = 12.sp)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = latText,
                            onValueChange = { latText = it },
                            label = { Text("Latitude") },
                            placeholder = { Text("37.4220") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = lngText,
                            onValueChange = { lngText = it },
                            label = { Text("Longitude") },
                            placeholder = { Text("-122.0841") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = radiusText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 5) {
                                radiusText = input
                            }
                        },
                        label = { Text("Radius (meters)") },
                        placeholder = { Text("150") },
                        supportingText = { Text("Recommended: 100–300m for reliable geofencing") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedButton(
                        onClick = {
                            isFetchingLocation = true
                            AutomationHelper.fetchCurrentLocation(context) { loc ->
                                isFetchingLocation = false
                                if (loc != null) {
                                    latText = "%.6f".format(Locale.US, loc.latitude)
                                    lngText = "%.6f".format(Locale.US, loc.longitude)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Unable to get location. Grant Fine Location permission and turn Location ON.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = !isFetchingLocation,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isFetchingLocation) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Getting Current Location...", fontSize = 12.sp)
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_location),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Use Current Location", fontSize = 12.sp)
                        }
                    }
                }

                HorizontalDivider()

                val pctInt = volumePercent.roundToInt().coerceIn(0, 100)
                Text(
                    text = "Notification Volume: ${if (pctInt == 0) "Mute (0%)" else "$pctInt%"}",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Slider(
                    value = volumePercent,
                    onValueChange = { volumePercent = it },
                    valueRange = 0f..100f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth()
                )

                val quickVols = listOf(0, 25, 50, 80, 100)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(quickVols) { pct ->
                        FilterChip(
                            selected = pctInt == pct,
                            onClick = { volumePercent = pct.toFloat() },
                            label = { Text(if (pct == 0) "Mute" else "$pct%", fontSize = 12.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = customCommand,
                    onValueChange = { customCommand = it },
                    label = { Text("Custom Command Override (Optional)") },
                    placeholder = { Text("volume notification $pctInt%") },
                    supportingText = { Text("Leave blank to set notification volume to $pctInt%") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pctInt = volumePercent.roundToInt().coerceIn(0, 100)
                    val radius = (radiusText.toFloatOrNull() ?: 150f).coerceAtLeast(25f)
                    val defaultName = when (triggerType) {
                        TriggerType.WIFI_CONNECT -> "Join ${wifiSsid.ifBlank { "Wi-Fi" }} → $pctInt%"
                        TriggerType.WIFI_DISCONNECT -> "Leave ${wifiSsid.ifBlank { "Wi-Fi" }} → $pctInt%"
                        TriggerType.LOCATION_ENTER -> "Arrive → Notif $pctInt%"
                        TriggerType.LOCATION_EXIT -> "Leave → Notif $pctInt%"
                    }
                    val saved = (initial ?: AutomationRule(name = "")).copy(
                        name = name.trim().ifBlank { defaultName },
                        triggerType = triggerType,
                        wifiSsid = wifiSsid.trim(),
                        latitude = latText.toDoubleOrNull() ?: 0.0,
                        longitude = lngText.toDoubleOrNull() ?: 0.0,
                        radiusMeters = radius,
                        notificationVolumePercent = pctInt,
                        customCommand = customCommand.trim()
                    )
                    onSave(saved)
                },
                enabled = canSave
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
