package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** REBORN conversation brain. It never touches Samsung UI. */
public final class RebornConversationOrchestrator {
    public interface Callback {
        void onThinking();
        void onReply(String reply, boolean assisted);
        void onError(String message);
    }

    private static final String IGNORE = "<IGNORE>";

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
        if (canonical(clean).equals(canonical(lastInput))) return false;

        lastInput = clean;
        final boolean assisted = "ASSISTED".equals(mode);

        // Only use deterministic fast-path when the utterance contains clear conversational signal.
        // This prevents random TV/radio fragments from triggering scripted sales answers.
        if (highSignalTurn(clean)) {
            SofiaEngine.learnFreeText(clean, memory);
            SofiaEngine.Decision fast = SofiaEngine.fastDecision(clean, memory);
            if (fast != null && fast.reply != null && !fast.reply.trim().isEmpty()) {
                String reply = sanitize(fast.reply);
                remember(reply);
                diag.edit().putString("brain_path", "FAST_PATH_SIGNALLED").apply();
                callback.onReply(reply, assisted);
                return true;
            }
        }

        busy = true;
        callback.onThinking();
        diag.edit().putString("brain_path", "QWEN_TURN_GATE").apply();

        final String previous = memory.getLastAssistant() == null ? "" : memory.getLastAssistant();
        final String salesPrompt = SofiaEngine.buildPrompt(clean, memory);
        final String prompt =
                "És o cérebro de uma chamada comercial REBORN/MyPoupar em português de Portugal. " +
                "A transcrição pode conter televisão, rádio ou outras pessoas ao fundo no lado do cliente. " +
                "Decide primeiro se a FRASE NOVA é plausivelmente dirigida à assistente ou é uma resposta à conversa. " +
                "Se parecer ruído ambiente, diálogo de TV/rádio, frase sem relação com a pergunta anterior ou fragmento aleatório, " +
                "responde EXATAMENTE " + IGNORE + " e nada mais. " +
                "Se for plausivelmente o cliente, responde naturalmente e segue o guião, sem repetir a mesma pergunta.\n" +
                "ÚLTIMA FALA DA ASSISTENTE: " + previous + "\n" +
                "FRASE NOVA: " + clean + "\n\n" + salesPrompt;

        worker.submit(() -> {
            try {
                String generated = QwenClient.generate(prompt);
                if (isIgnore(generated)) {
                    diag.edit()
                            .putString("brain_path", "AMBIENT_IGNORED")
                            .putString("qwen", "IGNORE · " + LocalRebornEngine.lastGenerationMs() + " ms")
                            .apply();
                    main.post(() -> {
                        busy = false;
                        callback.onReply("", assisted);
                    });
                    return;
                }

                SofiaEngine.learnFreeText(clean, memory);
                String reply = sanitize(generated);
                remember(reply);
                diag.edit()
                        .putString("brain_path", "QWEN_REPLY")
                        .putString("qwen", "OK · " + LocalRebornEngine.lastGenerationMs() + " ms")
                        .apply();
                main.post(() -> {
                    busy = false;
                    callback.onReply(reply, assisted);
                });
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                diag.edit().putString("qwen", "ERRO · " + msg).apply();
                main.post(() -> {
                    busy = false;
                    callback.onError(msg);
                    // On uncertainty do not speak over the client. Return to listening instead of inventing a fallback.
                    callback.onReply("", assisted);
                });
            }
        });
        return true;
    }

    public synchronized void setAssistantMessage(String text) {
        String clean = sanitize(text);
        if (!clean.isEmpty()) remember(clean);
    }

    public synchronized void reset() {
        busy = false;
        lastInput = "";
        lastReply = "";
        memory.setLastAssistant("");
        try { LocalRebornEngine.resetConversation(); } catch (Throwable ignored) {}
    }

    public void shutdown() { worker.shutdownNow(); }

    private boolean highSignalTurn(String s) {
        String x = canonical(s);
        if (x.matches(".*\\d.*")) return true;
        String[] signals = {
                "sim", "nao", "pode", "diga", "quero", "interessa", "interessado", "interessada",
                "quanto", "pago", "paga", "euros", "euro", "operador", "fidelizacao", "energia",
                "telecom", "internet", "telemovel", "televisao", "meo", "nos", "vodafone", "digi",
                "edp", "galp", "endesa", "iberdrola", "repsol", "ligue", "ligar", "mais tarde",
                "nao percebi", "repita", "quem fala", "porque", "como", "quando", "onde"
        };
        for (String signal : signals) {
            if (x.equals(signal) || x.startsWith(signal + " ") || x.endsWith(" " + signal) || x.contains(" " + signal + " ")) return true;
        }
        return false;
    }

    private boolean isIgnore(String text) {
        if (text == null) return false;
        String x = text.trim().toUpperCase(Locale.ROOT);
        return x.equals(IGNORE) || x.startsWith(IGNORE) || x.contains("<IGNORE>");
    }

    private void remember(String reply) {
        lastReply = reply;
        memory.setLastAssistant(reply);
    }

    private String sanitize(String text) {
        String out = text == null ? "" : text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        out = out.replaceFirst("(?i)^(SOFIA|REBORN)\\s*:\\s*", "");
        if (out.length() > 420) out = out.substring(0, 420).trim();
        return out;
    }

    private String canonical(String s) {
        if (s == null) return "";
        String x = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return x.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9€]+", " ").trim();
    }
}
