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

    boolean start(String tone) {
        return start(tone, null);
    }

    boolean start(String tone, String alarmUri) {
        stop();

        Uri selectedUri = alarmUri == null || alarmUri.isEmpty() ? null : Uri.parse(alarmUri);
        Uri defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        Uri notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        if (playUri(selectedUri)) return true;
        if (!sameUri(selectedUri, defaultAlarmUri) && playUri(defaultAlarmUri)) return true;
        return !sameUri(defaultAlarmUri, notificationUri) && playUri(notificationUri);
    }

    private boolean playUri(Uri uri) {
        if (uri == null) return false;
        stop();
        try {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(context, uri);
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
            player.setLooping(true);
            player.prepare();
            player.start();
            mediaPlayer = player;
            return true;
        } catch (Exception e) {
            stop();
            return false;
        }
    }

    private static boolean sameUri(Uri first, Uri second) {
        if (first == null || second == null) return false;
        return first.toString().equals(second.toString());
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
