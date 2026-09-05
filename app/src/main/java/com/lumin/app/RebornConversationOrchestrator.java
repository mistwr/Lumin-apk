package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conversation brain for REBORN.
 *
 * This class deliberately knows nothing about Samsung UI, Accessibility,
 * edit fields or send buttons. The Samsung bridge only supplies a stable
 * customer utterance and receives one final reply.
 */
public final class RebornConversationOrchestrator {
    public interface Callback {
        void onThinking();
        void onReply(String reply, boolean assisted);
        void onError(String message);
    }

    private final Context app;
    private final SofiaMemory memory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences diag;

    private volatile boolean busy;
    private String lastInput = "";
    private String lastReply = "";

    public RebornConversationOrchestrator(Context context, SofiaMemory memory) {
        this.app = context.getApplicationContext();
        this.memory = memory;
        this.diag = app.getSharedPreferences("sofia_diag", Context.MODE_PRIVATE);
    }

    public boolean isBusy() { return busy; }
    public String lastReply() { return lastReply; }

    public synchronized boolean acceptStableTurn(String customer, String mode, Callback callback) {
        String clean = customer == null ? "" : customer.trim();
        if (clean.isEmpty() || busy) return false;
        if (clean.equals(lastInput)) return false;

        lastInput = clean;
        SofiaEngine.learnFreeText(clean, memory);

        SofiaEngine.Decision fast = SofiaEngine.fastDecision(clean, memory);
        if (fast != null && fast.reply != null && !fast.reply.trim().isEmpty()) {
            String reply = sanitize(fast.reply);
            remember(reply);
            diag.edit().putString("brain_path", "FAST_PATH").apply();
            callback.onReply(reply, "ASSISTED".equals(mode));
            return true;
        }

        busy = true;
        diag.edit().putString("brain_path", "QWEN").apply();
        callback.onThinking();
        final String prompt = SofiaEngine.buildPrompt(clean, memory);

        worker.submit(() -> {
            try {
                String generated = QwenClient.generate(prompt);
                String reply = sanitize(generated);
                remember(reply);
                diag.edit()
                        .putString("brain_path", "QWEN")
                        .putString("qwen", "OK · " + LocalRebornEngine.lastGenerationMs() + " ms")
                        .apply();
                main.post(() -> {
                    busy = false;
                    callback.onReply(reply, "ASSISTED".equals(mode));
                });
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                String reply = fallback();
                remember(reply);
                diag.edit().putString("qwen", "ERRO · " + msg).apply();
                main.post(() -> {
                    busy = false;
                    callback.onError(msg);
                    callback.onReply(reply, "ASSISTED".equals(mode));
                });
            }
        });
        return true;
    }

    public synchronized void setAssistantMessage(String text) {
        remember(sanitize(text));
    }

    public synchronized void reset() {
        busy = false;
        lastInput = "";
        lastReply = "";
        memory.setLastAssistant("");
        try { LocalRebornEngine.resetConversation(); } catch (Throwable ignored) {}
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private void remember(String reply) {
        lastReply = reply;
        memory.setLastAssistant(reply);
    }

    private String sanitize(String text) {
        String out = text == null ? "" : text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        out = out.replaceFirst("(?i)^(SOFIA|REBORN)\\s*:\\s*", "");
        if (out.length() > 420) out = out.substring(0, 420).trim();
        return out.isEmpty() ? fallback() : out;
    }

    private String fallback() {
        return "Percebi. Diga-me só, por favor, qual é a principal coisa que gostaria de melhorar no serviço atual.";
    }
}
