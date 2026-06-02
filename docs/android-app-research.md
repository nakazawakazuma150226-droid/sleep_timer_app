# Android app research notes

## Existing sleep app patterns

- Sleep Cycle and similar smart alarm apps emphasize smart wake windows, sound/motion based sleep tracking, snore/noise detection, sleep reports, and bedside placement.
- Sleep Cycle documents microphone and accelerometer detection modes, and asks users to keep the app running in the foreground with the device unlocked for sensor-based tracking.
- Sleep as Android documents phone sensors, sonar/microphone style sensing, smart wake windows, and the reality that Android background and battery behavior can affect tracking.

## Technical direction for this app

The current web app already has the differentiating idea: start the timer after the user appears to have fallen asleep. For the Android app, the highest-value native features are reliable sleep detection, local-only personal tuning, and reliable alarm delivery after sleep detection.

Initial native bridge:

1. Keep sleep detection in `sleep_timer.html`.
2. When sleep is detected and `timerEnd` is known, call `window.SleepTimerAndroid.scheduleAlarm(minutes, triggerAtMillis)`.
3. Android schedules the alarm with `AlarmManager`.
4. When the alarm fires, Android starts a foreground service that plays the default alarm sound, vibrates, and shows a high-priority/full-screen alarm notification.

This keeps the privacy-sensitive microphone detection visible and user-initiated, while moving the time-critical alarm delivery to Android.

Native detection bridge:

1. Prefer Android-native sleep detection in the Play Store app.
2. Run detection from a foreground service started by the user's explicit tap.
3. Use `AudioRecord` only as a short-lived audio level meter. The app calculates RMS volume from an in-memory PCM buffer and immediately discards the samples.
4. Use `SensorManager` accelerometer readings for motion level and stillness.
5. Broadcast only aggregate state to the WebView: phase, message, audio level, motion level, progress, and remaining seconds.
6. Keep the existing Web detection path as a fallback for browser builds and for devices where native detection cannot start.

Privacy decisions in code:

- No raw audio is written to disk.
- No raw audio is sent to JavaScript.
- No network API is used by the Android sleep detection service.
- The foreground notification explains that detection is active.
- The service stops after sleep is detected and the native alarm is scheduled.
- Alarm playback uses an Android-native audio player so the Play Store version can sound more polished than the browser fallback.

Local-only tuning:

- `SleepStatsStore` stores placement-specific summary statistics in app-private `SharedPreferences`.
- Stored summaries are limited to session count, fallback count, cancellation count, average detection seconds, average baseline audio, and average baseline motion.
- These summaries tune the next run by slightly adjusting quiet threshold, motion threshold, and minimum detection seconds.
- There is no wake-feeling questionnaire because nap length is user-defined and the user usually cannot know the true sleep onset moment.
- No raw sensor timeline is stored.

## Android constraints to respect

- Exact alarms are appropriate for core alarm/timer apps, but Google Play treats exact alarm permissions as sensitive and declaration-worthy.
- Apps targeting recent Android versions must be careful with background foreground-service starts.
- Microphone access is a while-in-use permission. Starting microphone foreground services from the background is restricted on modern Android, so background sleep detection with the mic should not be the first implementation step.
- Full-screen intents are limited on Android 14+ to apps with calling or alarm functionality, so this app can justify the use case but should keep the feature clearly tied to alarms.

## Implementation priority

1. Android WebView shell and JS bridge.
2. Native alarm scheduling and alarm ringing service.
3. Native sleep detection service with privacy-preserving audio-level analysis.
4. Placement-specific local tuning from summary-only session stats.
5. In-app permission education for microphone, notifications, exact alarms, and full-screen alarm display.
6. Later: Health Connect sleep/wearable integration, alarm history, smart wake window, and richer sound choices.

## Sources

- Sleep Cycle Play listing: https://play.google.com/store/apps/details?id=com.northcube.sleepcycle
- Sleep Cycle phone placement: https://support.sleepcycle.com/hc/en-us/articles/207388465-Phone-Placement-with-the-Sleep-Cycle-App
- Sleep Cycle motion detection: https://support.sleepcycle.com/hc/en-us/articles/10977968179100-How-our-motion-detection-works
- Sleep as Android sensors: https://sleep.urbandroid.org/docs/sleep/sensors.html
- Android AlarmManager: https://developer.android.com/reference/android/app/AlarmManager
- Android foreground service restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android microphone permission: https://developer.android.com/reference/android/Manifest.permission#RECORD_AUDIO
- Android sensors overview: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- Android full-screen intent limits: https://source.android.com/docs/core/permissions/fsi-limits
- Health Connect sleep experiences: https://developer.android.com/health-and-fitness/health-connect/experiences/sleep
