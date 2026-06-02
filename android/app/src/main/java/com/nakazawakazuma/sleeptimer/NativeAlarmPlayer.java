package com.nakazawakazuma.sleeptimer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

final class NativeAlarmPlayer {
    private final Context context;
    private MediaPlayer mediaPlayer;

    NativeAlarmPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    void start(String tone) {
        start(tone, null);
    }

    void start(String tone, String alarmUri) {
        stop();
        try {
            Uri uri = alarmUri == null || alarmUri.isEmpty()
                ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                : Uri.parse(alarmUri);
            if (uri == null) return;

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(context, uri);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            stop();
        }
    }

    void stop() {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.stop();
        } catch (Exception ignored) {
        }
        try {
            mediaPlayer.release();
        } catch (Exception ignored) {
        }
        mediaPlayer = null;
    }
}
