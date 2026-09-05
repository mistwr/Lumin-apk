package com.lumin.app;

import android.content.Context;

/** Entry point for REBORN call audio capture + diagnostics. */
public final class RebornAudioEngine {
    private static volatile boolean running = false;
    private static volatile String state = "IDLE";
    private static Context app;

    private RebornAudioEngine() {}

    public static synchronized void start(Context context) {
        if (running) return;
        app = context.getApplicationContext();
        RebornAudioBridge.init(app);
        running = true;
        state = "STARTING";
        RebornAudioBridge.onState(state);

        // Proven S26 Ultra path: paired Wireless ADB shell -> app_process -> VOICE_CALL PCM.
        // Failure here is non-fatal because Android STT can still run as a fallback path.
        try {
            RebornDigitalAudioController.start(app);
            state = "PCM_BRIDGE_STARTING";
        } catch (Throwable t) {
            state = "PCM_FALLBACK_STT";
            app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                    .putString("pcm_error", t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage()))
                    .apply();
        }
        RebornAudioBridge.onState(state);
    }

    public static synchronized void stop() {
        if (!running) return;
        running = false;
        try { RebornDigitalAudioController.stop(app); } catch (Throwable ignored) {}
        state = "STOPPED";
        RebornAudioBridge.onState(state);
    }

    public static boolean isRunning() { return running; }
    public static String state() { return state; }
}
