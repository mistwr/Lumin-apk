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
    private static final String AUTO_INTRO = "Olá, boa tarde. Sou a assistente virtual da MY POUPar+. É uma chamada rápida para ajudar a perceber se os seus serviços de energia ou telecomunicações continuam competitivos. Posso explicar em vinte segundos?";

    private final SofiaMemory memory = new SofiaMemory();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastCustomer = "";
    private String transcript = "";
    private volatile boolean busy = false;
    private SharedPreferences diag;
    private SharedPreferences control;
    private long lastTextCallReadyAt = 0L;
    private long lastAutoOpenAttemptAt = 0L;
    private boolean autoIntroSent = false;

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
            if (tryOpenTextCall(root)) return;
            if (lastTextCallReadyAt > 0L && System.currentTimeMillis() - lastTextCallReadyAt > 5000L) {
                autoIntroSent = false;
                lastCustomer = "";
                transcript = "";
                memory.setLastAssistant("");
            }
            if (System.currentTimeMillis() - lastTextCallReadyAt > 2500L) log("surface", "Samsung aberto; a procurar Text Call automaticamente");
            return;
        }
        lastTextCallReadyAt = System.currentTimeMillis();
        log("surface", "TEXT_CALL_READY");

        String currentMode = mode();
        if ("AUTO".equals(currentMode) && !autoIntroSent) {
            autoIntroSent = true;
            memory.setLastAssistant(AUTO_INTRO);
            appendTranscript("REBORN", AUTO_INTRO);
            log("path", "AUTO_INTRO");
            log("intro", "SENDING");
            sendReply(AUTO_INTRO, false);
            return;
        }

        String customer = findCustomerCandidate(root, edit);
        if (customer.isEmpty() || customer.equals(lastCustomer) || customer.equals(memory.getLastAssistant())) return;

        lastCustomer = customer;
        log("last_customer", customer);
        appendTranscript("Cliente", customer);
        SofiaEngine.learnFreeText(customer, memory);

        String mode = currentMode;
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

        if (busy) return;
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
            }
        });
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
        if (clicked) {
            main.postDelayed(() -> {
                AccessibilityNodeInfo next = findSamsungRoot();
                if (next != null && findEditable(next) != null) log("surface", "TEXT_CALL_READY_AUTO");
            }, 700);
        }
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

    private void handleGeneratedReply(String reply, boolean handoff, String stage, String mode) {
        memory.setLastAssistant(reply);
        control.edit().putString("suggested_reply", reply).apply();
        if ("ASSISTED".equals(mode)) {
            log("send", "WAITING_USER_APPROVAL");
            return;
        }
        appendTranscript("REBORN", reply);
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
        for (int i = texts.size() - 1; i >= 0; i--) {
            String s = texts.get(i).trim();
            if (s.length() < 2 || s.length() > 260) continue;
            String l = s.toLowerCase();
            if (isSamsungChrome(l)) continue;
            if (s.equals(memory.getLastAssistant())) continue;
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
                appendTranscript("REBORN", reply);
                memory.setLastAssistant(reply);
                control.edit().putString("suggested_reply", "").apply();
            }
            main.postDelayed(() -> pressSend(reply, 0), 300);
        });
    }

    private void pressSend(String expectedReply, int attempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) { log("last_error", "pressSend: samsung_root=null"); return; }
        AccessibilityNodeInfo edit = findEditable(root);
        AccessibilityNodeInfo send = findSendButton(root);
        if (send == null && edit != null) send = findSendButtonNearEditor(root, edit);

        if (attempt == 0 && send != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(send);
            if (clickable != null) {
                boolean clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                log("send", clicked ? "CLICK_SENT" : "CLICK_FAILED");
                main.postDelayed(() -> verifySent(expectedReply, 1), 500);
                return;
            }

            Rect r = new Rect();
            send.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                tapBounds(r, "GEOMETRY_TAP");
                main.postDelayed(() -> verifySent(expectedReply, 1), 500);
                return;
            }
        }

        if (attempt <= 1 && edit != null && Build.VERSION.SDK_INT >= 30) {
            boolean enter = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            log("send", enter ? "IME_ENTER" : "IME_ENTER_FAILED");
            main.postDelayed(() -> verifySent(expectedReply, 2), 450);
            return;
        }

        if (send != null) {
            Rect r = new Rect();
            send.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                tapBounds(r, "GESTURE_SEND");
                main.postDelayed(() -> verifySent(expectedReply, 3), 550);
                return;
            }
        }

        if (edit != null) {
            tapSendRightOfEditor(edit);
            main.postDelayed(() -> verifySent(expectedReply, 3), 550);
            return;
        }
        log("send", "FAILED_NO_SEND_ACTION");
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
        if (r.isEmpty()) { log("send", "EDITOR_BOUNDS_EMPTY"); return; }
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
                int dx = Math.abs(cx - editor.right);
                int dy = Math.abs(cy - editor.centerY());
                int score = dx + (dy * 2);
                if (score < bestScore[0]) {
                    bestScore[0] = score;
                    best[0] = node;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) findSendCandidateRecursive(node.getChild(i), editor, best, bestScore);
    }

    private void verifySent(String expectedReply, int nextAttempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        String current = edit == null || edit.getText() == null ? "" : edit.getText().toString().trim();
        if (current.isEmpty() || !current.equals(expectedReply.trim())) {
            log("send", "SEND_CONFIRMED");
            if (AUTO_INTRO.equals(expectedReply)) log("intro", "SENT");
            log("surface", "TEXT_CALL_READY");
            control.edit().putString("suggested_reply", "").apply();
            return;
        }
        log("send", "NOT_SENT_ATTEMPT_" + nextAttempt);
        if (nextAttempt <= 2) pressSend(expectedReply, nextAttempt);
        else {
            if (AUTO_INTRO.equals(expectedReply)) autoIntroSent = false;
            log("last_error", "A mensagem ficou no campo Samsung; botão de envio não acionado");
        }
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
                desc.contains("mensagem") || id.contains("send") || id.contains("enter") || id.contains("text_call_send") ||
                id.contains("message_send")) return node;
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

    private String lower(CharSequence s) { return s == null ? "" : s.toString().toLowerCase(); }
    private String lower(String s) { return s == null ? "" : s.toLowerCase(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private String safe(String s) { return s == null ? "" : s.replace('\n', ' '); }

    @Override public void onInterrupt() { log("service", "INTERRUPTED"); }

    @Override public void onDestroy() {
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }
}
