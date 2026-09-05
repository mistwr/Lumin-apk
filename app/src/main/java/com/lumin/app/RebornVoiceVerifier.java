package com.lumin.app;

import android.content.Context;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Verifies whether the remote handset actually heard a REBORN voice-route test.
 *
 * This does not pretend that a route works merely because Android accepted a TTS/bridge call.
 * A test is only marked CONFIRMED after the remote side answers with a positive phrase that
 * arrives through the live call STT. Negative phrases mark the route FAILED. Everything else
 * stays WAITING/UNPROVEN.
 */
public final class RebornVoiceVerifier {
    private static volatile boolean waiting;
    private static volatile String route = "";
    private static volatile String state = "IDLE";
    private static volatile long startedAt;
    private static Context app;

    private RebornVoiceVerifier() {}

    public static synchronized void start(Context context, String voiceRoute) {
        app = context.getApplicationContext();
        route = voiceRoute == null ? "" : voiceRoute;
        waiting = true;
        startedAt = System.currentTimeMillis();
        state = "WAITING_REMOTE_CONFIRMATION";
        save("voice_verify_route", route);
        save("voice_verify_state", state);
        save("voice_verify_text", "");
        save("voice_verify_at", String.valueOf(startedAt));
        RebornCallActivity.refreshFromService();
    }

    /** Returns true when the utterance was consumed as a route-test response. */
    public static synchronized boolean onRemoteText(String text) {
        if (!waiting || text == null) return false;
        if (System.currentTimeMillis() - startedAt > 20_000L) {
            waiting = false;
            state = "TIMEOUT_UNPROVEN";
            save("voice_verify_state", state);
            RebornCallActivity.refreshFromService();
            return false;
        }

        String n = normalize(text);
        boolean positive = containsAny(n,
                "ouvi", "estou a ouvir", "consigo ouvir", "sim ouvi", "sim", "estou ouvindo", "deu para ouvir");
        boolean negative = containsAny(n,
                "nao ouvi", "nao estou a ouvir", "nao consigo ouvir", "nao se ouve", "nao ouvi nada");

        if (!positive && !negative) return false;

        waiting = false;
        state = positive ? "CONFIRMED_REMOTE_HEARD" : "FAILED_REMOTE_DID_NOT_HEAR";
        save("voice_verify_state", state);
        save("voice_verify_text", text.trim());
        save("voice_verified_route", positive ? route : "");
        save("voice_verify_at", String.valueOf(System.currentTimeMillis()));
        RebornCallActivity.refreshFromService();
        return true;
    }

    public static synchronized void cancel() {
        waiting = false;
        state = "IDLE";
        save("voice_verify_state", state);
        RebornCallActivity.refreshFromService();
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return " " + n + " ";
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            String t = normalize(term).trim();
            if (!t.isEmpty() && value.contains(" " + t + " ")) return true;
        }
        return false;
    }

    private static void save(String key, String value) {
        Context c = app;
        if (c == null) return;
        c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                .putString(key, value == null ? "" : value).apply();
    }

    public static boolean isWaiting() { return waiting; }
    public static String state() { return state; }
    public static String route() { return route; }
}
