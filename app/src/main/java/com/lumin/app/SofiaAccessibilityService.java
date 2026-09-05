package com.lumin.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SofiaAccessibilityService extends AccessibilityService {
    public static final String ACTION_SEND_REPLY = "com.lumin.app.SEND_REPLY";
    public static final String EXTRA_REPLY = "reply";

    private static final String SAMSUNG_INCALL = "com.samsung.android.incallui";
    private static final String AUTO_INTRO = "Olá, boa tarde. Sou a assistente virtual da MY POUPar+. É uma chamada rápida para ajudar a perceber se os seus serviços de energia ou telecomunicações continuam competitivos. Posso explicar em vinte segundos?";
    private static final long POLL_MS = 300L;
    private static final long STABLE_MS = 1050L;
    private static final long DUPLICATE_WINDOW_MS = 8000L;
    private static final long SESSION_GONE_MS = 5000L;

    private enum TurnState { IDLE, LISTENING, STABILIZING, THINKING, SENDING, WAITING_REMOTE, MANUAL }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SofiaMemory memory = new SofiaMemory();

    private SharedPreferences diag;
    private SharedPreferences control;
    private volatile TurnState turnState = TurnState.IDLE;
    private boolean destroyed = false;
    private boolean autoIntroSent = false;
    private long lastSamsungSeenAt = 0L;
    private long lastTextCallReadyAt = 0L;
    private long lastAutoOpenAttemptAt = 0L;
    private String observedCandidate = "";
    private long observedChangedAt = 0L;
    private String lastProcessedCanonical = "";
    private long lastProcessedAt = 0L;
    private String lastCustomer = "";
    private String transcript = "";

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SEND_REPLY.equals(intent.getAction())) return;
            String reply = intent.getStringExtra(EXTRA_REPLY);
            if (reply == null || reply.trim().isEmpty()) return;
            setTurnState(TurnState.SENDING);
            sendReply(reply.trim(), true);
        }
    };

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try { inspectSamsungSurface("POLL"); }
            catch (Throwable t) { log("poll_error", t.getClass().getSimpleName() + ": " + safe(t.getMessage())); }
            main.postDelayed(this, POLL_MS);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (!control.contains("mode")) control.edit().putString("mode", "AUTO").apply();
        IntentFilter filter = new IntentFilter(ACTION_SEND_REPLY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(commandReceiver, filter);
        destroyed = false;
        setTurnState(TurnState.IDLE);
        log("service", "ATIVO · POLLING 300ms");
        main.removeCallbacks(poller);
        main.post(poller);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!SAMSUNG_INCALL.contentEquals(event.getPackageName())) return;
        inspectSamsungSurface("EVENT");
    }

    private void inspectSamsungSurface(String source) {
        AccessibilityNodeInfo root = findSamsungRoot();
        long now = System.currentTimeMillis();
        if (root == null) {
            if (lastSamsungSeenAt > 0 && now - lastSamsungSeenAt > SESSION_GONE_MS) resetSessionSurface();
            return;
        }
        lastSamsungSeenAt = now;

        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) {
            tryOpenTextCall(root);
            log("surface", "Samsung aberto · à procura de Text Call · " + source);
            return;
        }

        lastTextCallReadyAt = now;
        log("surface", "TEXT_CALL_READY · " + source);
        String currentMode = mode();

        if ("MANUAL".equals(currentMode)) {
            setTurnState(TurnState.MANUAL);
            captureManualCustomer(root, edit);
            return;
        }

        if (turnState == TurnState.IDLE || turnState == TurnState.MANUAL) setTurnState(TurnState.LISTENING);

        if ("AUTO".equals(currentMode) && !autoIntroSent) {
            autoIntroSent = true;
            memory.setLastAssistant(AUTO_INTRO);
            appendTranscript("REBORN", AUTO_INTRO);
            control.edit().putString("suggested_reply", AUTO_INTRO).apply();
            log("path", "AUTO_INTRO");
            setTurnState(TurnState.SENDING);
            sendReply(AUTO_INTRO, false);
            return;
        }

        if (turnState == TurnState.THINKING || turnState == TurnState.SENDING || turnState == TurnState.WAITING_REMOTE) return;

        String candidate = clean(findCustomerCandidate(root, edit));
        if (candidate.isEmpty()) {
            if (turnState == TurnState.STABILIZING) setTurnState(TurnState.LISTENING);
            observedCandidate = "";
            observedChangedAt = 0L;
            return;
        }

        String canon = canonical(candidate);
        if (canon.equals(canonical(memory.getLastAssistant()))) return;
        if (canon.equals(lastProcessedCanonical) && now - lastProcessedAt < DUPLICATE_WINDOW_MS) {
            if (turnState == TurnState.STABILIZING) setTurnState(TurnState.LISTENING);
            return;
        }

        if (!canon.equals(canonical(observedCandidate))) {
            observedCandidate = candidate;
            observedChangedAt = now;
            control.edit().putString("live_customer_partial", candidate).apply();
            setTurnState(TurnState.STABILIZING);
            log("stable", "NOVO: " + candidate);
            return;
        }

        if (observedChangedAt > 0 && now - observedChangedAt >= STABLE_MS) {
            String stable = observedCandidate;
            observedCandidate = "";
            observedChangedAt = 0L;
            processCustomerTurn(stable);
        }
    }

    private void processCustomerTurn(String customer) {
        String canon = canonical(customer);
        if (canon.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (canon.equals(lastProcessedCanonical) && now - lastProcessedAt < DUPLICATE_WINDOW_MS) return;

        lastProcessedCanonical = canon;
        lastProcessedAt = now;
        lastCustomer = customer;
        control.edit().putString("live_customer", customer).putString("live_customer_partial", "").apply();
        log("last_customer", customer);
        appendTranscript("Cliente", customer);
        SofiaEngine.learnFreeText(customer, memory);

        String currentMode = mode();
        if ("MANUAL".equals(currentMode)) {
            setTurnState(TurnState.MANUAL);
            return;
        }

        SofiaEngine.Decision fast = SofiaEngine.fastDecision(customer, memory);
        if (fast != null) {
            log("path", "FAST_PATH");
            handleGeneratedReply(fast.reply, currentMode);
            return;
        }

        setTurnState(TurnState.THINKING);
        control.edit().putString("suggested_reply", "").apply();
        log("path", "QWEN");
        final String prompt = SofiaEngine.buildPrompt(customer, memory);
        worker.submit(() -> {
            try {
                String generated = QwenClient.generate(prompt);
                String reply = sanitizeReply(generated);
                log("qwen", "OK · " + LocalRebornEngine.lastGenerationMs() + " ms");
                main.post(() -> handleGeneratedReply(reply, currentMode));
            } catch (Throwable e) {
                log("qwen", "ERRO: " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
                main.post(() -> handleGeneratedReply(fallback(), currentMode));
            }
        });
    }

    private void handleGeneratedReply(String reply, String currentMode) {
        String cleanReply = sanitizeReply(reply);
        memory.setLastAssistant(cleanReply);
        control.edit().putString("suggested_reply", cleanReply).apply();
        if ("ASSISTED".equals(currentMode)) {
            setTurnState(TurnState.LISTENING);
            log("send", "WAITING_USER_APPROVAL");
            return;
        }
        appendTranscript("REBORN", cleanReply);
        setTurnState(TurnState.SENDING);
        sendReply(cleanReply, false);
    }

    private void captureManualCustomer(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        String c = clean(findCustomerCandidate(root, edit));
        if (c.isEmpty()) return;
        String canon = canonical(c);
        if (canon.equals(canonical(lastCustomer))) return;
        lastCustomer = c;
        control.edit().putString("live_customer", c).apply();
    }

    private boolean tryOpenTextCall(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - lastAutoOpenAttemptAt < 1400L) return false;
        AccessibilityNodeInfo target = findTextCallEntry(root);
        if (target == null) return false;
        AccessibilityNodeInfo clickable = clickableSelfOrParent(target);
        if (clickable == null) return false;
        lastAutoOpenAttemptAt = now;
        boolean clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        log("auto_open", clicked ? "TEXT_CALL_CLICKED" : "TEXT_CALL_CLICK_FAILED");
        return clicked;
    }

    private AccessibilityNodeInfo findTextCallEntry(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = lower(node.getText());
        String desc = lower(node.getContentDescription());
        String id = lower(node.getViewIdResourceName());
        boolean match = text.contains("chamada de texto") || text.contains("text call") || text.contains("bixby text call") ||
                desc.contains("chamada de texto") || desc.contains("text call") || desc.contains("bixby text call") ||
                id.contains("text_call") || id.contains("bixby_text_call");
        if (match) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findTextCallEntry(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private String findCustomerCandidate(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts, edit);
        for (int i = texts.size() - 1; i >= 0; i--) {
            String s = clean(texts.get(i));
            if (s.length() < 2 || s.length() > 300) continue;
            String l = canonical(s);
            if (isSamsungChrome(l)) continue;
            if (l.equals(canonical(memory.getLastAssistant()))) continue;
            if (l.equals(canonical(AUTO_INTRO))) continue;
            return s;
        }
        return "";
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> out, AccessibilityNodeInfo edit) {
        if (node == null) return;
        if (node != edit && !node.isEditable() && node.getText() != null) out.add(node.getText().toString());
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), out, edit);
    }

    private boolean isSamsungChrome(String l) {
        return l.isEmpty() || l.equals("escrever resposta") || l.equals("escrever") || l.equals("enviar") || l.equals("send") ||
                l.equals("repetir") || l.equals("urgente") || l.equals("quem fala") || l.equals("mais") ||
                l.contains("ligar lhe mais tarde") || l.contains("chamada de texto") || l.contains("text call") ||
                l.contains("mudar para chamada") || l.contains("assistente de chamada") || l.contains("assistente de voz") ||
                l.contains("converter a sua voz em texto") || l.contains("mantenha se em linha") || l.contains("se quiser continuar") ||
                l.contains("desligar") || l.contains("teclado") || l.contains("altifalante") || l.contains("bluetooth") ||
                l.contains("adicionar chamada") || l.contains("mensagem sugerida") || l.contains("sugestao") ||
                l.matches("\\d{1,2} \\d{2}") || l.matches("\\d{1,2} \\d{2} \\d{2}");
    }

    private void sendReply(String reply, boolean userApproved) {
        main.post(() -> {
            AccessibilityNodeInfo root = findSamsungRoot();
            AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
            if (root == null || edit == null) {
                failSend("Samsung Text Call/editor não acessível");
                return;
            }

            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
            boolean set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            log("last_reply", reply);
            log("set_text", String.valueOf(set));
            if (!set) {
                failSend("SET_TEXT_FAILED");
                return;
            }
            if (userApproved) {
                memory.setLastAssistant(reply);
                appendTranscript("REBORN", reply);
            }
            main.postDelayed(() -> pressSend(reply, 0), 260L);
        });
    }

    private void pressSend(String expectedReply, int attempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        if (root == null || edit == null) {
            failSend("pressSend sem Samsung/editor");
            return;
        }

        AccessibilityNodeInfo send = findSendButton(root);
        if (send == null) send = findSendButtonNearEditor(root, edit);
        if (send != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(send);
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("send", "CLICK_SENT");
                main.postDelayed(() -> verifySent(expectedReply, attempt + 1), 500L);
                return;
            }
            Rect r = new Rect();
            send.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                tapBounds(r, "GESTURE_SEND");
                main.postDelayed(() -> verifySent(expectedReply, attempt + 1), 550L);
                return;
            }
        }

        if (attempt == 0 && Build.VERSION.SDK_INT >= 30) {
            boolean enter = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            log("send", enter ? "IME_ENTER" : "IME_ENTER_FAILED");
            main.postDelayed(() -> verifySent(expectedReply, 1), 500L);
            return;
        }

        tapSendRightOfEditor(edit);
        main.postDelayed(() -> verifySent(expectedReply, attempt + 1), 600L);
    }

    private void verifySent(String expectedReply, int nextAttempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        String current = edit == null || edit.getText() == null ? "" : edit.getText().toString().trim();
        if (current.isEmpty() || !current.equals(expectedReply.trim())) {
            log("send", "SEND_CONFIRMED");
            control.edit().putString("suggested_reply", "").apply();
            setTurnState(TurnState.WAITING_REMOTE);
            main.postDelayed(() -> {
                if (!"MANUAL".equals(mode())) setTurnState(TurnState.LISTENING);
            }, 800L);
            return;
        }
        if (nextAttempt <= 2) {
            log("send", "NOT_SENT_ATTEMPT_" + nextAttempt);
            pressSend(expectedReply, nextAttempt);
        } else {
            if (AUTO_INTRO.equals(expectedReply)) autoIntroSent = false;
            failSend("Mensagem ficou no editor Samsung; envio não confirmado");
        }
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = lower(node.getText());
        String desc = lower(node.getContentDescription());
        String id = lower(node.getViewIdResourceName());
        if (text.contains("enviar") || text.equals("send") || desc.contains("enviar") || desc.contains("send") ||
                id.contains("send") || id.contains("enter") || id.contains("text_call_send") || id.contains("message_send")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findSendButton(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findSendButtonNearEditor(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        Rect editor = new Rect();
        edit.getBoundsInScreen(editor);
        if (editor.isEmpty()) return null;
        AccessibilityNodeInfo[] best = new AccessibilityNodeInfo[1];
        int[] score = new int[]{Integer.MAX_VALUE};
        findSendCandidateRecursive(root, editor, best, score);
        return best[0];
    }

    private void findSendCandidateRecursive(AccessibilityNodeInfo node, Rect editor, AccessibilityNodeInfo[] best, int[] bestScore) {
        if (node == null) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        AccessibilityNodeInfo clickable = clickableSelfOrParent(node);
        if (!r.isEmpty() && clickable != null) {
            int cx = r.centerX();
            int cy = r.centerY();
            boolean right = cx >= editor.centerX();
            boolean row = cy >= editor.top - dp(70) && cy <= editor.bottom + dp(70);
            boolean size = r.width() >= dp(20) && r.height() >= dp(20) && r.width() <= dp(180) && r.height() <= dp(180);
            if (right && row && size) {
                int s = Math.abs(cx - editor.right) + Math.abs(cy - editor.centerY()) * 2;
                if (s < bestScore[0]) { bestScore[0] = s; best[0] = node; }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) findSendCandidateRecursive(node.getChild(i), editor, best, bestScore);
    }

    private void tapBounds(Rect r, String label) {
        if (Build.VERSION.SDK_INT < 24) return;
        Path path = new Path();
        path.moveTo(r.centerX(), r.centerY());
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 80);
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        log("send", ok ? label : label + "_FAILED");
    }

    private void tapSendRightOfEditor(AccessibilityNodeInfo edit) {
        Rect r = new Rect();
        edit.getBoundsInScreen(r);
        if (r.isEmpty()) { failSend("EDITOR_BOUNDS_EMPTY"); return; }
        int width = getResources().getDisplayMetrics().widthPixels;
        float x = Math.min(width - dp(26), r.right + dp(34));
        float y = r.centerY();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 90);
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        log("send", ok ? "EDITOR_RIGHT_FALLBACK" : "EDITOR_RIGHT_FALLBACK_FAILED");
    }

    private AccessibilityNodeInfo findSamsungRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (isSamsungRoot(active)) return active;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo root = w == null ? null : w.getRoot();
                    if (isSamsungRoot(root)) return root;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isSamsungRoot(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null && SAMSUNG_INCALL.contentEquals(root.getPackageName());
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private void appendTranscript(String who, String text) {
        transcript += who + ": " + text + "\n";
        if (transcript.length() > 12000) transcript = transcript.substring(transcript.length() - 12000);
        control.edit().putString("live_transcript", transcript).apply();
    }

    private String mode() {
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        return control.getString("mode", "AUTO");
    }

    private void setTurnState(TurnState state) {
        if (turnState == state) return;
        turnState = state;
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        control.edit().putString("turn_state", state.name()).apply();
        log("turn_state", state.name());
    }

    private void failSend(String reason) {
        log("last_error", reason);
        log("send", "FAILED");
        setTurnState("MANUAL".equals(mode()) ? TurnState.MANUAL : TurnState.LISTENING);
    }

    private void resetSessionSurface() {
        if (turnState == TurnState.IDLE && transcript.isEmpty()) return;
        autoIntroSent = false;
        observedCandidate = "";
        observedChangedAt = 0L;
        lastProcessedCanonical = "";
        lastProcessedAt = 0L;
        lastCustomer = "";
        transcript = "";
        memory.setLastAssistant("");
        control.edit().putString("live_customer", "").putString("live_customer_partial", "").putString("suggested_reply", "").putString("live_transcript", "").apply();
        setTurnState(TurnState.IDLE);
        lastSamsungSeenAt = 0L;
        lastTextCallReadyAt = 0L;
    }

    private String fallback() {
        return "Percebi. Diga-me só, por favor, qual é a principal coisa que gostaria de melhorar no serviço atual.";
    }

    private String sanitizeReply(String text) {
        if (text == null) return fallback();
        String out = clean(text).replaceFirst("(?i)^(SOFIA|REBORN)\\s*:\\s*", "");
        if (out.length() > 420) out = out.substring(0, 420).trim();
        return out.isEmpty() ? fallback() : out;
    }

    private String canonical(String s) {
        if (s == null) return "";
        String x = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return x.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9€]+", " ").trim();
    }

    private String clean(String s) { return s == null ? "" : s.replace('\n', ' ').replaceAll("\\s+", " ").trim(); }
    private String lower(CharSequence s) { return s == null ? "" : s.toString().toLowerCase(Locale.ROOT); }
    private String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
    private String safe(String s) { return s == null ? "" : s.replace('\n', ' '); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private void log(String key, String value) {
        if (diag == null) diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        diag.edit().putString(key, value == null ? "" : value).putLong("updated", System.currentTimeMillis()).apply();
    }

    @Override public void onInterrupt() { log("service", "INTERRUPTED"); }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(commandReceiver); } catch (Throwable ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }
}
