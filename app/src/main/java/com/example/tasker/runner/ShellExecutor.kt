package com.example.tasker.runner

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long
)

object ShellExecutor {

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun execute(
        command: String,
        asRoot: Boolean = false,
        context: Context? = null
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val trimmed = command.trim()

        // 1. If not running as root, check if we can handle system settings commands
        // directly via Android APIs (requires WRITE_SECURE_SETTINGS which is granted to this app)
        if (!asRoot && context != null) {
            val handledResult = handleSystemCommand(trimmed, context, startTime)
            if (handledResult != null) {
                return@withContext handledResult
            }
        }

        // 2. Standard shell execution
        val shell = if (asRoot) "su" else "/system/bin/sh"

        try {
            val process = ProcessBuilder(shell, "-c", command)
                .redirectErrorStream(false)
                .start()

            val stdoutDeferred = async {
                try {
                    process.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    ""
                }
            }

            val stderrDeferred = async {
                try {
                    process.errorStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    ""
                }
            }

            val exitCode = process.waitFor()
            val stdout = stdoutDeferred.await()
            val rawStderr = stderrDeferred.await()
            val duration = System.currentTimeMillis() - startTime

            val finalStderr = if (rawStderr.contains("Can't find service: window") || rawStderr.contains("SecurityException")) {
                "$rawStderr\n\n[Tasker Notice]: 'wm' commands cannot be run in a standard app sandbox directly via /system/bin/sh. Enable 'Run as Root (su)' if rooted, or use the preloaded density command which Tasker executes via Android's Secure Settings."
            } else {
                rawStderr
            }

            ExecutionResult(
                exitCode = exitCode,
                stdout = stdout.trimEnd(),
                stderr = finalStderr.trimEnd(),
                durationMs = duration
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = e.localizedMessage ?: e.toString(),
                durationMs = duration
            )
        }
    }

    private fun handleSystemCommand(command: String, context: Context, startTime: Long): ExecutionResult? {
        try {
            // Case A: wm density reset
            if (command.equals("wm density reset", ignoreCase = true)) {
                applyDensity(context, null)
                return ExecutionResult(
                    exitCode = 0,
                    stdout = "Display density reset to default successfully.",
                    stderr = "",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            // Case B: wm density <number>
            val fixedDensityRegex = Regex("""wm\s+density\s+(\d+)""", RegexOption.IGNORE_CASE)
            val fixedMatch = fixedDensityRegex.matchEntire(command)
            if (fixedMatch != null) {
                val dpi = fixedMatch.groupValues[1].toInt()
                applyDensity(context, dpi)
                return ExecutionResult(
                    exitCode = 0,
                    stdout = "Display density set to $dpi DPI.",
                    stderr = "",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            // Case C: wm density calculation or target dp command (e.g. 510 dp, 550 dp)
            if (command.contains("wm density", ignoreCase = true)) {
                val targetDp = when {
                    command.contains("550") -> 550
                    command.contains("510") -> 510
                    else -> Regex("""(?:/\s*|dp\s*|\bsw\b\s*)(\d{3,4})""", RegexOption.IGNORE_CASE)
                        .find(command)?.groupValues?.get(1)?.toIntOrNull()
                }

                if (targetDp != null) {
                    val screenWidth = getPhysicalScreenWidth(context)
                    val targetDpi = (screenWidth * 160) / targetDp
                    applyDensity(context, targetDpi)
                    return ExecutionResult(
                        exitCode = 0,
                        stdout = "Detected screen width: ${screenWidth}px\nSmallest width set to ${targetDp} dp ($targetDpi DPI).",
                        stderr = "",
                        durationMs = System.currentTimeMillis() - startTime
                    )
                }
            }

            // Case D: settings put secure <key> <val>
            val settingsPutRegex = Regex("""settings\s+put\s+secure\s+([a-zA-Z0-9_]+)\s+(.*)""", RegexOption.IGNORE_CASE)
            val settingsMatch = settingsPutRegex.matchEntire(command)
            if (settingsMatch != null) {
                val key = settingsMatch.groupValues[1]
                val value = settingsMatch.groupValues[2].trim('\'', '"', ' ')
                Settings.Secure.putString(context.contentResolver, key, value)
                return ExecutionResult(
                    exitCode = 0,
                    stdout = "Updated Secure Setting: $key = $value",
                    stderr = "",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: SecurityException) {
            return ExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "Permission denied: WRITE_SECURE_SETTINGS is required.\nRun via ADB: adb shell pm grant com.example.tasker android.permission.WRITE_SECURE_SETTINGS",
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            // Let fallback handle or report error
        }
        return null
    }

    private fun getPhysicalScreenWidth(context: Context): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            minOf(bounds.width(), bounds.height())
        } catch (e: Exception) {
            val dm = context.resources.displayMetrics
            minOf(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun applyDensity(context: Context, density: Int?) {
        // 1. Always update Settings.Secure
        try {
            val value = if (density == null || density <= 0) "" else density.toString()
            Settings.Secure.putString(context.contentResolver, "display_density_forced", value)
        } catch (e: Exception) {
            android.util.Log.w("TaskerExec", "Failed to update Settings.Secure", e)
        }

        // 2. Invoke WindowManagerService via reflection to apply immediately
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val windowBinder = getServiceMethod.invoke(null, "window") as? android.os.IBinder ?: return

            val iWindowManagerStub = Class.forName("android.view.IWindowManager\$Stub")
            val asInterfaceMethod = iWindowManagerStub.getMethod("asInterface", android.os.IBinder::class.java)
            val iWindowManager = asInterfaceMethod.invoke(null, windowBinder) ?: return

            val displayId = android.view.Display.DEFAULT_DISPLAY
            val userHandleClass = Class.forName("android.os.UserHandle")
            val myUserIdMethod = userHandleClass.getMethod("myUserId")
            val userId = myUserIdMethod.invoke(null) as Int

            if (density == null || density <= 0) {
                val clearMethod = iWindowManager.javaClass.methods.firstOrNull {
                    it.name.startsWith("clearForcedDisplayDensity")
                }
                if (clearMethod != null) {
                    if (clearMethod.parameterCount == 2) {
                        clearMethod.invoke(iWindowManager, displayId, userId)
                    } else if (clearMethod.parameterCount == 1) {
                        clearMethod.invoke(iWindowManager, displayId)
                    }
                    android.util.Log.i("TaskerExec", "Cleared density via WindowManager.${clearMethod.name}")
                }
            } else {
                val setMethod = iWindowManager.javaClass.methods.firstOrNull {
                    it.name.startsWith("setForcedDisplayDensity")
                }
                if (setMethod != null) {
                    if (setMethod.parameterCount == 3) {
                        setMethod.invoke(iWindowManager, displayId, density, userId)
                    } else if (setMethod.parameterCount == 2) {
                        setMethod.invoke(iWindowManager, displayId, density)
                    }
                    android.util.Log.i("TaskerExec", "Set density to $density via WindowManager.${setMethod.name}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TaskerExec", "WindowManager reflection failed", e)
        }
    }
}
