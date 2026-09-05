package com.lumin.app;

import android.content.Context;

/** Coordinates the REBORN call audio lifecycle. */
public final class RebornCallAudioController {
    private static volatile boolean running = false;

    private RebornCallAudioController() {}

    public static void start(Context context) {
        if (running) {
            RebornAudioEngine.runProbe(context);
            return;
        }
        running = true;
        RebornAudioEngine.start(context);
        RebornCentral.save("audio_state", "LISTENING");
        RebornCentral.save("audio_route_probe_v4", RebornAudioEngine.lastProbe());
    }

    public static void stop(Context context) {
        if (!running) return;
        running = false;
        RebornAudioEngine.stop();
        RebornCentral.save("audio_state", "STOPPED");
    }

    public static boolean isRunning() { return running; }

    public static void feedCustomerText(String text) {
        if (!running) return;
        RebornCentral.onCustomerText(text);
    }

    /** Manual rerun for the in-call diagnostics screen/button. */
    public static String probeNow(Context context) {
        return RebornAudioEngine.runProbe(context);
    }
}
