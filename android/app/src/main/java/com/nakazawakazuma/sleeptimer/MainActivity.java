package com.nakazawakazuma.sleeptimer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 100;
    private static final int REQUEST_NOTIFICATIONS = 101;
    private static final int REQUEST_ALARM_TONE = 102;
    private static final String PREFS = "sleep_timer_settings";
    private static final String KEY_ALARM_URI = "alarmUri";
    private static final String KEY_ALARM_TITLE = "alarmTitle";
    private PermissionRequest pendingWebPermission;
    private WebView webView;
    private NativeAlarmPlayer previewAlarmPlayer;
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ALARM_TONE || resultCode != RESULT_OK || data == null) return;

        Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (uri == null) return;
        String title = ringtoneTitle(uri);
        settings().edit()
            .putString(KEY_ALARM_URI, uri.toString())
            .putString(KEY_ALARM_TITLE, title)
            .apply();
        if (webView != null) {
            webView.evaluateJavascript(
                "window.onSystemAlarmToneSelected && window.onSystemAlarmToneSelected(" + jsString(title) + ")",
                null
            );
        }
    }

    private void grantWebPermission(PermissionRequest request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            request.grant(request.getResources());
        }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
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
                previewAlarmPlayer.start(alarmTone == null ? "system" : alarmTone, selectedAlarmUriFor(alarmTone));
                webView.postDelayed(MainActivity.this::stopPreviewAlarm, 5200);
            });
        }

        @JavascriptInterface
        public void chooseSystemAlarmTone() {
            runOnUiThread(() -> {
                Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedAlarmUri());
                startActivityForResult(intent, REQUEST_ALARM_TONE);
            });
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
        if (previewAlarmPlayer == null) return;
        try {
            previewAlarmPlayer.stop();
        } catch (Exception ignored) {}
        previewAlarmPlayer = null;
    }

    private SharedPreferences settings() {
        return getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
            return ringtone == null ? "端末のアラーム音" : ringtone.getTitle(this);
        } catch (Exception e) {
            return "端末のアラーム音";
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
}
