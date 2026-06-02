package com.nakazawakazuma.sleeptimer;

import android.content.Context;
import android.content.SharedPreferences;

final class SleepStatsStore {
    private static final String PREFS = "sleep_detection_stats";
    private static final int MIN_SESSIONS_FOR_ADJUSTMENT = 3;

    static final class Profile {
        final int sessions;
        final int fallbackCount;
        final int cancelledCount;
        final double avgDetectSeconds;
        final double avgBaselineAudio;
        final double avgBaselineMotion;
        final double strictnessAdjustment;
        final int minDetectionOffsetSeconds;

        Profile(
            int sessions,
            int fallbackCount,
            int cancelledCount,
            double avgDetectSeconds,
            double avgBaselineAudio,
            double avgBaselineMotion
        ) {
            this.sessions = sessions;
            this.fallbackCount = fallbackCount;
            this.cancelledCount = cancelledCount;
            this.avgDetectSeconds = avgDetectSeconds;
            this.avgBaselineAudio = avgBaselineAudio;
            this.avgBaselineMotion = avgBaselineMotion;
            this.strictnessAdjustment = calculateStrictnessAdjustment();
            this.minDetectionOffsetSeconds = calculateMinDetectionOffsetSeconds();
        }

        double quietThresholdMultiplier() {
            return clamp(1.0 + strictnessAdjustment * 0.08, 0.90, 1.12);
        }

        double motionThresholdMultiplier() {
            return clamp(1.0 + strictnessAdjustment * 0.10, 0.88, 1.15);
        }

        private double calculateStrictnessAdjustment() {
            if (sessions < MIN_SESSIONS_FOR_ADJUSTMENT) return 0;

            double fallbackRate = fallbackCount / (double) sessions;
            if (fallbackRate >= 0.45) return -1.0;
            if (avgDetectSeconds > 20 * 60) return -0.65;
            if (avgDetectSeconds < 6 * 60 && fallbackRate < 0.15) return 0.45;
            return 0;
        }

        private int calculateMinDetectionOffsetSeconds() {
            if (sessions < MIN_SESSIONS_FOR_ADJUSTMENT) return 0;

            double fallbackRate = fallbackCount / (double) sessions;
            if (fallbackRate >= 0.45) return -30;
            if (avgDetectSeconds > 20 * 60) return -20;
            if (avgDetectSeconds < 6 * 60 && fallbackRate < 0.15) return 30;
            return 0;
        }
    }

    private final SharedPreferences prefs;

    SleepStatsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Profile profileFor(String placementMode) {
        String prefix = keyPrefix(placementMode);
        return new Profile(
            prefs.getInt(prefix + "sessions", 0),
            prefs.getInt(prefix + "fallbackCount", 0),
            prefs.getInt(prefix + "cancelledCount", 0),
            Double.longBitsToDouble(prefs.getLong(prefix + "avgDetectSeconds", Double.doubleToLongBits(0))),
            Double.longBitsToDouble(prefs.getLong(prefix + "avgBaselineAudio", Double.doubleToLongBits(0))),
            Double.longBitsToDouble(prefs.getLong(prefix + "avgBaselineMotion", Double.doubleToLongBits(0)))
        );
    }

    void recordCompletedSession(
        String placementMode,
        int detectSeconds,
        boolean usedFallback,
        double baselineAudio,
        double baselineMotion
    ) {
        String prefix = keyPrefix(placementMode);
        Profile old = profileFor(placementMode);
        int sessions = old.sessions + 1;

        prefs.edit()
            .putInt(prefix + "sessions", sessions)
            .putInt(prefix + "fallbackCount", old.fallbackCount + (usedFallback ? 1 : 0))
            .putLong(prefix + "avgDetectSeconds", Double.doubleToLongBits(runningAverage(old.avgDetectSeconds, detectSeconds, sessions)))
            .putLong(prefix + "avgBaselineAudio", Double.doubleToLongBits(runningAverage(old.avgBaselineAudio, baselineAudio, sessions)))
            .putLong(prefix + "avgBaselineMotion", Double.doubleToLongBits(runningAverage(old.avgBaselineMotion, baselineMotion, sessions)))
            .apply();
    }

    void recordCancelledSession(String placementMode) {
        String prefix = keyPrefix(placementMode);
        prefs.edit()
            .putInt(prefix + "cancelledCount", prefs.getInt(prefix + "cancelledCount", 0) + 1)
            .apply();
    }

    private static double runningAverage(double currentAverage, double newValue, int newCount) {
        if (newCount <= 1) return newValue;
        return currentAverage + (newValue - currentAverage) / newCount;
    }

    private static String keyPrefix(String placementMode) {
        String normalized = "bed".equals(placementMode) ? "bed" : "bedside";
        return normalized + ".";
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
