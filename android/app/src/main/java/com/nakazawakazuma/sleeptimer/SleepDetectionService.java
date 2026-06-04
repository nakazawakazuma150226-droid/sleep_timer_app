package com.nakazawakazuma.sleeptimer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

public class SleepDetectionService extends Service implements SensorEventListener {
    static final String ACTION_START = "com.nakazawakazuma.sleeptimer.DETECTION_START";
    static final String ACTION_STOP = "com.nakazawakazuma.sleeptimer.DETECTION_STOP";
    static final String ACTION_UPDATE = "com.nakazawakazuma.sleeptimer.DETECTION_UPDATE";
    static final String EXTRA_PAYLOAD = "payload";
    static final String EXTRA_DURATION_MINUTES = "durationMinutes";
    static final String EXTRA_SENSITIVITY = "sensitivity";
    static final String EXTRA_PLACEMENT_MODE = "placementMode";
    static final String EXTRA_FALLBACK_ENABLED = "fallbackEnabled";
    static final String EXTRA_ALARM_TONE = "alarmTone";
    static final String EXTRA_ALARM_URI = "alarmUri";

    private static final String CHANNEL_ID = "sleep_timer_detection";
    private static final int NOTIFICATION_ID = 150227;
    private static final int CALIBRATION_SECONDS = 20;
    private static final int MIN_DETECTION_SECONDS = 180;
    private static final int FALLBACK_SECONDS = 30 * 60;
    private static final int SAMPLE_RATE = 8000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Double> audioHistory = new ArrayDeque<>();
    private final ArrayDeque<Double> motionHistory = new ArrayDeque<>();
    private final ArrayList<Double> calibrationAudio = new ArrayList<>();
    private final ArrayList<Double> calibrationMotion = new ArrayList<>();
    private final Runnable monitorTick = this::onMonitorTick;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean recording;
    private volatile double audioLevel;
    private volatile double motionLevel;
    private volatile boolean motionAvailable;
    private float[] lastAcceleration;

    private int durationMinutes;
    private int sensitivity;
    private String placementMode;
    private String alarmTone;
    private String alarmUri;
    private boolean fallbackEnabled;
    private boolean stoppedByUser;
    private boolean calibrating;
    private boolean asleep;
    private int calibrationSeconds;
    private long detectionStartedAt;
    private long lastMotionAt;
    private double baselineAudio;
    private double baselineMotion;
    private double sleepScore;
    private SleepStatsStore statsStore;
    private SleepStatsStore.Profile profile;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stoppedByUser = true;
            if (!asleep && statsStore != null) {
                statsStore.recordCancelledSession(placementMode);
            }
            sendUpdate("stopped", "検知を停止しました", 0, 0, 0, 0, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 30);
        sensitivity = intent.getIntExtra(EXTRA_SENSITIVITY, 3);
        placementMode = intent.getStringExtra(EXTRA_PLACEMENT_MODE);
        if (placementMode == null) placementMode = "bedside";
        alarmTone = intent.getStringExtra(EXTRA_ALARM_TONE);
        if (alarmTone == null) alarmTone = "system";
        alarmUri = intent.getStringExtra(EXTRA_ALARM_URI);
        fallbackEnabled = intent.getBooleanExtra(EXTRA_FALLBACK_ENABLED, true);
        statsStore = new SleepStatsStore(this);
        profile = statsStore.profileFor(placementMode);

        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("睡眠検知中", "音量と端末の動きを端末内だけで解析しています"));
        startDetection();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopDetection();
        super.onDestroy();
    }

    private void startDetection() {
        stopDetection();
        detectionStartedAt = SystemClock.elapsedRealtime();
        lastMotionAt = detectionStartedAt;
        calibrating = true;
        asleep = false;
        stoppedByUser = false;
        calibrationSeconds = 0;
        baselineAudio = 0;
        baselineMotion = 0;
        sleepScore = 0;
        audioHistory.clear();
        motionHistory.clear();
        calibrationAudio.clear();
        calibrationMotion.clear();
        motionAvailable = false;
        lastAcceleration = null;

        startSensors();
        if (!startAudioMeter()) return;
        sendUpdate("calibrating", "環境を学習中...", 0, 0, 0, CALIBRATION_SECONDS, true);
        handler.postDelayed(monitorTick, 1000);
    }

    private void stopDetection() {
        handler.removeCallbacks(monitorTick);
        stopAudioMeter();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    private void startSensors() {
        sensorManager = getSystemService(SensorManager.class);
        if (sensorManager == null) return;
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private boolean startAudioMeter() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            handleDetectionError("マイク許可が必要です");
            return false;
        }

        int minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            handleDetectionError("マイクを開始できませんでした");
            return false;
        }

        int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 2);
        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            );
        } catch (Exception e) {
            handleDetectionError("マイクを開始できませんでした");
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            handleDetectionError("マイクを開始できませんでした");
            return false;
        }

        recording = true;
        audioThread = new Thread(() -> readAudioLoop(bufferSize), "SleepAudioMeter");
        audioThread.start();
        return true;
    }

    private void readAudioLoop(int bufferSize) {
        short[] buffer = new short[bufferSize / 2];
        try {
            audioRecord.startRecording();
            while (recording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read < 0) {
                    throw new IllegalStateException("AudioRecord read failed: " + read);
                }
                if (read == 0) continue;
                double sumSquares = 0;
                for (int i = 0; i < read; i++) {
                    double sample = buffer[i] / 32768.0;
                    sumSquares += sample * sample;
                    buffer[i] = 0;
                }
                audioLevel = Math.sqrt(sumSquares / read) * 100.0;
            }
        } catch (Exception e) {
            handler.post(() -> handleDetectionError("マイク計測を継続できませんでした"));
        }
    }

    private void handleDetectionError(String message) {
        recording = false;
        stopDetection();
        sendUpdate("error", message, 0, 0, 0, 0, false);
        stopSelf();
    }

    private void stopAudioMeter() {
        recording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        audioThread = null;
        audioLevel = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float[] current = new float[] {event.values[0], event.values[1], event.values[2]};
        if (lastAcceleration == null) {
            lastAcceleration = current;
            return;
        }

        double delta = Math.sqrt(
            Math.pow(current[0] - lastAcceleration[0], 2)
                + Math.pow(current[1] - lastAcceleration[1], 2)
                + Math.pow(current[2] - lastAcceleration[2], 2)
        );
        lastAcceleration = current;
        motionAvailable = true;
        pushLimited(motionHistory, delta, 5);
        motionLevel = average(motionHistory);
        if (calibrating) calibrationMotion.add(delta);
        if (motionLevel > motionThreshold()) lastMotionAt = SystemClock.elapsedRealtime();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void onMonitorTick() {
        double audio = audioLevel;
        double motion = motionLevel;

        if (calibrating) {
            calibrationSeconds++;
            calibrationAudio.add(audio);
            if (calibrationSeconds >= CALIBRATION_SECONDS) {
                baselineAudio = median(calibrationAudio);
                baselineMotion = median(calibrationMotion);
                calibrating = false;
                sendUpdate("detecting", "睡眠検知を開始しました", audio, motion, 0, adjustedMinDetectionSeconds(), false);
            } else {
                sendUpdate(
                    "calibrating",
                    "環境を学習中...",
                    audio,
                    motion,
                    calibrationSeconds / (double) CALIBRATION_SECONDS,
                    CALIBRATION_SECONDS - calibrationSeconds,
                    true
                );
            }
            handler.postDelayed(monitorTick, 1000);
            return;
        }

        pushLimited(audioHistory, audio, 30);
        double avgAudio = average(audioHistory);
        long elapsed = (SystemClock.elapsedRealtime() - detectionStartedAt) / 1000;
        long motionAge = (SystemClock.elapsedRealtime() - lastMotionAt) / 1000;
        boolean quiet = avgAudio < quietAudioThreshold();
        boolean motionless = motionAvailable && motionAge > 10 && motion < motionThreshold();
        boolean loudNoise = audio > quietAudioThreshold() * 2.5 || avgAudio > quietAudioThreshold() * 1.8;
        boolean bigMotion = motionAvailable && motion > motionThreshold() * 2.2;

        updateSleepScore(quiet, motionless, loudNoise, bigMotion);
        int targetSeconds = adjustedMinDetectionSeconds();
        int remaining = Math.max(0, targetSeconds - (int) Math.floor(sleepScore));
        String status = sleepStatusLabel(quiet, motionless);
        sendUpdate("detecting", status, audio, motion, sleepScore / targetSeconds, remaining, false);

        if (!asleep && sleepScore >= targetSeconds) {
            onAsleep("睡眠開始と判定しました", false);
            return;
        }
        if (!asleep && fallbackEnabled && elapsed >= FALLBACK_SECONDS) {
            onAsleep("保険設定でタイマーを開始しました", true);
            return;
        }

        handler.postDelayed(monitorTick, 1000);
    }

    private void onAsleep(String message, boolean usedFallback) {
        asleep = true;
        int detectSeconds = (int) ((SystemClock.elapsedRealtime() - detectionStartedAt) / 1000);
        if (statsStore != null) {
            statsStore.recordCompletedSession(
                placementMode,
                detectSeconds,
                usedFallback,
                baselineAudio,
                baselineMotion
            );
        }
        long triggerAt = System.currentTimeMillis() + durationMinutes * 60_000L;
        AlarmScheduler.schedule(this, triggerAt, durationMinutes, alarmTone, alarmUri);
        sendUpdate("asleep", message, audioLevel, motionLevel, 1, 0, false);
        stopSelf();
    }

    private void updateSleepScore(boolean quiet, boolean motionless, boolean loudNoise, boolean bigMotion) {
        double possible = 1.0;
        double positive = quiet ? 1.0 : 0.0;

        if (motionAvailable) {
            double weight = "bed".equals(placementMode) ? 0.85 : 0.25;
            possible += weight;
            if (motionless) positive += weight;
        }

        double confidence = positive / possible;
        double delta = confidence >= 0.9 ? 1.0 : confidence >= 0.55 ? 0.35 : -1.2;
        if (loudNoise) delta -= 2.5;
        if (bigMotion) delta -= "bed".equals(placementMode) ? 5.0 : 1.5;
        int targetSeconds = adjustedMinDetectionSeconds();
        sleepScore = Math.max(0, Math.min(targetSeconds, sleepScore + delta));
    }

    private double quietAudioThreshold() {
        double[] ratios = new double[] {0, 1.05, 0.95, 0.85, 0.75, 0.65};
        double quietRatio = sensitivity >= 1 && sensitivity <= 5 ? ratios[sensitivity] : 0.85;
        double absoluteQuiet = 1.2 + (6 - sensitivity) * 0.55;
        double learnedMultiplier = profile == null ? 1.0 : profile.quietThresholdMultiplier();
        return Math.max(absoluteQuiet, baselineAudio * quietRatio * learnedMultiplier);
    }

    private double motionThreshold() {
        double base = Math.max(0.12, baselineMotion * (1.8 - sensitivity * 0.14));
        double absolute = 1.15 - sensitivity * 0.14;
        double learnedMultiplier = profile == null ? 1.0 : profile.motionThresholdMultiplier();
        return Math.max(base, absolute) * learnedMultiplier;
    }

    private int adjustedMinDetectionSeconds() {
        int offset = profile == null ? 0 : profile.minDetectionOffsetSeconds;
        return Math.max(120, Math.min(240, MIN_DETECTION_SECONDS + offset));
    }

    private String sleepStatusLabel(boolean quiet, boolean motionless) {
        int targetSeconds = adjustedMinDetectionSeconds();
        if (sleepScore >= targetSeconds * 0.66) return "入眠候補";
        if (sleepScore >= targetSeconds * 0.33) return "落ち着き中";
        if (quiet && !motionless) return "動きを確認中...";
        if (motionless && !quiet) return "音を確認中...";
        return "睡眠検知中...";
    }

    private void sendUpdate(
        String phase,
        String message,
        double audio,
        double motion,
        double progress,
        int remainingSeconds,
        boolean calibratingState
    ) {
        String payload = String.format(
            Locale.US,
            "{\"phase\":\"%s\",\"message\":\"%s\",\"audio\":%.3f,\"motion\":%.3f,\"progress\":%.3f,\"remainingSeconds\":%d,\"calibrating\":%s,\"privacy\":\"音量と動きの数値だけを端末内で解析しています\"}",
            escapeJson(phase),
            escapeJson(message),
            audio,
            motion,
            Math.max(0, Math.min(1, progress)),
            remainingSeconds,
            calibratingState ? "true" : "false"
        );
        Intent intent = new Intent(ACTION_UPDATE)
            .setPackage(getPackageName())
            .putExtra(EXTRA_PAYLOAD, payload);
        sendBroadcast(intent);
    }

    private Notification buildNotification(String title, String text) {
        PendingIntent openApp = PendingIntent.getActivity(
            this,
            3,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stop = PendingIntent.getService(
            this,
            4,
            new Intent(this, SleepDetectionService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "睡眠検知",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("睡眠検知中であることを明示します。録音・保存・送信は行いません。");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private static void pushLimited(ArrayDeque<Double> deque, double value, int limit) {
        deque.addLast(value);
        while (deque.size() > limit) deque.removeFirst();
    }

    private static double average(ArrayDeque<Double> values) {
        if (values.isEmpty()) return 0;
        double sum = 0;
        for (double value : values) sum += value;
        return sum / values.size();
    }

    private static double median(ArrayList<Double> values) {
        if (values.isEmpty()) return 0;
        ArrayList<Double> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy.get(copy.size() / 2);
    }

    private static String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }
}
