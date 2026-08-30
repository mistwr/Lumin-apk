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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SofiaAccessibilityService extends AccessibilityService {
    public static final String ACTION_SEND_REPLY = "com.lumin.app.SEND_REPLY";
    public static final String EXTRA_REPLY = "reply";

    private final SofiaMemory memory = new SofiaMemory();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> recentCustomers = new ArrayList<>();
    private String lastCustomer = "";
    private String transcript = "";
    private volatile boolean busy = false;
    private volatile String pendingCustomer = "";
    private SharedPreferences diag;
    private SharedPreferences control;
    private long lastTextCallReadyAt = 0L;

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
        log("service", "ATIVO");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!"com.samsung.android.incallui".contentEquals(event.getPackageName())) return;

        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) { log("last_error", "samsung_root=null"); return; }

        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) {
            if (System.currentTimeMillis() - lastTextCallReadyAt > 2500L) log("surface", "Samsung aberto; à espera do campo Text Call");
            return;
        }
        lastTextCallReadyAt = System.currentTimeMillis();
        log("surface", "TEXT_CALL_READY");

        String customer = findCustomerCandidate(root, edit);
        if (customer.isEmpty() || customer.equals(memory.getLastAssistant())) return;

        if (busy) {
            if (!customer.equals(lastCustomer) && !customer.equals(pendingCustomer) && !recentCustomers.contains(customer)) {
                pendingCustomer = customer;
                log("pending_customer", customer);
                log("path", "QUEUED_WHILE_AI_BUSY");
            }
            return;
        }

        processCustomer(customer);
    }

    private void processCustomer(String customer) {
        if (customer == null) return;
        customer = customer.trim();
        if (customer.isEmpty() || customer.equals(lastCustomer) || customer.equals(memory.getLastAssistant()) || recentCustomers.contains(customer)) return;

        lastCustomer = customer;
        recentCustomers.add(customer);
        if (recentCustomers.size() > 14) recentCustomers.remove(0);
        log("last_customer", customer);
        appendTranscript("Cliente", customer);
        SofiaEngine.learnFreeText(customer, memory);

        String mode = mode();
        if ("MANUAL".equals(mode)) {
            log("path", "MANUAL_CAPTURE");
            control.edit().putString("suggested_reply", "").apply();
            return;
        }

        SofiaEngine.Decision d = SofiaEngine.fastDecision(customer, memory);
        if (d != null) {
            log("path", "FAST_PATH");
            handleGeneratedReply(d.reply, d.handoff, d.stage, mode);
            return;
        }

        busy = true;
        log("path", "QWEN");
        final String prompt = SofiaEngine.buildPrompt(customer, memory);
        worker.submit(() -> {
            try {
                String reply = QwenClient.generate(prompt);
                if (reply == null || reply.trim().isEmpty()) reply = fallback();
                log("qwen", "OK");
                handleGeneratedReply(reply.trim(), false, "QUALIFICATION", mode);
            } catch (Exception e) {
                log("qwen", "ERRO: " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
                handleGeneratedReply(fallback(), false, "QUALIFICATION", mode);
            } finally {
                busy = false;
                drainPendingCustomer();
            }
        });
    }

    private void drainPendingCustomer() {
        String queued = pendingCustomer;
        pendingCustomer = "";
        if (queued == null || queued.trim().isEmpty()) return;
        log("path", "PROCESSING_QUEUED_CUSTOMER");
        main.postDelayed(() -> processCustomer(queued), 120L);
    }

    private void handleGeneratedReply(String reply, boolean handoff, String stage, String mode) {
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
        return "Certo. Diga-me só o que considera mais importante melhorar no serviço atual.";
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
        String lastAssistant = memory.getLastAssistant();
        for (int i = texts.size() - 1; i >= 0; i--) {
            String s = texts.get(i).trim();
            if (s.length() < 2 || s.length() > 260) continue;
            String l = s.toLowerCase(java.util.Locale.ROOT);
            if (isSamsungChrome(l)) continue;
            if (s.equals(lastAssistant)) continue;
            if (s.equals(lastCustomer)) continue;
            if (s.equals(pendingCustomer)) continue;
            if (recentCustomers.contains(s)) continue;
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
                l.contains("urgente") || l.contains("liga-lhe mais tarde") || l.contains("ligar-lhe mais tarde") ||
                l.contains("ligue-lhe mais tarde") || l.contains("quem fala") || l.contains("mensagem sugerida") ||
                l.contains("sugestão") || l.contains("assistente de chamada") ||
                l.contains("estou a utilizar um assistente de voz") || l.contains("converter a sua voz em texto") ||
                l.contains("responder-lhe. se quiser continuar") || l.contains("mantenha-se em linha") ||
                l.matches("\\d{1,2}:\\d{2}") || l.matches("\\d{1,2}:\\d{2}:\\d{2}") ||
                l.contains("minutos") || l.contains("segundos");
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> out, AccessibilityNodeInfo edit) {
        if (node == null) return;
        if (node != edit && node.getText() != null && !node.isEditable()) out.add(node.getText().toString());
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), out, edit);
    }

    private void sendReply(String reply, boolean userApproved) {
        main.post(() -> {
            AccessibilityNodeInfo root = findSamsungRoot();
            if (root == null) { log("last_error", "send: Samsung Text Call não está acessível"); return; }
            AccessibilityNodeInfo edit = findEditable(root);
            if (edit == null) { log("last_error", "send: edit=null"); return; }

            lastTextCallReadyAt = System.currentTimeMillis();
            log("surface", "TEXT_CALL_READY");
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
            boolean set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            log("last_reply", reply);
            log("set_text", String.valueOf(set));
            if (!set) { log("send", "SET_TEXT_FAILED"); return; }
            if (userApproved) {
                appendTranscript("Sofia", reply);
                memory.setLastAssistant(reply);
                control.edit().putString("suggested_reply", "").apply();
            }
            main.postDelayed(() -> pressSend(reply, 0), 120);
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
            main.postDelayed(() -> verifySent(expectedReply, 1), 260);
            return;
        }

        if (attempt <= 1 && edit != null && Build.VERSION.SDK_INT >= 30) {
            edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            main.postDelayed(() -> verifySent(expectedReply, 2), 240);
            return;
        }

        if (send != null) {
            Rect r = new Rect();
            send.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                Path path = new Path();
                path.moveTo(r.centerX(), r.centerY());
                GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 60);
                boolean dispatched = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
                log("send", dispatched ? "GESTURE_DISPATCHED" : "GESTURE_FAILED");
                main.postDelayed(() -> verifySent(expectedReply, 3), 300);
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
            log("surface", "TEXT_CALL_READY");
            control.edit().putString("suggested_reply", "").apply();
            return;
        }
        log("send", "NOT_SENT_ATTEMPT_" + nextAttempt);
        if (nextAttempt <= 2) pressSend(expectedReply, nextAttempt);
        else log("last_error", "A mensagem ficou no campo Samsung; abre o Telefone e tenta Enviar agora");
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
        String text = node.getText() == null ? "" : node.getText().toString().toLowerCase();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase();
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase();
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
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }
}
