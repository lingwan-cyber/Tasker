# Tasker - Android Shell Command Shortcut App

**Tasker** is an Android application that allows users to define custom shell commands, test them with live console output, and pin 1-tap shortcuts to the Android home screen.

## Features

- **Custom Shell Commands**: Define shell commands (e.g. `wm density`, `df -h`, `date`).
- **Notification Volume Skill**: Native Android `AudioManager` volume control (`volume notification 0%`, `volume notification 30%`, `volume notification 80%`, `mute`, `max`, `up`, `down`, `get`, or `cmd media_session volume --stream 5 --set <n>`) with dedicated volume shortcut icons and 1-tap home screen shortcuts.
- **Location & Wi-Fi Background Auto Rules**: Automatically adjust notification volume (or run a custom command) in the background when joining/leaving a Wi-Fi network (SSID) or entering/exiting a geographic area (geofence), complete with "Use Current Wi-Fi", "Use Current Location", boot persistence, and an in-app permissions checklist.
- **Quick Presets**: Preloaded with `434 dp` (Default native & 1.0 font), `434 dp (Font 1.15)` (Native dp & large 1.15 font), `511 dp` (Small display & font), `550 dp` (550 dp smallest width & small font), `Reset Density`, storage info, and Notification Volume presets (`Mute 0%`, `Low 30%`, `High 80%`).
- **Instant Display Density Switching**: Seamlessly changes smallest width / display density via Android WindowManager and `WRITE_SECURE_SETTINGS`.
- **Duplicate Command**: 1-tap duplication button to clone any command, preserving all options and parameters.
- **Home Screen Shortcuts**: Pin 1-tap launcher shortcuts using `ShortcutManagerCompat`.
- **Auto-Exit on Success**: When tapping a home screen shortcut / widget, the command runs and automatically exits without requiring a tap on the Close button. If an error occurs, it remains open with full diagnostics.
- **Configurable Auto-Close Delay**: Configure custom delay in ms (defaults to `0 ms` for instant exit; supports any value and quick preset chips: 300ms, 500ms, 1s, 2s).
- **Execution Modes**:
  - **Output Window**: Shows a floating terminal card with live execution status, `stdout`, `stderr`, exit code, duration, and clipboard copy.
  - **Silent / Toast**: Runs the command in the background, displays a brief result Toast, and finishes immediately.
- **Root & Non-Root**: Standard shell execution via `/system/bin/sh` or optional Root (`su`).

## Permissions

For display density commands (`wm density`):
```sh
adb shell pm grant com.example.tasker android.permission.WRITE_SECURE_SETTINGS
```

For Notification Volume muting & Wi-Fi/Location background automation (all grantable directly from the **Auto Rules** tab in-app — no ADB required):
- **Location (Fine)** & **Background Location ("Allow all the time")**: Required for geofence triggers and reading Wi-Fi SSID in the background.
- **Do Not Disturb Access**: Required when muting (`0%`) or changing volume while Do Not Disturb is active.
- **Notifications (Android 13+)**: Shows the quiet ongoing status notification while background monitoring is active.

## Build & Install

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
