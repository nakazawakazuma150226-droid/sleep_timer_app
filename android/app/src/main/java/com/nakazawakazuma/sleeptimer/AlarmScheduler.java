package com.nakazawakazuma.sleeptimer;

import android.app.AlarmManager;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

final class AlarmScheduler {
    static final int REQUEST_CODE = 150226;
    static final String ACTION_ALARM = "com.nakazawakazuma.sleeptimer.ALARM";
    static final String ACTION_DISMISS = "com.nakazawakazuma.sleeptimer.DISMISS";
    static final String EXTRA_TRIGGER_AT = "triggerAtMillis";
    static final String EXTRA_DURATION_MINUTES = "durationMinutes";
    static final String EXTRA_ALARM_TONE = "alarmTone";
    static final String EXTRA_ALARM_URI = "alarmUri";

    private AlarmScheduler() {}

    static void schedule(Context context, long triggerAtMillis, int durationMinutes) {
        schedule(context, triggerAtMillis, durationMinutes, "system");
    }

    static void schedule(Context context, long triggerAtMillis, int durationMinutes, String alarmTone) {
        schedule(context, triggerAtMillis, durationMinutes, alarmTone, null);
    }

    static void schedule(Context context, long triggerAtMillis, int durationMinutes, String alarmTone, String alarmUri) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        PendingIntent operation = alarmOperation(context, durationMinutes, triggerAtMillis, alarmTone, alarmUri);
        PendingIntent showIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            new Intent(context, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
                if (context instanceof Activity) {
                    requestExactAlarmPermission(context);
                    Toast.makeText(context, "正確なアラーム権限を許可すると、より確実に鳴ります", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(context, "通常アラームとして予約しました", Toast.LENGTH_LONG).show();
                }
                return;
            }

            AlarmManager.AlarmClockInfo info =
                new AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent);
            alarmManager.setAlarmClock(info, operation);
            Toast.makeText(context, "Android側でアラームを予約しました", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
            Toast.makeText(context, "通常アラームとして予約しました", Toast.LENGTH_LONG).show();
        }
    }

    static void cancel(Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        alarmManager.cancel(alarmOperation(context, 0, 0, "system", null));
    }

    private static PendingIntent alarmOperation(Context context, int durationMinutes, long triggerAtMillis, String alarmTone, String alarmUri) {
        Intent intent = new Intent(context, AlarmReceiver.class)
            .setAction(ACTION_ALARM)
            .putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
            .putExtra(EXTRA_TRIGGER_AT, triggerAtMillis)
            .putExtra(EXTRA_ALARM_TONE, alarmTone == null ? "system" : alarmTone)
            .putExtra(EXTRA_ALARM_URI, alarmUri);
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static void requestExactAlarmPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:" + context.getPackageName()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
