package com.example.tasker.runner

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlin.math.roundToInt

enum class VolumeStream(val displayName: String, val androidStreamType: Int) {
    NOTIFICATION("Notification", AudioManager.STREAM_NOTIFICATION),
    RING("Ring", AudioManager.STREAM_RING),
    MEDIA("Media", AudioManager.STREAM_MUSIC),
    ALARM("Alarm", AudioManager.STREAM_ALARM),
    SYSTEM("System", AudioManager.STREAM_SYSTEM)
}

sealed interface VolumeAction {
    data class SetPercent(val percent: Int) : VolumeAction
    data class SetStep(val step: Int) : VolumeAction
    data class AdjustStep(val delta: Int) : VolumeAction
    object Mute : VolumeAction
    object Max : VolumeAction
    object Get : VolumeAction
}

data class VolumeCommandSpec(
    val stream: VolumeStream,
    val action: VolumeAction,
    val showUi: Boolean = false
)

data class VolumeStatus(
    val stream: VolumeStream,
    val current: Int,
    val min: Int,
    val max: Int,
    val percent: Int,
    val dndActive: Boolean
)

object VolumeController {

    private val volumeCmdRegex = Regex(
        """^volume\s+([a-zA-Z0-9_-]+)\s+([a-zA-Z0-9%+-]+)(?:\s+(--ui|--show-ui))?$""",
        RegexOption.IGNORE_CASE
    )

    private val mediaSessionCmdRegex = Regex(
        """^cmd\s+media_session\s+volume\b(.*)$""",
        RegexOption.IGNORE_CASE
    )

    fun isVolumeCommand(command: String): Boolean {
        return parseAll(command).isNotEmpty()
    }

    /**
     * Parses a command string (which may contain multiple subcommands separated by '&&')
     * and returns all recognized volume commands in order.
     */
    fun parseAll(command: String): List<VolumeCommandSpec> {
        return command.split("&&")
            .map { it.trim() }
            .mapNotNull { parseSingle(it) }
    }

    /**
     * Parses a single volume command segment such as:
     *  - "volume notification 50%"
     *  - "volume notif mute"
     *  - "volume notification 4 --ui"
     *  - "volume notification up"
     *  - "volume notification get"
     *  - "cmd media_session volume --stream 5 --set 3"
     */
    fun parseSingle(segment: String): VolumeCommandSpec? {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return null

        val match = volumeCmdRegex.matchEntire(trimmed)
        if (match != null) {
            val stream = parseStreamName(match.groupValues[1]) ?: return null
            val action = parseActionToken(match.groupValues[2]) ?: return null
            val showUi = match.groupValues[3].isNotBlank()
            return VolumeCommandSpec(stream = stream, action = action, showUi = showUi)
        }

        val msMatch = mediaSessionCmdRegex.matchEntire(trimmed)
        if (msMatch != null) {
            val args = msMatch.groupValues[1]
            val streamId = Regex("""--stream\s+(\d+)""").find(args)?.groupValues?.get(1)?.toIntOrNull()
            val stream = when (streamId) {
                null, 5 -> VolumeStream.NOTIFICATION
                2 -> VolumeStream.RING
                3 -> VolumeStream.MEDIA
                4 -> VolumeStream.ALARM
                1 -> VolumeStream.SYSTEM
                else -> return null
            }
            val showUi = args.contains("--show", ignoreCase = true)
            val setVal = Regex("""--set\s+(\d+)""").find(args)?.groupValues?.get(1)?.toIntOrNull()
            val adjVal = Regex("""--adj\s+(raise|lower|same)""", RegexOption.IGNORE_CASE)
                .find(args)?.groupValues?.get(1)?.lowercase()
            val isGet = Regex("""--get\b""").containsMatchIn(args)

            val action = when {
                setVal != null -> VolumeAction.SetStep(setVal)
                adjVal == "raise" -> VolumeAction.AdjustStep(1)
                adjVal == "lower" -> VolumeAction.AdjustStep(-1)
                isGet -> VolumeAction.Get
                else -> return null
            }
            return VolumeCommandSpec(stream = stream, action = action, showUi = showUi)
        }

        return null
    }

    fun parseStreamName(token: String): VolumeStream? {
        return when (token.lowercase()) {
            "notification", "notifications", "notif", "notify" -> VolumeStream.NOTIFICATION
            "ring", "ringer", "ringtone" -> VolumeStream.RING
            "media", "music" -> VolumeStream.MEDIA
            "alarm", "alarms" -> VolumeStream.ALARM
            "system", "sys" -> VolumeStream.SYSTEM
            else -> null
        }
    }

    fun parseActionToken(token: String): VolumeAction? {
        val lower = token.lowercase()
        return when {
            lower == "mute" || lower == "off" || lower == "silent" -> VolumeAction.Mute
            lower == "max" || lower == "full" -> VolumeAction.Max
            lower == "get" || lower == "status" || lower == "show" -> VolumeAction.Get
            lower == "up" || lower == "+" -> VolumeAction.AdjustStep(1)
            lower == "down" || lower == "-" -> VolumeAction.AdjustStep(-1)
            lower.endsWith("%") -> {
                val pct = lower.removeSuffix("%").toIntOrNull() ?: return null
                VolumeAction.SetPercent(pct.coerceIn(0, 100))
            }
            lower.startsWith("+") && lower.length > 1 -> {
                val delta = lower.substring(1).toIntOrNull() ?: return null
                VolumeAction.AdjustStep(delta)
            }
            lower.startsWith("-") && lower.length > 1 -> {
                val delta = lower.substring(1).toIntOrNull() ?: return null
                VolumeAction.AdjustStep(-delta)
            }
            else -> {
                val step = lower.toIntOrNull() ?: return null
                VolumeAction.SetStep(step.coerceAtLeast(0))
            }
        }
    }

    fun resolveTargetStep(action: VolumeAction, current: Int, min: Int, max: Int): Int {
        val safeMin = min.coerceAtLeast(0)
        val safeMax = max.coerceAtLeast(safeMin)
        return when (action) {
            is VolumeAction.SetPercent -> {
                val clampedPct = action.percent.coerceIn(0, 100)
                if (clampedPct == 0) {
                    safeMin
                } else {
                    (safeMin + ((safeMax - safeMin) * clampedPct / 100.0)).roundToInt()
                        .coerceIn(safeMin, safeMax)
                }
            }
            is VolumeAction.SetStep -> action.step.coerceIn(safeMin, safeMax)
            is VolumeAction.AdjustStep -> (current + action.delta).coerceIn(safeMin, safeMax)
            VolumeAction.Mute -> safeMin
            VolumeAction.Max -> safeMax
            VolumeAction.Get -> current
        }
    }

    fun stepToPercent(step: Int, min: Int, max: Int): Int {
        val range = (max - min).coerceAtLeast(1)
        return (((step - min).coerceIn(0, range) * 100.0) / range).roundToInt().coerceIn(0, 100)
    }

    fun getVolumeStatus(context: Context, stream: VolumeStream = VolumeStream.NOTIFICATION): VolumeStatus {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val current = audioManager.getStreamVolume(stream.androidStreamType)
        val max = audioManager.getStreamMaxVolume(stream.androidStreamType)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(stream.androidStreamType)
        } else {
            0
        }
        val dndActive = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        return VolumeStatus(
            stream = stream,
            current = current,
            min = min,
            max = max,
            percent = stepToPercent(current, min, max),
            dndActive = dndActive
        )
    }

    fun executeSpec(context: Context, spec: VolumeCommandSpec): Pair<Boolean, String> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = spec.stream.androidStreamType
        val current = audioManager.getStreamVolume(streamType)
        val max = audioManager.getStreamMaxVolume(streamType)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(streamType)
        } else {
            0
        }

        if (spec.action is VolumeAction.Get) {
            val pct = stepToPercent(current, min, max)
            return true to "${spec.stream.displayName} volume: $current/$max ($pct%)"
        }

        val target = resolveTargetStep(spec.action, current, min, max)
        val flags = if (spec.showUi) AudioManager.FLAG_SHOW_UI else 0

        return try {
            audioManager.setStreamVolume(streamType, target, flags)
            val actual = audioManager.getStreamVolume(streamType)
            val actualPct = stepToPercent(actual, min, max)
            true to "${spec.stream.displayName} volume set to $actual/$max ($actualPct%)"
        } catch (e: SecurityException) {
            false to "Permission denied: Grant 'Do Not Disturb Access' to Tasker in Settings (or in the Auto Rules tab) to mute or change ${spec.stream.displayName.lowercase()} volume during Do Not Disturb."
        } catch (e: Exception) {
            false to "Failed to adjust ${spec.stream.displayName.lowercase()} volume: ${e.localizedMessage ?: e.message}"
        }
    }
}
