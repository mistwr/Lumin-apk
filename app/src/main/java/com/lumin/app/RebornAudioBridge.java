package com.lumin.app;

import android.content.Context;

/** Shared audio diagnostics bus for the REBORN call pipeline. */
public final class RebornAudioBridge {
    private static volatile String state = "IDLE";
    private static volatile long frames = 0L;
    private static volatile int sampleRate = 0;
    private static volatile int channels = 0;
    private static Context app;

    private RebornAudioBridge() {}

    public static void init(Context context) {
        app = context.getApplicationContext();
        save();
    }

    public static void onState(String value) {
        state = value == null ? "" : value;
        save();
        RebornCallActivity.refreshFromService();
    }

    public static void onPcmFrame(int rate, int ch) {
        frames++;
        sampleRate = rate;
        channels = ch;
        state = "PCM_ACTIVE";
        save();
        if ((frames % 25L) == 0L) RebornCallActivity.refreshFromService();
    }

    private static void save() {
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
