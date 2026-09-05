package com.lumin.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/**
 * On-device/live STT controller used by REBORN Phone.
 *
 * This is the immediately usable Android STT path. It prefers offline recognition and
 * continuously restarts while a call session is active. On devices/firmware where the
 * recognizer cannot access call audio, RebornAudioEngine can still expose a different PCM
 * provider without changing the brain API.
 */
public final class RebornTranscriptionService {
    private static SpeechRecognizer recognizer;
    private static volatile boolean running;
    private static volatile String state = "IDLE";
    private static volatile String lastText = "";
    private static Context app;

    private RebornTranscriptionService() {}

    public static synchronized void start(Context context) {
        if (running) return;
        app = context.getApplicationContext();
        running = true;
        state = "STARTING";
        publish();

        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            state = "UNAVAILABLE";
            publish();
            return;
        }

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(app);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { state = "LISTENING"; publish(); }
                @Override public void onBeginningOfSpeech() { state = "SPEECH"; publish(); RebornVoiceController.onCustomerStartedSpeaking(); }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { state = "PROCESSING"; publish(); }
                @Override public void onError(int error) {
                    if (!running) return;
                    state = "RESTARTING_" + error;
                    publish();
                    restartSoon();
                }
                @Override public void onResults(Bundle results) {
                    consume(results, true);
                    if (running) restartSoon();
                }
                @Override public void onPartialResults(Bundle partialResults) { consume(partialResults, false); }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            listen();
        } catch (Throwable t) {
            state = "ERROR";
            saveError(t);
            publish();
        }
    }

    public static synchronized void stop() {
        running = false;
        state = "STOPPED";
        try { if (recognizer != null) recognizer.cancel(); } catch (Throwable ignored) {}
        try { if (recognizer != null) recognizer.destroy(); } catch (Throwable ignored) {}
        recognizer = null;
        publish();
    }

    private static void listen() {
        if (!running || recognizer == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-PT");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L);
        try {
            recognizer.startListening(i);
        } catch (Throwable t) {
            saveError(t);
            restartSoon();
        }
    }

    private static void restartSoon() {
        Context c = app;
        if (c == null || !running) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!running || recognizer == null) return;
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            listen();
        }, 250L);
    }

    private static void consume(Bundle bundle, boolean finalResult) {
        if (bundle == null) return;
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        String text = list.get(0) == null ? "" : list.get(0).trim();
        if (text.length() < 2) return;
        state = finalResult ? "TEXT_READY" : "PARTIAL";
        if (app != null) app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE)
                .edit().putString("stt_partial", text).putString("stt_state", state).apply();
        publish();
        if (finalResult && !text.equalsIgnoreCase(lastText)) {
            lastText = text;
            RebornCentral.onCustomerText(text);
        }
    }

    private static void publish() {
        Context c = app;
        if (c != null) c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE)
                .edit().putString("stt_state", state).apply();
        RebornCallActivity.refreshFromService();
    }

    private static void saveError(Throwable t) {
        Context c = app;
        if (c == null) return;
        c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                .putString("stt_error", t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage()))
                .apply();
    }

    public static boolean isRunning() { return running; }
    public static String state() { return state; }
    public static String lastText() { return lastText; }
}
