package com.lumin.app;

import android.content.Context;

/** Shared audio diagnostics bus for the REBORN call pipeline. */
public final class RebornAudioBridge {
    private static volatile String state = "IDLE";
    private static volatile long frames = 0L;
    private static volatile int sampleRate = 0;
    private static volatile int channels = 0;
    private static Context app;
    private static volatile long lastPersistAt = 0L;

    private RebornAudioBridge() {}

    public static void init(Context context) {
        app = context.getApplicationContext();
        persist();
    }

    public static void onState(String value) {
        state = value == null ? "" : value;
        persist();
        RebornCallActivity.refreshFromService();
    }

    public static void onPcmFrame(int rate, int ch) {
        frames++;
        sampleRate = rate;
        channels = ch;
        state = "PCM_ACTIVE";

        // IMPORTANT: PCM can arrive ~50 times/sec. Writing SharedPreferences on every frame
        // was saturating Android's preference/disk queue and contributed to call-screen ANRs.
        // Keep counters in memory and persist/refresh at most ~2 times/sec.
        long now = System.currentTimeMillis();
        if (now - lastPersistAt >= 500L) {
            lastPersistAt = now;
            persist();
            RebornCallActivity.refreshFromService();
        }
    }

    private static void persist() {
        Context c = app;
        if (c == null) return;
        c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                .putString("audio_state", state)
                .putLong("audio_frames", frames)
                .putInt("audio_rate", sampleRate)
                .putInt("audio_channels", channels)
                .apply();
    }

    public static String state() { return state; }
    public static long frames() { return frames; }
    public static int sampleRate() { return sampleRate; }
    public static int channels() { return channels; }
}
