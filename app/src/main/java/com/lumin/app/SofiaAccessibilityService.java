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
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SofiaAccessibilityService extends AccessibilityService {
    public static final String ACTION_SEND_REPLY = "com.lumin.app.SEND_REPLY";
    public static final String EXTRA_REPLY = "reply";

    private static final String AUTO_INTRO = "Olá, boa tarde. Sou a assistente virtual da MY POUPar+. É uma chamada rápida para ajudar a perceber se os seus serviços de energia ou telecomunicações continuam competitivos. Posso explicar em vinte segundos?";
    private static final long STABLE_MS = 1100L;
    private static final long DUPLICATE_WINDOW_MS = 7000L;

    private enum TurnState { IDLE, LISTENING, STABILIZING, THINKING, SENDING, WAITING_REMOTE, MANUAL }

    private final SofiaMemory memory = new SofiaMemory();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences diag;
    private SharedPreferences control;
    private String lastCustomer = "";
    private String lastProcessedCanonical = "";
    private long lastProcessedAt = 0L;
    private String pendingCandidate = "";
    private long pendingSince = 0L;
    private int pendingToken = 0;
    private String transcript = "";
    private long lastTextCallReadyAt = 0L;
    private long lastAutoOpenAttemptAt = 0L;
    private boolean autoIntroSent = false;
    private volatile TurnState turnState = TurnState.IDLE;

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SEND_REPLY.equals(intent.getAction())) return;
            String reply = intent.getStringExtra(EXTRA_REPLY);
            if (reply == null || reply.trim().isEmpty()) return;
            setTurnState(TurnState.SENDING);
            sendReply(reply.trim(), true);
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
        setTurnState(TurnState.IDLE);
        log("service", "ATIVO");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!"com.samsung.android.incallui".contentEquals(event.getPackageName())) return;

        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) {
            log("last_error", "samsung_root=null");
            return;
        }

        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) {
            if (tryOpenTextCall(root)) return;
            if (lastTextCallReadyAt > 0L && System.currentTimeMillis() - lastTextCallReadyAt > 5000L) resetSessionSurface();
            log("surface", "Samsung aberto; a procurar Text Call automaticamente");
            return;
        }

        lastTextCallReadyAt = System.currentTimeMillis();
        log("surface", "TEXT_CALL_READY");

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

        String customer = findCustomerCandidate(root, edit);
        if (customer.isEmpty()) return;
        queueStableCandidate(customer);
    }

    private void queueStableCandidate(String candidate) {
        String c = clean(candidate);
        if (c.length() < 2) return;
        String canonical = canonical(c);
        if (canonical.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (canonical.equals(lastProcessedCanonical) && now - lastProcessedAt < DUPLICATE_WINDOW_MS) return;
        if (c.equals(memory.getLastAssistant())) return;

        if (!c.equals(pendingCandidate)) {
            pendingCandidate = c;
            pendingSince = now;
            pendingToken++;
            setTurnState(TurnState.STABILIZING);
        }

        final int token = pendingToken;
        main.removeCallbacksAndMessages("sofia_stable");
        main.postAtTime(() -> processIfStable(token), "sofia_stable", System.currentTimeMillis() + STABLE_MS);
    }

    private void processIfStable(int token) {
        if (token != pendingToken) return;
        if (turnState == TurnState.THINKING || turnState == TurnState.SENDING || turnState == TurnState.WAITING_REMOTE) return;
        if (System.currentTimeMillis() - pendingSince < STABLE_MS - 80L) return;

        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        if (root == null || edit == null) return;
        String current = clean(findCustomerCandidate(root, edit));
        if (current.isEmpty()) return;
        if (!canonical(current).equals(canonical(pendingCandidate))) {
            queueStableCandidate(current);
            return;
        }

        String stable = pendingCandidate;
        pendingCandidate = "";
        pendingSince = 0L;
        processCustomerTurn(stable);
    }

    private void processCustomerTurn(String customer) {
        String canonical = canonical(customer);
        if (canonical.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (canonical.equals(lastProcessedCanonical) && now - lastProcessedAt < DUPLICATE_WINDOW_MS) return;

        lastProcessedCanonical = canonical;
        lastProcessedAt = now;
        lastCustomer = customer;
        control.edit().putString("live_customer", customer).apply();
        log("last_customer", customer);
        appendTranscript("Cliente", customer);
        SofiaEngine.learnFreeText(customer, memory);

        String currentMode = mode();
        if ("MANUAL".equals(currentMode)) {
            setTurnState(TurnState.MANUAL);
            control.edit().putString("suggested_reply", "").apply();
            return;
        }

        SofiaEngine.Decision d = SofiaEngine.fastDecision(customer, memory);
        if (d != null) {
            log("path", "FAST_PATH");
            handleGeneratedReply(d.reply, d.handoff, d.stage, currentMode);
            return;
        }

        setTurnState(TurnState.THINKING);
        control.edit().putString("suggested_reply", "").apply();
        log("path", "QWEN");
        final String prompt = SofiaEngine.buildPrompt(customer, memory);
        worker.submit(() -> {
            try {
                String generated = QwenClient.generate(prompt);
                if (generated == null || generated.trim().isEmpty()) generated = fallback();
                final String reply = sanitizeReply(generated);
                log("qwen", "OK · " + LocalRebornEngine.lastGenerationMs() + " ms");
                handleGeneratedReply(reply, false, "QUALIFICATION", currentMode);
            } catch (Exception e) {
                log("qwen", "ERRO: " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
                handleGeneratedReply(fallback(), false, "QUALIFICATION", currentMode);
            }
        });
    }

    private void captureManualCustomer(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        String c = clean(findCustomerCandidate(root, edit));
        if (c.isEmpty() || canonical(c).equals(canonical(lastCustomer))) return;
        lastCustomer = c;
        control.edit().putString("live_customer", c).apply();
    }

    private boolean tryOpenTextCall(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - lastAutoOpenAttemptAt < 1200L) return false;
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

    private void handleGeneratedReply(String reply, boolean handoff, String stage, String currentMode) {
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
        if (handoff) syncNow("interested", stage, true);
    }

    private String mode() {
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        return control.getString("mode", "AUTO");
    }

    private String fallback() {
        return "Percebi. Diga-me só, por favor, qual é a principal coisa que gostaria de melhorar no serviço atual.";
    }

    private void appendTranscript(String who, String text) {
        transcript += who + ": " + text + "\n";
        if (transcript.length() > 12000) transcript = transcript.substring(transcript.length() - 12000);
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        control.edit().putString("live_transcript", transcript).putString("live_customer", lastCustomer).apply();
    }

    private String findCustomerCandidate(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts, edit);
        for (int i = texts.size() - 1; i >= 0; i--) {
            String s = clean(texts.get(i));
            if (s.length() < 2 || s.length() > 280) continue;
            String l = s.toLowerCase(Locale.ROOT);
            if (isSamsungChrome(l)) continue;
            if (canonical(s).equals(canonical(memory.getLastAssistant()))) continue;
            return s;
        }
        return "";
    }

    private boolean isSamsungChrome(String l) {
        return l.contains("mudar para chamada") || l.contains("desligar") || l.contains("teclado") ||
                l.contains("altifalante") || l.contains("adicionar chamada") || l.contains("bluetooth") ||
                l.equals("escrever") || l.contains("chamada de texto") || l.equals("mais") ||
                l.equals("repetir") || l.equals("reproduzir") || l.equals("parar") || l.equals("cancelar") ||
                l.equals("enviar") || l.equals("responder") || l.equals("voltar") || l.equals("fechar") ||
                l.contains("urgente") || l.contains("ligar-lhe mais tarde") || l.contains("ligar lhe mais tarde") ||
                l.contains("quem fala") || l.contains("mensagem sugerida") || l.contains("sugestão") ||
                l.contains("assistente de chamada") || l.contains("assistente de voz") ||
                l.contains("converter a sua voz em texto") || l.contains("se quiser continuar") ||
                l.contains("mantenha-se em linha") || l.matches("\\d{1,2}:\\d{2}") ||
                l.matches("\\d{1,2}:\\d{2}:\\d{2}") || l.contains("minutos") || l.contains("segundos");
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> out, AccessibilityNodeInfo edit) {
        if (node == null) return;
        if (node != edit && node.getText() != null && !node.isEditable()) out.add(node.getText().toString());
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), out, edit);
    }

    private void sendReply(String reply, boolean userApproved) {
        main.post(() -> {
            AccessibilityNodeInfo root = findSamsungRoot();
            if (root == null) { failSend("send: Samsung Text Call não está acessível"); return; }
            AccessibilityNodeInfo edit = findEditable(root);
            if (edit == null) { failSend("send: edit=null"); return; }

            lastTextCallReadyAt = System.currentTimeMillis();
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
            boolean set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            log("last_reply", reply);
            log("set_text", String.valueOf(set));
            if (!set) { failSend("SET_TEXT_FAILED"); return; }

            if (userApproved) {
                appendTranscript("REBORN", reply);
                memory.setLastAssistant(reply);
            }
            main.postDelayed(() -> pressSend(reply, 0), 260L);
        });
    }

    private void pressSend(String expectedReply, int attempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) { failSend("pressSend: samsung_root=null"); return; }
        AccessibilityNodeInfo edit = findEditable(root);
        AccessibilityNodeInfo send = findSendButton(root);
        if (send == null && edit != null) send = findSendButtonNearEditor(root, edit);

        if (attempt == 0 && send != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(send);
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("send", "CLICK_SENT");
                main.postDelayed(() -> verifySent(expectedReply, 1), 450L);
                return;
            }
        }

        if (send != null) {
            Rect r = new Rect();
            send.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                tapBounds(r, "GESTURE_SEND");
                main.postDelayed(() -> verifySent(expectedReply, attempt + 1), 500L);
                return;
            }
        }

        if (attempt <= 1 && edit != null && Build.VERSION.SDK_INT >= 30) {
            boolean enter = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            log("send", enter ? "IME_ENTER" : "IME_ENTER_FAILED");
            main.postDelayed(() -> verifySent(expectedReply, attempt + 1), 450L);
            return;
        }

        if (edit != null) {
            tapSendRightOfEditor(edit);
            main.postDelayed(() -> verifySent(expectedReply, attempt + 1), 550L);
            return;
        }
        failSend("FAILED_NO_SEND_ACTION");
    }

    private void verifySent(String expectedReply, int nextAttempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        String current = edit == null || edit.getText() == null ? "" : edit.getText().toString().trim();
        if (current.isEmpty() || !current.equals(expectedReply.trim())) {
            log("send", "SEND_CONFIRMED");
            if (AUTO_INTRO.equals(expectedReply)) log("intro", "SENT");
            control.edit().putString("suggested_reply", "").apply();
            setTurnState(TurnState.WAITING_REMOTE);
            main.postDelayed(() -> {
                if (!"MANUAL".equals(mode())) setTurnState(TurnState.LISTENING);
            }, 850L);
            return;
        }
        log("send", "NOT_SENT_ATTEMPT_" + nextAttempt);
        if (nextAttempt <= 2) pressSend(expectedReply, nextAttempt);
        else {
            if (AUTO_INTRO.equals(expectedReply)) autoIntroSent = false;
            failSend("A mensagem ficou no campo Samsung; botão de envio não acionado");
        }
    }

    private void failSend(String reason) {
        log("last_error", reason);
        log("send", "FAILED");
        setTurnState("MANUAL".equals(mode()) ? TurnState.MANUAL : TurnState.LISTENING);
    }

    private void tapBounds(Rect r, String label) {
        Path path = new Path();
        path.moveTo(r.centerX(), r.centerY());
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 80);
        boolean dispatched = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        log("send", dispatched ? label : label + "_FAILED");
    }

    private void tapSendRightOfEditor(AccessibilityNodeInfo edit) {
        Rect r = new Rect();
        edit.getBoundsInScreen(r);
        if (r.isEmpty()) { failSend("EDITOR_BOUNDS_EMPTY"); return; }
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        float x = Math.min(screenWidth - dp(28), r.right + dp(34));
        float y = r.centerY();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 90);
        boolean dispatched = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        log("send", dispatched ? "EDITOR_RIGHT_FALLBACK" : "EDITOR_RIGHT_FALLBACK_FAILED");
    }

    private AccessibilityNodeInfo findSendButtonNearEditor(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        Rect editor = new Rect();
        edit.getBoundsInScreen(editor);
        if (editor.isEmpty()) return null;
        AccessibilityNodeInfo[] best = new AccessibilityNodeInfo[1];
        int[] bestScore = new int[]{Integer.MAX_VALUE};
        findSendCandidateRecursive(root, editor, best, bestScore);
        if (best[0] != null) log("send_target", "GEOMETRY");
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
            boolean rightSide = cx >= editor.centerX();
            boolean sameRow = cy >= editor.top - dp(70) && cy <= editor.bottom + dp(70);
            boolean sensibleSize = r.width() >= dp(20) && r.height() >= dp(20) && r.width() <= dp(180) && r.height() <= dp(180);
            if (rightSide && sameRow && sensibleSize) {
                int score = Math.abs(cx - editor.right) + Math.abs(cy - editor.centerY()) * 2;
                if (score < bestScore[0]) { bestScore[0] = score; best[0] = node; }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) findSendCandidateRecursive(node.getChild(i), editor, best, bestScore);
    }

    private AccessibilityNodeInfo findSamsungRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (isSamsungRoot(active)) return active;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = window == null ? null : window.getRoot();
                    if (isSamsungRoot(root)) return root;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isSamsungRoot(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null && "com.samsung.android.incallui".contentEquals(root.getPackageName());
    }

    private AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
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

    private void syncNow(String result, String stage, boolean handoff) {
        worker.submit(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("phone_number", memory.has("phone_number") ? memory.get("phone_number") : "unknown");
                payload.put("client_name", memory.has("client_name") ? memory.get("client_name") : "Cliente REBORN");
                payload.put("result", result);
                payload.put("facts", memory.toJson());
                JSONObject feedback = new JSONObject();
                feedback.put("summary", "Chamada qualificada automaticamente pelo REBORN");
                feedback.put("interest_level", handoff ? "high" : "medium");
                feedback.put("next_action", handoff ? "MyPoupar handoff" : "follow_up");
                feedback.put("intent", handoff ? "INTERESTED" : "CONTINUE");
                feedback.put("stage", stage);
                feedback.put("transcript", transcript);
                payload.put("feedback", feedback);
                payload.put("handoff_requested", handoff);
                SupabaseSyncClient.sync(this, payload);
            } catch (Exception e) {
                log("sync", "ERRO: " + safe(e.getMessage()));
            }
        });
    }

    private void setTurnState(TurnState state) {
        turnState = state;
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        control.edit().putString("turn_state", state.name()).apply();
        log("turn_state", state.name());
    }

    private void resetSessionSurface() {
        autoIntroSent = false;
        lastCustomer = "";
        lastProcessedCanonical = "";
        pendingCandidate = "";
        transcript = "";
        memory.setLastAssistant("");
        setTurnState(TurnState.IDLE);
    }

    private String sanitizeReply(String text) {
        if (text == null) return fallback();
        String out = text.trim();
        out = out.replaceAll("^(SOFIA|Sofia|REBORN|Reborn)\\s*:\\s*", "");
        if (out.length() > 420) out = out.substring(0, 420).trim();
        return out.isEmpty() ? fallback() : out;
    }

    private String canonical(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", " ")
                .trim();
    }

    private String clean(String s) { return s == null ? "" : s.replace('\n', ' ').replaceAll("\\s+", " ").trim(); }
    private void log(String key, String value) {
        if (diag == null) diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        diag.edit().putString(key, value == null ? "" : value).putLong("updated", System.currentTimeMillis()).apply();
    }
    private String lower(CharSequence s) { return s == null ? "" : s.toString().toLowerCase(Locale.ROOT); }
    private String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private String safe(String s) { return s == null ? "" : s.replace('\n', ' '); }

    @Override public void onInterrupt() { log("service", "INTERRUPTED"); }

    @Override public void onDestroy() {
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
