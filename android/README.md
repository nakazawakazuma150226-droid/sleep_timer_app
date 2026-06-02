# Android app

This folder contains the Google Play version scaffold.

## Current architecture

- `sleep_timer.html` remains the main UI and keeps browser-based sleep detection as a fallback.
- `MainActivity` loads the web app in an Android `WebView`.
- `SleepTimerAndroid.scheduleAlarm(minutes, triggerAtMillis)` is exposed to JavaScript.
- When the web app detects sleep and calculates the alarm time, Android schedules a native alarm.
- When the native alarm fires, `AlarmRingService` plays the selected device alarm tone and shows an alarm notification with a full-screen intent.
- `NativeAlarmPlayer` uses Android's alarm ringtone URI through `MediaPlayer`; the app does not ship custom generated alarm tones.
- `SleepTimerAndroid.startSleepDetection(...)` starts native sleep detection from an explicit user action.
- `SleepDetectionService` calculates only audio RMS level and accelerometer motion level on-device. It does not save, transmit, or expose raw audio.
- `SleepStatsStore` stores only summary statistics in app-private `SharedPreferences` and keeps separate profiles for bedside and bed placement.
- The web app keeps its original browser-based detection as a fallback.

## Open in Android Studio

1. Open the repository root as a Gradle project.
2. Let Android Studio sync Gradle.
3. Run the `android:app` module on a device.

The Gradle task `syncWebAssets` copies these root files into Android assets at build time:

- `sleep_timer.html`
- `manifest.json`
- `service-worker.js`
- `icon-192.png`
- `icon-512.png`

## Permissions to test

- Microphone permission for the WebView sleep detection.
- Foreground service microphone behavior while detection is running.
- Notification permission on Android 13+.
- Exact alarm permission on Android 12+ if the device requires it.
- Full-screen alarm display on Android 14+.
- Native alarm tone preview from the app settings screen.

## Privacy behavior to verify

- Detection starts only after the user taps the start button.
- A persistent foreground notification is visible during native detection.
- No recording files are created.
- No network request is needed for sleep detection.
- Only summary stats are saved locally: counts, average detection time, average baseline audio, and average baseline motion.
- Detection stops once sleep is detected or the user cancels.

## Play Console notes

The app's core user-facing feature is a sleep timer/alarm. If exact alarm permission is used in the final build, the Play Console declaration should describe that the app must wake the user at the selected alarm time after sleep detection.
