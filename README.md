# Tasker - Android Shell Command Shortcut App

**Tasker** is an Android application that allows users to define custom shell commands, test them with live console output, and pin 1-tap shortcuts to the Android home screen.

## Features

- **Custom Shell Commands**: Define shell commands (e.g. `wm density`, `uptime`, `df -h`, `date`).
- **Quick Presets**: Preloaded with `510 dp`, `550 dp`, `Reset Density`, and storage info.
- **Instant Display Density Switching**: Seamlessly changes smallest width / display density via Android WindowManager and `WRITE_SECURE_SETTINGS`.
- **Home Screen Shortcuts**: Pin 1-tap launcher shortcuts using `ShortcutManagerCompat`.
- **Auto-Exit on Success**: When tapping a home screen shortcut / widget, the command runs and automatically exits without requiring a tap on the Close button. If an error occurs, it remains open with full error diagnostics.
- **Execution Modes**:
  - **Output Window**: Shows a floating terminal card with live execution status, `stdout`, `stderr`, exit code, duration, and clipboard copy.
  - **Silent / Toast**: Runs the command in the background, displays a brief result Toast, and finishes immediately.
- **Root & Non-Root**: Standard shell execution via `/system/bin/sh` or optional Root (`su`).

## Permissions

For display density commands (`wm density`):
```sh
adb shell pm grant com.example.tasker android.permission.WRITE_SECURE_SETTINGS
```

## Build & Install

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
