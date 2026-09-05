package com.lumin.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Live PT-PT STT for REBORN Phone.
 *
 * Starts with the normal on-device recognizer, then automatically switches to Android's
 * external-audio recognition path when the no-root VOICE_CALL PCM bridge becomes active.
 * On Android 13+ the recognizer receives the captured PCM through a ParcelFileDescriptor
 * pipe instead of relying on the microphone. If the OEM recognition service rejects that
 * input, diagnostics expose the failure and REBORN can fall back to the normal recognizer.
 */
public final class RebornTranscriptionService {
    private static final String EXTRA_AUDIO_SOURCE = "android.speech.extra.AUDIO_SOURCE";
    private static final String EXTRA_AUDIO_SOURCE_CHANNEL_COUNT = "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT";
    private static final String EXTRA_AUDIO_SOURCE_ENCODING = "android.speech.extra.AUDIO_SOURCE_ENCODING";
    private static final String EXTRA_AUDIO_SOURCE_SAMPLING_RATE = "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE";

    private static SpeechRecognizer recognizer;
    private static volatile boolean running;
    private static volatile boolean externalPcm;
    private static volatile boolean pcmSwitchPending;
    private static volatile String state = "IDLE";
    private static volatile String lastText = "";
    private static volatile int pcmRate = 48000;
    private static volatile int pcmChannels = 1;
    private static Context app;
    private static ParcelFileDescriptor pcmRead;
    private static ParcelFileDescriptor pcmWrite;
    private static FileOutputStream pcmOut;
    private static final Object PCM_LOCK = new Object();

    private RebornTranscriptionService() {}

    public static synchronized void start(Context context) {
        if (running) return;
        app = context.getApplicationContext();
        running = true;
        externalPcm = false;
        pcmSwitchPending = false;
        state = "STARTING_MIC_FALLBACK";
        publish();

        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            state = "UNAVAILABLE";
            publish();
            return;
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                createRecognizerIfNeeded();
                listen();
            } catch (Throwable t) {
                state = "ERROR";
                saveError(t);
                publish();
            }
        });
    }

    /** Switch recognition from microphone input to the proven VOICE_CALL PCM stream. */
    public static void enableExternalPcm(Context context, int sampleRate, int channels) {
        if (!running || externalPcm || pcmSwitchPending) return;
        if (android.os.Build.VERSION.SDK_INT < 33) {
            saveMode("PCM_UNSUPPORTED_API");
            return;
        }
        pcmRate = sampleRate > 0 ? sampleRate : 48000;
        pcmChannels = channels == 2 ? 2 : 1;
        pcmSwitchPending = true;
        app = context.getApplicationContext();
        saveMode("PCM_SWITCHING");

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (!running) return;
            try {
                if (recognizer != null) recognizer.cancel();
                closePcmPipe();
                externalPcm = true;
                pcmSwitchPending = false;
                listen();
            } catch (Throwable t) {
                externalPcm = false;
                pcmSwitchPending = false;
                saveError(t);
                saveMode("PCM_SWITCH_FAILED");
                restartSoon();
            }
        });
    }

    /** Called directly by the digital call-audio bridge for every captured PCM16LE frame. */
    public static void feedPcm(short[] samples, int sampleRate, int channels) {
        if (!running || !externalPcm || samples == null || samples.length == 0) return;
        FileOutputStream out;
        synchronized (PCM_LOCK) { out = pcmOut; }
        if (out == null) return;

        try {
            byte[] bytes = new byte[samples.length * 2];
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : samples) b.putShort(s);
            synchronized (PCM_LOCK) {
                if (pcmOut != null) pcmOut.write(bytes);
            }
            Context c = app;
            if (c != null) c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                    .putString("stt_input", "VOICE_CALL_PCM")
                    .putInt("stt_pcm_rate", sampleRate)
                    .putInt("stt_pcm_channels", channels)
                    .apply();
        } catch (Throwable t) {
            saveError(t);
        }
    }

    public static synchronized void stop() {
        running = false;
        externalPcm = false;
        pcmSwitchPending = false;
        state = "STOPPED";
        closePcmPipe();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try { if (recognizer != null) recognizer.cancel(); } catch (Throwable ignored) {}
            try { if (recognizer != null) recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        });
        publish();
    }

    private static void createRecognizerIfNeeded() {
        if (recognizer != null || app == null) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(app);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                state = externalPcm ? "PCM_LISTENING" : "MIC_LISTENING";
                publish();
            }
            @Override public void onBeginningOfSpeech() {
                state = externalPcm ? "PCM_SPEECH" : "MIC_SPEECH";
                publish();
                RebornVoiceController.onCustomerStartedSpeaking();
            }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { state = "PROCESSING"; publish(); }
            @Override public void onError(int error) {
                if (!running) return;
                state = (externalPcm ? "PCM_ERROR_" : "MIC_ERROR_") + error;
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
    }

    private static void listen() {
        if (!running) return;
        createRecognizerIfNeeded();
        if (recognizer == null) return;

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-PT");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L);

        try {
            if (externalPcm && android.os.Build.VERSION.SDK_INT >= 33) {
                openPcmPipe();
                i.putExtra(EXTRA_AUDIO_SOURCE, pcmRead);
                i.putExtra(EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcmChannels);
                i.putExtra(EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra(EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcmRate);
                saveMode("VOICE_CALL_PCM");
            } else {
                saveMode("MIC_FALLBACK");
            }
            recognizer.startListening(i);
        } catch (Throwable t) {
            saveError(t);
            if (externalPcm) {
                externalPcm = false;
                closePcmPipe();
                saveMode("PCM_REJECTED_FALLBACK_MIC");
            }
            restartSoon();
        }
    }

    private static void openPcmPipe() throws Exception {
        closePcmPipe();
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        pcmRead = pipe[0];
        pcmWrite = pipe[1];
        synchronized (PCM_LOCK) { pcmOut = new FileOutputStream(pcmWrite.getFileDescriptor()); }
    }

    private static void closePcmPipe() {
        synchronized (PCM_LOCK) {
            try { if (pcmOut != null) pcmOut.close(); } catch (Throwable ignored) {}
            pcmOut = null;
        }
        try { if (pcmRead != null) pcmRead.close(); } catch (Throwable ignored) {}
        try { if (pcmWrite != null) pcmWrite.close(); } catch (Throwable ignored) {}
        pcmRead = null;
        pcmWrite = null;
    }

    private static void restartSoon() {
        Context c = app;
        if (c == null || !running) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!running || recognizer == null) return;
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            if (externalPcm) closePcmPipe();
            listen();
        }, externalPcm ? 120L : 250L);
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

    private static void saveMode(String mode) {
        Context c = app;
        if (c != null) c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE)
                .edit().putString("stt_input", mode).apply();
        publish();
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
    public static boolean isUsingExternalPcm() { return externalPcm; }
}
