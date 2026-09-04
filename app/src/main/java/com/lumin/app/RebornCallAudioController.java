package com.lumin.app;

import android.content.Context;

/**
 * Coordinates the audio lifecycle of a REBORN call session.
 * Keeps audio state separate from Telecom and the AI brain.
 */
public final class RebornCallAudioController {
    private static volatile boolean running = false;

    private RebornCallAudioController() {}

    public static void start(Context context) {
        if (running) return;
        running = true;
        RebornAudioEngine.start(context);
        RebornCentral.save("audio_state", "LISTENING");
    }

    public static void stop(Context context) {
        if (!running) return;
        running = false;
        RebornAudioEngine.stop();
        RebornCentral.save("audio_state", "STOPPED");
    }

    public static boolean isRunning() {
        return running;
    }

    public static void feedCustomerText(String text) {
        if (!running) return;
        RebornCentral.onCustomerText(text);
    }
}
