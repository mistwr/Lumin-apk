package com.lumin.app;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SofiaAccessibilityService extends AccessibilityService {
    private final SofiaMemory memory = new SofiaMemory();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastCustomer = "";
    private String transcript = "";
    private volatile boolean busy = false;
    private SharedPreferences diag;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        log("service", "ATIVO");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!"com.samsung.android.incallui".contentEquals(event.getPackageName())) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { log("last_error", "root=null"); return; }

        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) {
            log("surface", "Samsung aberto; campo Text Call ainda não encontrado");
            return;
        }
        log("surface", "TEXT_CALL_READY");

        String customer = findCustomerCandidate(root, edit);
        if (customer.isEmpty() || customer.equals(lastCustomer) || customer.equals(memory.getLastAssistant())) return;

        lastCustomer = customer;
        log("last_customer", customer);
        transcript += "Cliente: " + customer + "\n";
        SofiaEngine.learnFreeText(customer, memory);

        SofiaEngine.Decision d = SofiaEngine.fastDecision(customer, memory);
        if (d != null) {
            log("path", "FAST_PATH");
            sendReply(d.reply);
            memory.setLastAssistant(d.reply);
            transcript += "Sofia: " + d.reply + "\n";
            if (d.handoff) syncNow("interested", d.stage, true);
            return;
        }

        if (busy) return;
        busy = true;
        log("path", "QWEN");
        final String prompt = SofiaEngine.buildPrompt(customer, memory);
        worker.submit(() -> {
            try {
                String reply = QwenClient.generate(prompt);
                if (reply == null || reply.trim().isEmpty()) reply = fallback();
                final String finalReply = reply.trim();
                log("qwen", "OK");
                sendReply(finalReply);
                memory.setLastAssistant(finalReply);
                transcript += "Sofia: " + finalReply + "\n";
            } catch (Exception e) {
                log("qwen", "ERRO: " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
                sendReply(fallback());
            } finally {
                busy = false;
            }
        });
    }

    private String fallback() {
        return "Certo. Diga-me só o que considera mais importante melhorar no serviço atual.";
    }

    private String findCustomerCandidate(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts, edit);
        for (int i = texts.size() - 1; i >= 0; i--) {
            String s = texts.get(i).trim();
            if (s.length() < 2 || s.length() > 260) continue;
            String l = s.toLowerCase();
            if (isChrome(l)) continue;
            if (s.equals(memory.getLastAssistant())) continue;
            return s;
        }
        return "";
    }

    private boolean isChrome(String l) {
        return l.contains("mudar para chamada") || l.contains("desligar") || l.contains("teclado") ||
                l.contains("altifalante") || l.contains("adicionar chamada") || l.contains("bluetooth") ||
                l.equals("escrever") || l.contains("chamada de texto") || l.contains("mais") ||
                l.contains("urgente") || l.contains("liga-lhe mais tarde") || l.contains("quem fala") ||
                l.matches("\\d{1,2}:\\d{2}") || l.contains("minutos") || l.contains("segundos");
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> out, AccessibilityNodeInfo edit) {
        if (node == null) return;
        if (node != edit && node.getText() != null && !node.isEditable()) out.add(node.getText().toString());
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), out, edit);
    }

    private void sendReply(String reply) {
        main.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { log("last_error", "send: root=null"); return; }
            AccessibilityNodeInfo edit = findEditable(root);
            if (edit == null) { log("last_error", "send: edit=null"); return; }

            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
            boolean set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            log("last_reply", reply);
            log("set_text", String.valueOf(set));

            main.postDelayed(() -> pressSend(reply), 180);
        });
    }

    private void pressSend(String expectedReply) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { log("last_error", "pressSend: root=null"); return; }
        AccessibilityNodeInfo edit = findEditable(root);

        AccessibilityNodeInfo send = findSendButton(root);
        if (send != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(send);
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("send", "BUTTON_OK");
                return;
            }
        }

        if (edit != null && android.os.Build.VERSION.SDK_INT >= 30) {
            boolean ime = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            if (ime) {
                log("send", "IME_ENTER_OK");
                return;
            }
        }

        if (edit != null) {
            boolean click = edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            log("send", "NO_SEND_BUTTON editClick=" + click + " text=" + expectedReply);
        } else {
            log("send", "FAILED_NO_EDIT");
        }
    }

    private AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 4 && cur != null; i++) {
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
}
