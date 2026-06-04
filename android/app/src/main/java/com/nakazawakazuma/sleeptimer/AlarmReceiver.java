package com.nakazawakazuma.sleeptimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent service = new Intent(context, AlarmRingService.class)
            .setAction(AlarmScheduler.ACTION_ALARM)
            .putExtras(intent);

        context.startForegroundService(service);
    }
}
