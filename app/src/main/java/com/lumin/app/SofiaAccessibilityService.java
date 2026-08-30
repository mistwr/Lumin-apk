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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SofiaAccessibilityService extends AccessibilityService {
    public static final String ACTION_SEND_REPLY = "com.lumin.app.SEND_REPLY";
    public static final String EXTRA_REPLY = "reply";

    private static final long POLL_MS = 250L;
    private static final long STABLE_MS = 650L;

    private final SofiaMemory memory = new SofiaMemory();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> recentCustomers = new ArrayList<>();
    private final Deque<String> customerQueue = new ArrayDeque<>();

    private String lastCustomer = "";
    private String transcript = "";
    private String observedCandidate = "";
    private long observedCandidateSince = 0L;
    private volatile boolean busy = false;
    private volatile boolean destroyed = false;
    private SharedPreferences diag;
    private SharedPreferences control;
    private long lastTextCallReadyAt = 0L;

    private final Runnable samsungWatcher = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try { scanSamsungSurface("POLL"); } catch (Throwable t) { log("watcher", "ERRO: " + safe(t.getMessage())); }
            main.postDelayed(this, POLL_MS);
        }
    };

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SEND_REPLY.equals(intent.getAction())) return;
            String reply = intent.getStringExtra(EXTRA_REPLY);
            if (reply == null || reply.trim().isEmpty()) return;
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
        log("service", "ATIVO · SAMSUNG DRIVER 58");
        main.removeCallbacks(samsungWatcher);
        main.post(samsungWatcher);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!"com.samsung.android.incallui".contentEquals(event.getPackageName())) return;
        scanSamsungSurface("EVENT");
    }

    private void scanSamsungSurface(String trigger) {
        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) {
            observedCandidate = "";
            observedCandidateSince = 0L;
            return;
        }

        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) {
            if (System.currentTimeMillis() - lastTextCallReadyAt > 2500L) log("surface", "Samsung aberto; à espera do Text Call");
            return;
        }

        lastTextCallReadyAt = System.currentTimeMillis();
        log("surface", "TEXT_CALL_READY");
        log("driver_trigger", trigger);

        String candidate = findCustomerCandidate(root, edit);
        if (candidate.isEmpty()) {
            observedCandidate = "";
            observedCandidateSince = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (!candidate.equals(observedCandidate)) {
            observedCandidate = candidate;
            observedCandidateSince = now;
            log("raw_candidate", candidate);
            log("stability", "0ms · WAITING");
            return;
        }

        long stableFor = now - observedCandidateSince;
        log("stability", stableFor + "ms" + (stableFor >= STABLE_MS ? " · FINAL" : " · WAITING"));
        if (stableFor < STABLE_MS) return;

        enqueueCustomer(candidate);
        observedCandidate = "";
        observedCandidateSince = 0L;
    }

    private synchronized void enqueueCustomer(String customer) {
        if (customer == null) return;
        customer = customer.trim();
        if (customer.isEmpty() || customer.equals(lastCustomer) || customer.equals(memory.getLastAssistant()) || recentCustomers.contains(customer)) return;
        if (customerQueue.contains(customer)) return;
        customerQueue.offer(customer);
        log("queue", String.valueOf(customerQueue.size()));
        log("path", busy ? "QUEUED_WHILE_AI_BUSY" : "QUEUED_STABLE_UTTERANCE");
        drainQueue();
    }

    private synchronized void drainQueue() {
        if (busy) return;
        String next = customerQueue.poll();
        log("queue", String.valueOf(customerQueue.size()));
        if (next == null || next.trim().isEmpty()) return;
        processCustomer(next);
    }

    private void processCustomer(String customer) {
        customer = customer == null ? "" : customer.trim();
        if (customer.isEmpty() || recentCustomers.contains(customer)) { drainQueue(); return; }

        lastCustomer = customer;
        recentCustomers.add(customer);
        if (recentCustomers.size() > 30) recentCustomers.remove(0);
        log("last_customer", customer);
        appendTranscript("Cliente", customer);
        SofiaEngine.learnFreeText(customer, memory);

        String mode = mode();
        if ("MANUAL".equals(mode)) {
            log("path", "MANUAL_CAPTURE");
            control.edit().putString("suggested_reply", "").apply();
            drainQueue();
            return;
        }

        SofiaEngine.Decision d = SofiaEngine.fastDecision(customer, memory);
        if (d != null) {
            log("path", "FAST_PATH");
            handleGeneratedReply(d.reply, d.handoff, d.stage, mode);
            drainQueue();
            return;
        }

        busy = true;
        log("path", "QWEN");
        final String prompt = SofiaEngine.buildPrompt(customer, memory);
        worker.submit(() -> {
            try {
                String reply = QwenClient.generate(prompt);
                reply = sanitizeReply(reply);
                if (reply.isEmpty()) reply = fallback();
                log("qwen", "OK");
                handleGeneratedReply(reply, false, "QUALIFICATION", mode);
            } catch (Exception e) {
                log("qwen", "ERRO: " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
                handleGeneratedReply(fallback(), false, "QUALIFICATION", mode);
            } finally {
                busy = false;
                main.post(this::drainQueue);
            }
        });
    }

    private String sanitizeReply(String reply) {
        if (reply == null) return "";
        String r = reply.trim();
        String l = r.toLowerCase(Locale.ROOT);
        if (l.contains("és a sofia") || l.contains("es a sofia") || l.contains("system prompt") ||
                l.contains("factos conhecidos") || l.contains("cliente disse:") || l.contains("responde numa frase") ||
                l.contains("consultora mypoupar. português de portugal") || l.contains("consultora mypoupar. portugues de portugal")) {
            log("guardrail", "PROMPT_LEAK_BLOCKED");
            return fallback();
        }
        if (r.length() > 320) r = r.substring(0, 320).trim();
        return r;
    }

    private void handleGeneratedReply(String reply, boolean handoff, String stage, String mode) {
        reply = sanitizeReply(reply);
        if (reply.isEmpty()) reply = fallback();
        memory.setLastAssistant(reply);
        control.edit().putString("suggested_reply", reply).apply();
        if ("ASSISTED".equals(mode)) {
            log("send", "WAITING_USER_APPROVAL");
            return;
        }
        appendTranscript("Sofia", reply);
        sendReply(reply, false);
        if (handoff) syncNow("interested", stage, true);
    }

    private String mode() {
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        return control.getString("mode", "AUTO");
    }

    private String fallback() {
        return "Certo. Para eu perceber melhor, diga-me só o que gostaria de melhorar no serviço atual.";
    }

    private void appendTranscript(String who, String text) {
        transcript += who + ": " + text + "\n";
        if (transcript.length() > 12000) transcript = transcript.substring(transcript.length() - 12000);
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        control.edit().putString("live_transcript", transcript).putString("live_customer", lastCustomer).apply();
    }

    private String findCustomerCandidate(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        List<NodeText> texts = new ArrayList<>();
        collectTexts(root, texts, edit);
        String lastAssistant = memory.getLastAssistant();
        Rect editRect = new Rect();
        edit.getBoundsInScreen(editRect);

        for (int i = texts.size() - 1; i >= 0; i--) {
            NodeText nt = texts.get(i);
            String s = nt.text.trim();
            if (s.length() < 2 || s.length() > 280) continue;
            String l = s.toLowerCase(Locale.ROOT);
            if (isSamsungChrome(l)) continue;
            if (s.equals(lastAssistant) || s.equals(lastCustomer) || recentCustomers.contains(s) || customerQueue.contains(s)) continue;
            if (looksLikePhoneNumber(s)) continue;

            // Conversation bubbles normally sit above the composer. Ignore labels below/inside composer region.
            if (!editRect.isEmpty() && !nt.bounds.isEmpty() && nt.bounds.top >= editRect.top - 12) continue;
            log("candidate_bounds", nt.bounds.flattenToString());
            log("candidate_class", nt.className);
            return s;
        }
        return "";
    }

    private boolean looksLikePhoneNumber(String s) {
        String compact = s.replace(" ", "").replace("+", "");
        return compact.matches("\\d{7,15}");
    }

    private boolean isSamsungChrome(String l) {
        return l.contains("mudar para chamada") || l.contains("desligar") || l.contains("teclado") ||
                l.contains("altifalante") || l.contains("adicionar chamada") || l.contains("bluetooth") ||
                l.equals("escrever") || l.contains("chamada de texto") || l.equals("mais") ||
                l.equals("repetir") || l.equals("reproduzir") || l.equals("parar") || l.equals("cancelar") ||
                l.equals("enviar") || l.equals("responder") || l.equals("voltar") || l.equals("fechar") ||
                l.contains("urgente") || l.contains("liga-lhe mais tarde") || l.contains("ligar-lhe mais tarde") ||
                l.contains("ligue-lhe mais tarde") || l.contains("quem fala") || l.contains("mensagem sugerida") ||
                l.contains("sugestão") || l.contains("assistente de chamada") ||
                l.contains("estou a utilizar um assistente de voz") || l.contains("converter a sua voz em texto") ||
                l.contains("responder-lhe. se quiser continuar") || l.contains("mantenha-se em linha") ||
                l.matches("\\d{1,2}:\\d{2}") || l.matches("\\d{1,2}:\\d{2}:\\d{2}") ||
                l.contains("minutos") || l.contains("segundos");
    }

    private void collectTexts(AccessibilityNodeInfo node, List<NodeText> out, AccessibilityNodeInfo edit) {
        if (node == null) return;
        if (node != edit && node.getText() != null && !node.isEditable()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            out.add(new NodeText(node.getText().toString(), r, node.getClassName() == null ? "" : node.getClassName().toString()));
        }
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), out, edit);
    }

    private static class NodeText {
        final String text;
        final Rect bounds;
        final String className;
        NodeText(String text, Rect bounds, String className) { this.text = text; this.bounds = bounds; this.className = className; }
    }

    private void sendReply(String reply, boolean userApproved) {
        final String safeReply = sanitizeReply(reply);
        if (safeReply.isEmpty()) return;
        main.post(() -> {
            AccessibilityNodeInfo root = findSamsungRoot();
            if (root == null) { log("last_error", "send: Samsung Text Call não está acessível"); return; }
            AccessibilityNodeInfo edit = findEditable(root);
            if (edit == null) { log("last_error", "send: edit=null"); return; }

            lastTextCallReadyAt = System.currentTimeMillis();
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, safeReply);
            boolean set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            log("last_reply", safeReply);
            log("set_text", String.valueOf(set));
            if (!set) { log("send", "SET_TEXT_FAILED"); return; }
            if (userApproved) {
                appendTranscript("Sofia", safeReply);
                memory.setLastAssistant(safeReply);
                control.edit().putString("suggested_reply", "").apply();
            }
            main.postDelayed(() -> pressSend(safeReply, 0), 100);
        });
    }

    private void pressSend(String expectedReply, int attempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) { log("last_error", "pressSend: samsung_root=null"); return; }
        AccessibilityNodeInfo edit = findEditable(root);
        AccessibilityNodeInfo send = findSendButton(root);

        if (attempt == 0 && send != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(send);
            if (clickable != null) clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            main.postDelayed(() -> verifySent(expectedReply, 1), 220);
            return;
        }
        if (attempt <= 1 && edit != null && Build.VERSION.SDK_INT >= 30) {
            edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            main.postDelayed(() -> verifySent(expectedReply, 2), 220);
            return;
        }
        if (send != null) {
            Rect r = new Rect();
            send.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                Path path = new Path();
                path.moveTo(r.centerX(), r.centerY());
                GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 50);
                boolean dispatched = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
                log("send", dispatched ? "GESTURE_DISPATCHED" : "GESTURE_FAILED");
                main.postDelayed(() -> verifySent(expectedReply, 3), 280);
                return;
            }
        }
        log("send", "FAILED_NO_SEND_ACTION");
    }

    private void verifySent(String expectedReply, int nextAttempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        String current = edit == null || edit.getText() == null ? "" : edit.getText().toString().trim();
        if (current.isEmpty() || !current.equals(expectedReply.trim())) {
            log("send", "SEND_CONFIRMED");
            control.edit().putString("suggested_reply", "").apply();
            return;
        }
        log("send", "NOT_SENT_ATTEMPT_" + nextAttempt);
        if (nextAttempt <= 2) pressSend(expectedReply, nextAttempt);
        else log("last_error", "A mensagem ficou no campo Samsung; tenta Enviar agora");
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
        String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(Locale.ROOT);
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase(Locale.ROOT);
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(Locale.ROOT);
        if (text.contains("enviar") || text.equals("send") || desc.contains("enviar") || desc.contains("send") ||
                id.contains("send") || id.contains("enter") || id.contains("text_call_send")) return node;
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
                payload.put("client_name", memory.has("client_name") ? memory.get("client_name") : "Cliente SOFIA");
                payload.put("result", result);
                payload.put("facts", memory.toJson());
                JSONObject feedback = new JSONObject();
                feedback.put("summary", "Chamada qualificada automaticamente pela SOFIA");
                feedback.put("interest_level", handoff ? "high" : "medium");
                feedback.put("next_action", handoff ? "MyPoupar handoff" : "follow_up");
                feedback.put("intent", handoff ? "INTERESTED" : "CONTINUE");
                feedback.put("stage", stage);
                feedback.put("transcript", transcript);
                payload.put("feedback", feedback);
                payload.put("handoff_requested", handoff);
                payload.put("requested_keyword", handoff ? "poupar" : JSONObject.NULL);
                SupabaseSyncClient.sync(this, payload);
            } catch (Exception e) {
                log("sync", "ERRO: " + safe(e.getMessage()));
            }
        });
    }

    private void log(String key, String value) {
        if (diag == null) diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        diag.edit().putString(key, value == null ? "" : value).putLong("updated", System.currentTimeMillis()).apply();
    }

    private String safe(String s) { return s == null ? "" : s.replace('\n', ' '); }

    @Override public void onInterrupt() { log("service", "INTERRUPTED"); }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacks(samsungWatcher);
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }
}
