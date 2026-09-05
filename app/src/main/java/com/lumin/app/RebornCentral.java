package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.telecom.Call;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central brain for one native REBORN phone call.
 * Keeps the session hot, owns the commercial opener, fast-path decisions,
 * local Qwen fallback and the live transcript shared by the call UI.
 */
public final class RebornCentral {
    public static final String INTRO = "Olá, boa tarde. Sou a assistente virtual da MY POUPar+. É uma chamada rápida para ajudar a perceber se os seus serviços de energia ou telecomunicações continuam competitivos. Posso explicar em vinte segundos?";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final SofiaMemory MEMORY = new SofiaMemory();
    private static volatile Context app;
    private static volatile boolean introQueued = false;
    private static volatile boolean busy = false;
    private static volatile String transcript = "";
    private static volatile String lastCustomer = "";
    private static volatile String lastReply = "";
    private static volatile String stage = "IDLE";
    private static volatile long lastLatencyMs = 0L;

    public interface Listener { void onCentralChanged(); }
    private static volatile Listener listener;

    private RebornCentral() {}

    public static void init(Context context) {
        app = context.getApplicationContext();
        RebornVoiceController.init(app);
        warm();
    }

    public static void setListener(Listener l) { listener = l; }

    public static void startSession(Context context) {
        app = context.getApplicationContext();
        introQueued = false;
        busy = false;
        transcript = "";
        lastCustomer = "";
        lastReply = "";
        stage = "PREPARING";
        lastLatencyMs = 0L;
        MEMORY.setLastAssistant("");
        LocalRebornEngine.resetConversation();
        RebornVoiceController.init(app);
        warm();
        publish();
    }

    public static void onCallState(Context context, int state) {
        if (app == null) app = context.getApplicationContext();
        if (state == Call.STATE_ACTIVE) {
            stage = "LISTENING";
            if (!introQueued) queueIntro();
        } else if (state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) {
            stage = "CONNECTING";
        } else if (state == Call.STATE_RINGING) {
            stage = "RINGING";
        } else if (state == Call.STATE_HOLDING) {
            stage = "HOLD";
        } else if (state == Call.STATE_DISCONNECTED) {
            stage = "ENDED";
            RebornVoiceController.stop();
        }
        publish();
    }

    public static void queueIntro() {
        if (introQueued) return;
        introQueued = true;
        lastReply = INTRO;
        MEMORY.setLastAssistant(INTRO);
        append("REBORN", INTRO);
        stage = "INTRO_READY";
        save("voice_output", "INTRO_READY");
        publish();
        Context c = app;
        if (c != null) RebornVoiceController.speak(c, INTRO);
    }

    /** Feed only a verified customer utterance (STT/PCM provider or trusted transcript). */
    public static void onCustomerText(String text) {
        if (text == null) return;
        String clean = text.trim();
        if (clean.length() < 2 || clean.equalsIgnoreCase(lastCustomer) || clean.equalsIgnoreCase(lastReply)) return;
        lastCustomer = clean;
        append("Cliente", clean);
        SofiaEngine.learnFreeText(clean, MEMORY);

        SofiaEngine.Decision fast = SofiaEngine.fastDecision(clean, MEMORY);
        if (fast != null && fast.reply != null && !fast.reply.trim().isEmpty()) {
            lastLatencyMs = 0L;
            setReply(fast.reply.trim(), "FAST_PATH");
            return;
        }

        if (busy) return;
        busy = true;
        stage = "THINKING";
        publish();
        final String prompt = SofiaEngine.buildPrompt(clean, MEMORY);
        WORKER.submit(() -> {
            long t0 = System.currentTimeMillis();
            try {
                String reply = QwenClient.generate(prompt);
                if (reply == null || reply.trim().isEmpty()) reply = "Certo. Diga-me só qual é hoje o serviço que mais gostava de melhorar: telecomunicações ou energia?";
                lastLatencyMs = System.currentTimeMillis() - t0;
                setReply(reply.trim(), "QWEN_LOCAL");
            } catch (Throwable e) {
                lastLatencyMs = System.currentTimeMillis() - t0;
                setReply("Certo. Diga-me só qual é hoje o serviço que mais gostava de melhorar: telecomunicações ou energia?", "FALLBACK");
                save("central_error", e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
            } finally {
                busy = false;
            }
        });
    }

    private static void setReply(String reply, String source) {
        lastReply = reply;
        MEMORY.setLastAssistant(reply);
        append("REBORN", reply);
        stage = "REPLY_READY";
        save("central_path", source);
        save("voice_output", "REPLY_READY");
        publish();
        Context c = app;
        if (c != null) RebornVoiceController.speak(c, reply);
    }

    private static void warm() {
        Context c = app;
        if (c == null || !LocalRebornEngine.isInstalled(c)) return;
        WORKER.submit(() -> {
            try {
                LocalRebornEngine.warmUp(c);
                save("central_brain", "READY · " + LocalRebornEngine.backendName());
            } catch (Throwable e) {
                save("central_error", "warm: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    private static void append(String who, String text) {
        transcript += who + ": " + text + "\n";
        if (transcript.length() > 16000) transcript = transcript.substring(transcript.length() - 16000);
        save("central_transcript", transcript);
        save("central_customer", lastCustomer);
        save("central_reply", lastReply);
    }

    private static void save(String key, String value) {
        Context c = app;
        if (c == null) return;
        SharedPreferences p = c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE);
        p.edit().putString(key, value == null ? "" : value).putLong("updated", System.currentTimeMillis()).apply();
    }

    private static void publish() {
        save("central_stage", stage);
        save("central_latency", String.valueOf(lastLatencyMs));
        Listener l = listener;
        if (l != null) l.onCentralChanged();
        RebornCallActivity.refreshFromService();
    }

    public static String stage() { return stage; }
    public static String transcript() { return transcript; }
    public static String lastCustomer() { return lastCustomer; }
    public static String lastReply() { return lastReply; }
    public static long lastLatencyMs() { return lastLatencyMs; }
    public static boolean isBusy() { return busy; }
}
