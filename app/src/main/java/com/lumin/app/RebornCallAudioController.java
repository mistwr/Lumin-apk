package com.lumin.app;

import android.content.Context;

/** Coordinates call lifecycle with audio/STT/voice services. */
public final class RebornCallAudioController {
    private static volatile boolean running;
    private static volatile String state = "IDLE";
    private static Context app;

    private RebornCallAudioController() {}

    public static synchronized void start(Context context) {
        if (running) return;
        app = context.getApplicationContext();
        running = true;
        state = "STARTING";
        save();

        RebornAudioEngine.start(app);
        RebornTranscriptionService.start(app);
        RebornVoiceController.init(app);

        state = "LISTENING";
        save();
        RebornCallActivity.refreshFromService();
    }

    public static synchronized void stop() {
        if (!running) return;
        running = false;
        state = "STOPPING";
        save();

        RebornTranscriptionService.stop();
        RebornAudioEngine.stop();
        RebornVoiceController.stop();

        state = "STOPPED";
        save();
        RebornCallActivity.refreshFromService();
    }

    public static void feedVerifiedTranscript(String text) {
        if (!running || text == null || text.trim().length() < 2) return;
        RebornCentral.onCustomerText(text.trim());
    }

    private static void save() {
        Context c = app;
        if (c == null) return;
        c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                .putString("audio_controller_state", state)
                .putBoolean("audio_controller_running", running)
                .apply();
    }

    public static boolean isRunning() { return running; }
    public static String state() { return state; }
}
