package com.lumin.app;

/**
 * REBORN live transcription bridge.
 * Receives validated speech text from the audio/STT layer and forwards it
 * to the central AI brain.
 */
public final class RebornTranscriptionService {

    private static boolean running = false;
    private static String lastText = "";

    private RebornTranscriptionService() {}

    public static void start() {
        running = true;
    }

    public static void stop() {
        running = false;
        lastText = "";
    }

    /** Feed only trusted customer speech from STT. */
    public static void onSpeechResult(String text) {
        if (!running || text == null) return;
        String clean = text.trim();
        if (clean.length() < 2 || clean.equals(lastText)) return;
        lastText = clean;
        RebornCentral.onCustomerText(clean);
    }

    public static boolean isRunning() {
        return running;
    }
}
