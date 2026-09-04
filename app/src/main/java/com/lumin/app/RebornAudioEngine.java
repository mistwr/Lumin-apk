package com.lumin.app;

import android.content.Context;

/**
 * Audio pipeline foundation for REBORN calls.
 * Keeps the audio layer separated from the AI brain so different
 * capture/transcription backends can be connected later.
 */
public final class RebornAudioEngine {
    private static volatile boolean running = false;
    private static volatile String state = "IDLE";

    private RebornAudioEngine() {}

    public static void start(Context context) {
        running = true;
        state = "LISTENING";
    }

    public static void stop() {
        running = false;
        state = "STOPPED";
    }

    public static boolean isRunning() {
        return running;
    }

    public static String state() {
        return state;
    }

    /**
     * Entry point for verified speech-to-text events.
     * The final audio backend will feed this into RebornCentral.
     */
    public static void onTranscript(String text) {
        if (!running || text == null) return;
        RebornCentral.onCustomerText(text);
    }
}
