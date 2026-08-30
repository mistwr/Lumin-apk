package com.lumin.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
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
    private String lastCustomer = "";
    private String transcript = "";
    private volatile boolean busy = false;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!"com.samsung.android.incallui".contentEquals(event.getPackageName())) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String customer = findCustomerCandidate(root);
        if (customer.isEmpty() || customer.equals(lastCustomer) || customer.equals(memory.getLastAssistant())) return;
        lastCustomer = customer;
        transcript += "Cliente: " + customer + "\n";
        SofiaEngine.learnFreeText(customer, memory);
        SofiaEngine.Decision d = SofiaEngine.fastDecision(customer, memory);
        if (d != null) {
            speakThroughSamsung(root, d.reply);
            memory.setLastAssistant(d.reply);
            transcript += "Sofia: " + d.reply + "\n";
            if (d.handoff) syncNow("interested", d.stage, true);
            return;
        }
        if (busy) return;
        busy = true;
        final String prompt = SofiaEngine.buildPrompt(customer, memory);
        worker.submit(() -> {
            try {
                String reply = QwenClient.generate(prompt);
                if (reply == null || reply.trim().isEmpty()) reply = "Certo. Diga-me só o que considera mais importante melhorar no serviço atual.";
                final String finalReply = reply.trim();
                AccessibilityNodeInfo currentRoot = getRootInActiveWindow();
                if (currentRoot != null) speakThroughSamsung(currentRoot, finalReply);
                memory.setLastAssistant(finalReply);
                transcript += "Sofia: " + finalReply + "\n";
            } catch (Exception e) {
                AccessibilityNodeInfo currentRoot = getRootInActiveWindow();
                if (currentRoot != null) speakThroughSamsung(currentRoot, "Certo. Diga-me só o que considera mais importante melhorar no serviço atual.");
            } finally { busy = false; }
        });
    }

    private String findCustomerCandidate(AccessibilityNodeInfo root) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        for (int i = texts.size() - 1; i >= 0; i--) {
            String s = texts.get(i).trim();
            if (s.length() < 2 || s.length() > 260) continue;
            String l = s.toLowerCase();
            if (l.contains("mudar para chamada") || l.contains("desligar") || l.contains("teclado") || l.contains("altifalante") ||
                    l.contains("adicionar chamada") || l.contains("bluetooth") || l.contains("escrever") || l.contains("chamada de texto") ||
                    l.matches("\\d{1,2}:\\d{2}") || l.contains("minutos") || l.contains("segundos")) continue;
            if (s.equals(memory.getLastAssistant())) continue;
            return s;
        }
        return "";
    }

    private void collectTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        if (node.getText() != null) out.add(node.getText().toString());
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), out);
    }

    private void speakThroughSamsung(AccessibilityNodeInfo root, String reply) {
        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) return;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
        edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        AccessibilityNodeInfo send = findSendButton(root);
        if (send != null) send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
        if ((text.contains("enviar") || desc.contains("enviar") || desc.contains("send")) && node.isClickable()) return node;
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
            } catch (Exception ignored) {}
        });
    }

    @Override public void onInterrupt() {}
}
