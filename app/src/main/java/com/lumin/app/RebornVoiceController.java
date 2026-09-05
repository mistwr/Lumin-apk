package com.lumin.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;

/**
 * REBORN voice output controller.
 *
 * Practical routes:
 * 1) Samsung Text Call bridge when that Samsung surface is available.
 * 2) Android TTS on the call device as a local/speaker fallback.
 *
 * Direct digital uplink injection into cellular GSM remains a separately measured experiment.
 */
public final class RebornVoiceController implements TextToSpeech.OnInitListener {
    private static final Queue<String> queue = new ArrayDeque<>();
    private static volatile RebornVoiceController instance;
    private static volatile String state = "IDLE";
    private static volatile String route = "LOCAL_TTS";
    private static volatile boolean speaking = false;
    private static volatile String currentText = "";
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
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    speaking = true;
                    state = "SPEAKING_LOCAL";
                    save("voice_started_at", String.valueOf(System.currentTimeMillis()));
                    publish();
                }
                @Override public void onDone(String utteranceId) {
                    speaking = false;
                    currentText = "";
                    state = "WAITING_CLIENT";
                    save("voice_finished_at", String.valueOf(System.currentTimeMillis()));
                    publish();
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(RebornVoiceController.this::drain);
                }
                @Override public void onError(String utteranceId) {
                    speaking = false;
                    currentText = "";
                    state = "TTS_ERROR";
                    publish();
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(RebornVoiceController.this::drain);
                }
            });
        } catch (Throwable ignored) { }
        drain();
    }

    private void drain() {
        if (!ready || tts == null || speaking || tts.isSpeaking()) return;
        final String next;
        synchronized (queue) { next = queue.poll(); }
        if (next == null) {
            state = "WAITING_CLIENT";
            publish();
            return;
        }
        currentText = next;
        state = "PREPARING_SPEECH";
        route = "LOCAL_TTS";
        save("voice_route", route);
        save("voice_text", next);
        publish();

        String id = "reborn-" + System.currentTimeMillis();
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
        try {
            int r = tts.speak(next, TextToSpeech.QUEUE_FLUSH, params, id);
            if (r == TextToSpeech.ERROR) {
                speaking = false;
                currentText = "";
                state = "TTS_ERROR";
                publish();
            }
        } catch (Throwable t) {
            speaking = false;
            currentText = "";
            state = "TTS_ERROR";
            save("voice_error", t.getClass().getSimpleName());
            publish();
        }
    }

    public static void onCustomerStartedSpeaking() {
        RebornVoiceController v = instance;
        if (v != null && v.tts != null) {
            try { if (v.tts.isSpeaking()) v.tts.stop(); } catch (Throwable ignored) { }
        }
        synchronized (queue) { queue.clear(); }
        speaking = false;
        currentText = "";
        state = "BARGE_IN";
        publish();
    }

    public static synchronized void stop() {
        synchronized (queue) { queue.clear(); }
        RebornVoiceController v = instance;
        if (v != null && v.tts != null) {
            try { v.tts.stop(); } catch (Throwable ignored) { }
        }
        speaking = false;
        currentText = "";
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
        save("voice_speaking", String.valueOf(speaking));
        RebornCallActivity.refreshFromService();
    }

    public static String state() { return state; }
    public static String route() { return route; }
    public static boolean isSpeaking() { return speaking; }
    public static String currentText() { return currentText; }
}
