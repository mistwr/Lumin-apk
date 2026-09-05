package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.telecom.Call;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Central coordinator for REBORN call sessions. */
public final class RebornCentral {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final SofiaMemory MEMORY = new SofiaMemory();
    private static volatile Context app;
    private static volatile TextToSpeech tts;
    private static volatile String lastCustomer = "";
    private static volatile String lastAssistant = "";

    private RebornCentral() {}

    public static synchronized void init(Context context) {
        if (context == null) return;
        app = context.getApplicationContext();
        if (tts == null) {
            tts = new TextToSpeech(app, status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(new Locale("pt", "PT"));
                    tts.setSpeechRate(1.02f);
                }
            });
        }
    }

    public static void startSession(Context context) {
        init(context);
        save("session_state", "STARTED");
        RebornCallAudioController.start(context);
    }

    public static void stopSession(Context context) {
        RebornCallAudioController.stop(context);
        save("session_state", "STOPPED");
        TextToSpeech engine = tts;
        if (engine != null) engine.stop();
    }

    public static void onCallStarted(Context context, Call call) {
        init(context);
        save("call_state", "ADDED");
    }

    public static void onCallActive(Context context, Call call) {
        init(context);
        save("call_state", "ACTIVE");
        save("audio_route_probe_v4", RebornCallAudioController.probeNow(context));
    }

    public static void onCallState(Context context, int state) {
        init(context);
        save("call_state", String.valueOf(state));
    }

    public static void onCallDetails(Context context, Call call, Call.Details details) {
        init(context);
        if (details != null) save("call_capabilities", String.valueOf(details.getCallCapabilities()));
    }

    public static void onCallEnded(Context context) {
        save("call_state", "ENDED");
        stopSession(context);
    }

    public static void onCustomerText(String text) {
        if (text == null) return;
        String clean = text.trim();
        if (clean.isEmpty()) return;
        lastCustomer = clean;
        save("last_customer", clean);
        SofiaEngine.learnFreeText(clean, MEMORY);

        SofiaEngine.Decision fast = SofiaEngine.fastDecision(clean, MEMORY);
        if (fast != null) {
            deliverAssistant(fast.reply);
            save("conversation_stage", fast.stage);
            return;
        }

        EXEC.execute(() -> {
            String reply;
            try {
                reply = QwenClient.generate(SofiaEngine.buildPrompt(clean, MEMORY));
                if (reply == null || reply.trim().isEmpty()) throw new IllegalStateException("empty LLM reply");
            } catch (Throwable e) {
                reply = "Percebi. Diga-me só o que pretende melhorar no seu serviço atual.";
                save("llm_error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            }
            deliverAssistant(reply.trim());
        });
    }

    private static synchronized void deliverAssistant(String reply) {
        if (reply == null || reply.isEmpty()) return;
        lastAssistant = reply;
        MEMORY.setLastAssistant(reply);
        save("last_assistant", reply);
        save("audio_output_mode", "ACOUSTIC_FALLBACK");
        TextToSpeech engine = tts;
        if (engine != null) engine.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "reborn-call-reply");
        RebornCallActivity.refreshFromService();
    }

    public static String lastCustomer() { return lastCustomer; }
    public static String lastAssistant() { return lastAssistant; }

    public static void save(String key, String value) {
        Context c = app;
        if (c == null || key == null) return;
        try {
            SharedPreferences p = c.getSharedPreferences("reborn_state", Context.MODE_PRIVATE);
            p.edit().putString(key, value == null ? "" : value).apply();
        } catch (Throwable ignored) {}
    }

    public static String read(String key) {
        Context c = app;
        if (c == null) return "";
        try { return c.getSharedPreferences("reborn_state", Context.MODE_PRIVATE).getString(key, ""); }
        catch (Throwable ignored) { return ""; }
    }
}
