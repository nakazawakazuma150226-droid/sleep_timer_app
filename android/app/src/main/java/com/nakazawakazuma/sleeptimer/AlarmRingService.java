package com.nakazawakazuma.sleeptimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class AlarmRingService extends Service {
    private static final String CHANNEL_ID = "sleep_timer_alarm";
    private static final int NOTIFICATION_ID = 150226;
    private NativeAlarmPlayer player;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && AlarmScheduler.ACTION_DISMISS.equals(intent.getAction())) {
            stopAlarm();
            return START_NOT_STICKY;
        }

        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        String tone = intent == null ? "system" : intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_TONE);
        if (tone == null) tone = "system";
        String alarmUri = intent == null ? null : intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_URI);
        startSound(tone, alarmUri);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopAlarm();
        super.onDestroy();
    }

    private Notification buildNotification() {
        PendingIntent openAlarm = PendingIntent.getActivity(
            this,
            1,
            new Intent(this, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent dismiss = PendingIntent.getService(
            this,
            2,
            new Intent(this, AlarmRingService.class).setAction(AlarmScheduler.ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);

        builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("起きる時間です")
            .setContentText("睡眠タイマーのアラームが鳴っています")
            .setContentIntent(openAlarm)
            .setFullScreenIntent(openAlarm, true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .addAction(android.R.drawable.ic_media_pause, "止める", dismiss);

        return builder.build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "睡眠タイマー アラーム",
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("睡眠タイマーのアラーム通知");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startSound(String tone, String alarmUri) {
        if (player != null) return;
        player = new NativeAlarmPlayer(this);
        if (!player.start(tone, alarmUri)) {
            player = null;
        }
    }

    private void stopAlarm() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {}
            player = null;
        }
        stopForeground(true);
        stopSelf();
    }
}
