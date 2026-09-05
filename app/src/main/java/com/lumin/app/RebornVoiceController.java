package com.lumin.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;

/**
 * REBORN voice output controller.
 *
 * It provides two practical output paths:
 * 1) Samsung Text Call bridge when that Samsung surface is available.
 * 2) Android TTS on the call device as a local/speaker fallback.
 *
 * Direct digital uplink injection into a cellular call is deliberately NOT claimed here;
 * Android/OEM restrictions vary and that path must be proven separately on-device.
 */
public final class RebornVoiceController implements TextToSpeech.OnInitListener {
    private static final Queue<String> queue = new ArrayDeque<>();
    private static volatile RebornVoiceController instance;
    private static volatile String state = "IDLE";
    private static volatile String route = "LOCAL_TTS";
    private static Context app;

    private TextToSpeech tts;
    private boolean ready;

    private RebornVoiceController(Context context) {
        app = context.getApplicationContext();
        tts = new TextToSpeech(app, this);
    }

    public static synchronized void init(Context context) {
        if (instance == null) instance = new RebornVoiceController(context);
    }

    public static synchronized void speak(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return;
        init(context);
        String clean = text.trim();

        String mode = context.getSharedPreferences("sofia_control", Context.MODE_PRIVATE)
                .getString("mode", "AUTO");
        if (!"AUTO".equals(mode)) {
            state = "SUGGESTION_READY";
            save("voice_text", clean);
            publish();
            return;
        }

        // First try the existing Samsung Text Call accessibility bridge. If Samsung's
        // in-call text surface is not active, the service will simply fail safely and
        // REBORN can still speak locally through TTS.
        if (isSamsungBridgeEnabled(context)) {
            try {
                Intent i = new Intent(SofiaAccessibilityService.ACTION_SEND_REPLY);
                i.setPackage(context.getPackageName());
                i.putExtra(SofiaAccessibilityService.EXTRA_REPLY, clean);
                context.sendBroadcast(i);
                route = "SAMSUNG_TEXT_CALL_ATTEMPT";
                save("voice_route", route);
            } catch (Throwable ignored) { }
        }

        synchronized (queue) { queue.offer(clean); }
        instance.drain();
    }

    @Override public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            state = "TTS_ERROR";
            publish();
            return;
        }
        ready = true;
        try {
            int lang = tts.setLanguage(new Locale("pt", "PT"));
            save("tts_lang", String.valueOf(lang));
            tts.setSpeechRate(1.04f);
            tts.setPitch(1.0f);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
        } catch (Throwable ignored) { }
        drain();
    }

    private void drain() {
        if (!ready || tts == null || tts.isSpeaking()) return;
        final String next;
        synchronized (queue) { next = queue.poll(); }
        if (next == null) {
            state = "WAITING_CLIENT";
            publish();
            return;
        }
        state = "SPEAKING_LOCAL";
        route = "LOCAL_TTS";
        save("voice_route", route);
        save("voice_text", next);
        publish();

        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "reborn-" + System.currentTimeMillis());
        try {
            tts.speak(next, TextToSpeech.QUEUE_FLUSH, params, "reborn-" + System.currentTimeMillis());
        } catch (Throwable t) {
            state = "TTS_ERROR";
            save("voice_error", t.getClass().getSimpleName());
            publish();
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::drain, 900L);
    }

    public static void onCustomerStartedSpeaking() {
        RebornVoiceController v = instance;
        if (v != null && v.tts != null) {
            try { if (v.tts.isSpeaking()) v.tts.stop(); } catch (Throwable ignored) { }
        }
        synchronized (queue) { queue.clear(); }
        state = "BARGE_IN";
        publish();
    }

    public static synchronized void stop() {
        synchronized (queue) { queue.clear(); }
        RebornVoiceController v = instance;
        if (v != null && v.tts != null) {
            try { v.tts.stop(); } catch (Throwable ignored) { }
        }
        state = "STOPPED";
        publish();
    }

    private static boolean isSamsungBridgeEnabled(Context context) {
        try {
            String enabled = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.toLowerCase().contains(context.getPackageName().toLowerCase());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void save(String key, String value) {
        Context c = app;
        if (c == null) return;
        c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                .putString(key, value == null ? "" : value).apply();
    }

    private static void publish() {
        save("voice_state", state);
        RebornCallActivity.refreshFromService();
    }

    public static String state() { return state; }
    public static String route() { return route; }
}
