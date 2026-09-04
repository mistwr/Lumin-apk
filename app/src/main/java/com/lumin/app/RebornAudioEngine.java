package com.lumin.app;

import android.content.Context;

/**
 * Audio pipeline entry point for REBORN calls.
 *
 * Keeps audio capture/transcription separated from the call controller.
 * The provider implementation can be swapped depending on device capabilities.
 */
public final class RebornAudioEngine {
    private static volatile boolean running = false;
    private static volatile String state = "IDLE";

    private RebornAudioEngine() {}

    public static void start(Context context) {
        running = true;
        state = "READY_FOR_AUDIO";
        RebornAudioBridge.onState(state);
    }

    public static void stop() {
        running = false;
        state = "STOPPED";
        RebornAudioBridge.onState(state);
    }

    public static boolean isRunning() {
        return running;
    }

    public static String state() {
        return state;
    }
}
