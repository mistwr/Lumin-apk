package com.lumin.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;

/**
 * REBORN voice output controller with explicit, measurable routes.
 *
 * Routes:
 * AUTO       -> Samsung Text Call attempt first, then local/acoustic TTS fallback.
 * SAMSUNG    -> Samsung Text Call bridge only.
 * ACOUSTIC   -> speakerphone + communication TTS, useful to prove the full conversation loop.
 * DIGITAL    -> reserved experimental uplink path; never reported as working until proven on-device.
 */
public final class RebornVoiceController implements TextToSpeech.OnInitListener {
    public static final String ROUTE_AUTO = "AUTO";
    public static final String ROUTE_SAMSUNG = "SAMSUNG";
    public static final String ROUTE_ACOUSTIC = "ACOUSTIC";
    public static final String ROUTE_DIGITAL = "DIGITAL_EXPERIMENTAL";

    private static final Queue<String> queue = new ArrayDeque<>();
    private static volatile RebornVoiceController instance;
    private static volatile String state = "IDLE";
    private static volatile String route = ROUTE_AUTO;
    private static volatile boolean speaking = false;
    private static volatile String currentText = "";
    private static Context app;

    private TextToSpeech tts;
    private boolean ready;
    private boolean forcedSpeaker;

    private RebornVoiceController(Context context) {
        app = context.getApplicationContext();
        route = app.getSharedPreferences("sofia_control", Context.MODE_PRIVATE)
                .getString("voice_route_mode", ROUTE_AUTO);
        tts = new TextToSpeech(app, this);
    }

    public static synchronized void init(Context context) {
        if (instance == null) instance = new RebornVoiceController(context);
    }

    public static synchronized void setRoute(Context context, String requested) {
        init(context);
        if (!ROUTE_AUTO.equals(requested) && !ROUTE_SAMSUNG.equals(requested)
                && !ROUTE_ACOUSTIC.equals(requested) && !ROUTE_DIGITAL.equals(requested)) {
            requested = ROUTE_AUTO;
        }
        route = requested;
        context.getSharedPreferences("sofia_control", Context.MODE_PRIVATE).edit()
                .putString("voice_route_mode", route).apply();
        save("voice_route", route);
        state = ROUTE_DIGITAL.equals(route) ? "DIGITAL_UNPROVEN" : "ROUTE_READY";
        publish();
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

        if (ROUTE_DIGITAL.equals(route)) {
            // Important: do not fake success. This marks the test as unavailable until a
            // device-specific uplink writer is actually proven by the remote handset.
            state = "DIGITAL_UNPROVEN";
            save("voice_text", clean);
            save("voice_route", route);
            save("voice_error", "Direct GSM uplink injection not yet proven on this device");
            publish();
            return;
        }

        if (ROUTE_SAMSUNG.equals(route)) {
            boolean attempted = sendSamsung(context, clean);
            state = attempted ? "SAMSUNG_BRIDGE_REQUESTED" : "SAMSUNG_BRIDGE_UNAVAILABLE";
            publish();
            return;
        }

        if (ROUTE_AUTO.equals(route) && isSamsungBridgeEnabled(context)) {
            sendSamsung(context, clean);
            save("voice_auto_samsung_attempt", "true");
        }

        synchronized (queue) { queue.offer(clean); }
        instance.drain();
    }

    private static boolean sendSamsung(Context context, String clean) {
        if (!isSamsungBridgeEnabled(context)) return false;
        try {
            Intent i = new Intent(SofiaAccessibilityService.ACTION_SEND_REPLY);
            i.setPackage(context.getPackageName());
            i.putExtra(SofiaAccessibilityService.EXTRA_REPLY, clean);
            context.sendBroadcast(i);
            save("voice_route", "SAMSUNG_TEXT_CALL_ATTEMPT");
            return true;
        } catch (Throwable t) {
            save("voice_error", "Samsung bridge: " + t.getClass().getSimpleName());
            return false;
        }
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
                    state = ROUTE_ACOUSTIC.equals(route) ? "SPEAKING_ACOUSTIC" : "SPEAKING_LOCAL";
                    save("voice_started_at", String.valueOf(System.currentTimeMillis()));
                    publish();
                }
                @Override public void onDone(String utteranceId) {
                    speaking = false;
                    currentText = "";
                    restoreSpeakerIfNeeded();
                    state = "WAITING_CLIENT";
                    save("voice_finished_at", String.valueOf(System.currentTimeMillis()));
                    publish();
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(RebornVoiceController.this::drain);
                }
                @Override public void onError(String utteranceId) {
                    speaking = false;
                    currentText = "";
                    restoreSpeakerIfNeeded();
                    state = "TTS_ERROR";
                    publish();
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(RebornVoiceController.this::drain);
                }
            });
        } catch (Throwable ignored) { }
        drain();
    }

    private void prepareAcousticRoute() {
        if (!ROUTE_ACOUSTIC.equals(route) || app == null) return;
        try {
            RebornInCallService s = RebornInCallService.get();
            if (s != null) {
                s.setSpeaker(true);
                forcedSpeaker = true;
            } else {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    am.setSpeakerphoneOn(true);
                    forcedSpeaker = true;
                }
            }
            save("voice_acoustic", "SPEAKER_FORCED");
        } catch (Throwable t) {
            save("voice_error", "Acoustic route: " + t.getClass().getSimpleName());
        }
    }

    private void restoreSpeakerIfNeeded() {
        if (!forcedSpeaker) return;
        forcedSpeaker = false;
        try {
            RebornInCallService s = RebornInCallService.get();
            if (s != null) s.setSpeaker(false);
        } catch (Throwable ignored) { }
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
        save("voice_route", route);
        save("voice_text", next);
        publish();

        prepareAcousticRoute();
        String id = "reborn-" + System.currentTimeMillis();
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
        try {
            int r = tts.speak(next, TextToSpeech.QUEUE_FLUSH, params, id);
            if (r == TextToSpeech.ERROR) {
                speaking = false;
                currentText = "";
                restoreSpeakerIfNeeded();
                state = "TTS_ERROR";
                publish();
            }
        } catch (Throwable t) {
            speaking = false;
            currentText = "";
            restoreSpeakerIfNeeded();
            state = "TTS_ERROR";
            save("voice_error", t.getClass().getSimpleName());
            publish();
        }
    }

    public static void onCustomerStartedSpeaking() {
        RebornVoiceController v = instance;
        if (v != null && v.tts != null) {
            try { if (v.tts.isSpeaking()) v.tts.stop(); } catch (Throwable ignored) { }
            v.restoreSpeakerIfNeeded();
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
            v.restoreSpeakerIfNeeded();
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
        save("voice_route", route);
        RebornCallActivity.refreshFromService();
    }

    public static String state() { return state; }
    public static String route() { return route; }
    public static boolean isSpeaking() { return speaking; }
    public static String currentText() { return currentText; }
}
