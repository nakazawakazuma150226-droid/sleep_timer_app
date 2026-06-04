package com.nakazawakazuma.sleeptimer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 100;
    private static final int REQUEST_NOTIFICATIONS = 101;
    private static final String PREFS = "sleep_timer_settings";
    private static final String KEY_ALARM_URI = "alarmUri";
    private static final String KEY_ALARM_TITLE = "alarmTitle";
    private static final String KEY_ALARM_CHALLENGE_ENABLED = "alarmChallengeEnabled";
    private static final String DEFAULT_ALARM_TITLE = "端末のアラーム音";
    private PermissionRequest pendingWebPermission;
    private WebView webView;
    private NativeAlarmPlayer previewAlarmPlayer;
    private final Runnable stopPreviewAlarmRunnable = this::stopPreviewAlarm;
    private final BroadcastReceiver sleepDetectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (webView == null) return;
            String payload = intent.getStringExtra(SleepDetectionService.EXTRA_PAYLOAD);
            if (payload == null) return;
            webView.post(() -> webView.evaluateJavascript(
                "window.onNativeDetectionUpdate && window.onNativeDetectionUpdate(" + payload + ")",
                null
            ));
        }
    };

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(13, 17, 23));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingWebPermission = request;
                    requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_RECORD_AUDIO);
                    return;
                }
                grantWebPermission(request);
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "SleepTimerAndroid");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/sleep_timer.html");
        webView.postDelayed(this::sendSavedAlarmTitleToWeb, 600);
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerSleepDetectionReceiver();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerSleepDetectionReceiver() {
        IntentFilter filter = new IntentFilter(SleepDetectionService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sleepDetectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sleepDetectionReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        stopPreviewAlarm();
        try {
            unregisterReceiver(sleepDetectionReceiver);
        } catch (IllegalArgumentException ignored) {}
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sendPermissionStatusToWeb();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO && pendingWebPermission != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                grantWebPermission(pendingWebPermission);
            } else {
                pendingWebPermission.deny();
                Toast.makeText(this, "マイク許可がないため検知精度が下がります", Toast.LENGTH_LONG).show();
            }
            pendingWebPermission = null;
        }
        sendPermissionStatusToWeb();
    }

    private void grantWebPermission(PermissionRequest request) {
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                request.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                return;
            }
        }
        request.deny();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQUEST_NOTIFICATIONS);
        }
    }

    private void sendNativeDetectionError(String message) {
        if (webView == null) return;
        String escaped = message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
        webView.evaluateJavascript(
            "window.onNativeDetectionUpdate && window.onNativeDetectionUpdate({phase:\"error\",message:\"" + escaped + "\"})",
            null
        );
    }

    public class NativeBridge {
        @JavascriptInterface
        public boolean isNativeDetectionAvailable() {
            return true;
        }

        @JavascriptInterface
        public void startSleepDetection(int minutes, int sensitivity, String placementMode, boolean fallbackEnabled, String alarmTone) {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_RECORD_AUDIO);
                    Toast.makeText(MainActivity.this, "睡眠検知にはマイク許可が必要です", Toast.LENGTH_LONG).show();
                    sendNativeDetectionError("Android検知にはマイク許可が必要です");
                    return;
                }
                Intent intent = new Intent(MainActivity.this, SleepDetectionService.class)
                    .setAction(SleepDetectionService.ACTION_START)
                    .putExtra(SleepDetectionService.EXTRA_DURATION_MINUTES, minutes)
                    .putExtra(SleepDetectionService.EXTRA_SENSITIVITY, sensitivity)
                    .putExtra(SleepDetectionService.EXTRA_PLACEMENT_MODE, placementMode)
                    .putExtra(SleepDetectionService.EXTRA_FALLBACK_ENABLED, fallbackEnabled)
                    .putExtra(SleepDetectionService.EXTRA_ALARM_TONE, alarmTone == null ? "system" : alarmTone)
                    .putExtra(SleepDetectionService.EXTRA_ALARM_URI, selectedAlarmUriFor(alarmTone));
                startForegroundService(intent);
            });
        }

        @JavascriptInterface
        public void stopSleepDetection() {
            runOnUiThread(() -> startService(
                new Intent(MainActivity.this, SleepDetectionService.class)
                    .setAction(SleepDetectionService.ACTION_STOP)
            ));
        }

        @JavascriptInterface
        public void scheduleAlarm(int minutes, double triggerAtMillis, String alarmTone) {
            long trigger = (long) triggerAtMillis;
            runOnUiThread(() -> AlarmScheduler.schedule(
                MainActivity.this,
                trigger,
                minutes,
                alarmTone,
                selectedAlarmUriFor(alarmTone)
            ));
        }

        @JavascriptInterface
        public void cancelAlarm() {
            runOnUiThread(() -> {
                AlarmScheduler.cancel(MainActivity.this);
                Toast.makeText(MainActivity.this, "Android側のアラームを解除しました", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void testAlarm(String alarmTone) {
            runOnUiThread(() -> {
                stopPreviewAlarm();
                previewAlarmPlayer = new NativeAlarmPlayer(MainActivity.this);
                boolean started = previewAlarmPlayer.start(alarmTone == null ? "system" : alarmTone, selectedAlarmUriFor(alarmTone));
                if (!started) {
                    previewAlarmPlayer = null;
                    Toast.makeText(MainActivity.this, "アラーム音を再生できませんでした", Toast.LENGTH_LONG).show();
                    return;
                }
                webView.postDelayed(stopPreviewAlarmRunnable, 5200);
            });
        }

        @JavascriptInterface
        public void chooseSystemAlarmTone() {
            runOnUiThread(MainActivity.this::showAlarmToneDialog);
        }

        @JavascriptInterface
        public void setAlarmChallengeEnabled(boolean enabled) {
            settings().edit()
                .putBoolean(KEY_ALARM_CHALLENGE_ENABLED, enabled)
                .apply();
        }

        @JavascriptInterface
        public boolean isAlarmChallengeEnabled() {
            return settings().getBoolean(KEY_ALARM_CHALLENGE_ENABLED, false);
        }

        @JavascriptInterface
        public String getPermissionStatus() {
            return permissionStatusJson();
        }

        @JavascriptInterface
        public void requestPermission(String key) {
            runOnUiThread(() -> requestPermissionFromWeb(key));
        }

        @JavascriptInterface
        public void stopAlarm() {
            runOnUiThread(() -> {
                stopPreviewAlarm();
                startService(
                    new Intent(MainActivity.this, AlarmRingService.class)
                        .setAction(AlarmScheduler.ACTION_DISMISS)
                );
            });
        }

    }

    private void stopPreviewAlarm() {
        if (webView != null) {
            webView.removeCallbacks(stopPreviewAlarmRunnable);
        }
        if (previewAlarmPlayer == null) return;
        try {
            previewAlarmPlayer.stop();
        } catch (Exception ignored) {}
        previewAlarmPlayer = null;
    }

    private SharedPreferences settings() {
        return getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void requestPermissionFromWeb(String key) {
        if ("microphone".equals(key)) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_RECORD_AUDIO);
            return;
        }
        if ("notifications".equals(key)) {
            requestNotificationPermission();
            return;
        }
        if ("exactAlarm".equals(key)) {
            openExactAlarmSettings();
            return;
        }
        if ("fullScreenIntent".equals(key)) {
            openFullScreenIntentSettings();
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void sendPermissionStatusToWeb() {
        if (webView == null) return;
        webView.evaluateJavascript(
            "window.onNativePermissionStatus && window.onNativePermissionStatus(" + permissionStatusJson() + ")",
            null
        );
    }

    private String permissionStatusJson() {
        return "{"
            + "\"native\":true,"
            + "\"microphone\":" + hasMicrophonePermission() + ","
            + "\"notifications\":" + hasNotificationPermission() + ","
            + "\"exactAlarm\":" + hasExactAlarmPermission() + ","
            + "\"fullScreenIntent\":" + hasFullScreenIntentPermission()
            + "}";
    }

    private boolean hasMicrophonePermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasExactAlarmPermission() {
        AlarmManager alarmManager = getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            || alarmManager == null
            || alarmManager.canScheduleExactAlarms();
    }

    private boolean hasFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true;
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        return notificationManager == null || notificationManager.canUseFullScreenIntent();
    }

    private void showAlarmToneDialog() {
        ArrayList<AlarmToneOption> options = alarmToneOptions();
        if (options.isEmpty()) {
            Toast.makeText(this, "選択できるアラーム音がありません", Toast.LENGTH_LONG).show();
            return;
        }

        String[] labels = new String[options.size()];
        Uri selected = selectedAlarmUri();
        int checked = 0;
        for (int i = 0; i < options.size(); i++) {
            AlarmToneOption option = options.get(i);
            labels[i] = option.title;
            if (sameUri(option.uri, selected)) checked = i;
        }

        new AlertDialog.Builder(this)
            .setTitle("アラーム音を選ぶ")
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                saveSelectedAlarmTone(options.get(which));
                dialog.dismiss();
            })
            .setNegativeButton("キャンセル", null)
            .show();
    }

    private ArrayList<AlarmToneOption> alarmToneOptions() {
        ArrayList<AlarmToneOption> options = new ArrayList<>();
        Uri defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (defaultUri != null) {
            options.add(new AlarmToneOption(DEFAULT_ALARM_TITLE, defaultUri));
        }

        RingtoneManager manager = new RingtoneManager(this);
        manager.setType(RingtoneManager.TYPE_ALARM);
        Cursor cursor = manager.getCursor();
        while (cursor != null && cursor.moveToNext()) {
            Uri uri = manager.getRingtoneUri(cursor.getPosition());
            if (uri == null || sameUri(uri, defaultUri)) continue;

            String title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX);
            if (title == null || title.trim().isEmpty()) {
                title = ringtoneTitle(uri);
            }
            options.add(new AlarmToneOption(title, uri));
        }
        return options;
    }

    private void saveSelectedAlarmTone(AlarmToneOption option) {
        settings().edit()
            .putString(KEY_ALARM_URI, option.uri.toString())
            .putString(KEY_ALARM_TITLE, option.title)
            .apply();
        if (webView != null) {
            webView.evaluateJavascript(
                "window.onSystemAlarmToneSelected && window.onSystemAlarmToneSelected(" + jsString(option.title) + ")",
                null
            );
        }
    }

    private String selectedAlarmUriFor(String alarmTone) {
        Uri uri = selectedAlarmUri();
        return uri == null ? null : uri.toString();
    }

    private Uri selectedAlarmUri() {
        String saved = settings().getString(KEY_ALARM_URI, null);
        if (saved != null) return Uri.parse(saved);
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    }

    private String ringtoneTitle(Uri uri) {
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(this, uri);
            return ringtone == null ? DEFAULT_ALARM_TITLE : ringtone.getTitle(this);
        } catch (Exception e) {
            return DEFAULT_ALARM_TITLE;
        }
    }

    private void sendSavedAlarmTitleToWeb() {
        if (webView == null) return;
        String title = settings().getString(KEY_ALARM_TITLE, null);
        if (title == null) {
            Uri uri = selectedAlarmUri();
            title = ringtoneTitle(uri);
        }
        webView.evaluateJavascript(
            "window.onSystemAlarmToneTitle && window.onSystemAlarmToneTitle(" + jsString(title) + ")",
            null
        );
    }

    private static String jsString(String value) {
        String escaped = value == null ? "" : value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }

    private static boolean sameUri(Uri first, Uri second) {
        if (first == null || second == null) return false;
        return first.toString().equals(second.toString());
    }

    private static final class AlarmToneOption {
        final String title;
        final Uri uri;

        AlarmToneOption(String title, Uri uri) {
            this.title = title;
            this.uri = uri;
        }
    }
}
