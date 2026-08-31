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

    private static final long POLL_MS = 180L;
    private static final long TURN_SILENCE_MS = 720L;

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
    private SofiaCallOverlay overlay;

    private final Runnable samsungWatcher = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try { scanSamsungSurface("POLL"); } catch (Throwable t) { log("watcher", "ERRO: " + safe(t.getMessage())); }
            main.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable finalizeObservedTurn = new Runnable() {
        @Override public void run() {
            String candidate = observedCandidate == null ? "" : observedCandidate.trim();
            if (candidate.isEmpty()) return;
            long quietFor = System.currentTimeMillis() - observedCandidateSince;
            if (quietFor < TURN_SILENCE_MS - 40L) {
                main.postDelayed(this, Math.max(80L, TURN_SILENCE_MS - quietFor));
                return;
            }
            log("stability", quietFor + "ms · FINAL_POR_SILENCIO");
            observedCandidate = "";
            observedCandidateSince = 0L;
            enqueueCustomer(candidate);
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

        try {
            overlay = new SofiaCallOverlay(this);
            overlay.start();
            log("overlay", "ATIVO");
        } catch (Throwable t) {
            log("overlay", "ERRO: " + safe(t.getMessage()));
        }

        log("service", "ATIVO · SAMSUNG TRANSCRIPT DRIVER 60.5");
        main.removeCallbacks(samsungWatcher);
        main.removeCallbacks(finalizeObservedTurn);
        main.post(samsungWatcher);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!"com.samsung.android.incallui".contentEquals(event.getPackageName())) return;
        scanSamsungSurface("EVENT");
    }

    private void scanSamsungSurface(String trigger) {
        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) return;

        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) {
            if (System.currentTimeMillis() - lastTextCallReadyAt > 2500L) log("surface", "Samsung aberto; à espera do Text Call");
            return;
        }

        lastTextCallReadyAt = System.currentTimeMillis();
        log("surface", "TEXT_CALL_READY");
        log("driver_trigger", trigger);

        String candidate = findCustomerCandidate(root, edit);
        if (candidate.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (!sameUtterance(candidate, observedCandidate)) {
            observedCandidate = candidate;
            observedCandidateSince = now;
            log("raw_candidate", candidate);
            log("stability", "0ms · a ouvir");
            main.removeCallbacks(finalizeObservedTurn);
            main.postDelayed(finalizeObservedTurn, TURN_SILENCE_MS);
            return;
        }

        long quietFor = now - observedCandidateSince;
        log("stability", quietFor + "ms · a ouvir");
    }

    private synchronized void enqueueCustomer(String customer) {
        if (customer == null) return;
        customer = customer.trim();
        if (customer.isEmpty() || isCallState(customer) || sameUtterance(customer, lastCustomer) || sameUtterance(customer, memory.getLastAssistant())) return;
        for (String old : recentCustomers) if (sameUtterance(customer, old)) return;
        for (String q : customerQueue) if (sameUtterance(customer, q)) return;

        if (busy) customerQueue.clear();
        else if (!customerQueue.isEmpty()) customerQueue.clear();
        customerQueue.offer(customer);
        log("queue", String.valueOf(customerQueue.size()));
        log("path", busy ? "LATEST_TURN_WHILE_AI_BUSY" : "TURN_FINAL_POR_SILENCIO");
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
        if (customer.isEmpty() || isCallState(customer)) { drainQueue(); return; }
        for (String old : recentCustomers) if (sameUtterance(customer, old)) { drainQueue(); return; }

        final long turnStarted = System.currentTimeMillis();
        lastCustomer = customer;
        recentCustomers.add(customer);
        if (recentCustomers.size() > 30) recentCustomers.remove(0);
        log("last_customer", customer);
        appendTranscript("Cliente", customer);
        SofiaEngine.learnFreeText(customer, memory);

        String mode = mode();
        if ("MANUAL".equals(mode)) {
            log("path", "MANUAL_CAPTURE");
            log("llm_ms", "0");
            control.edit().putString("suggested_reply", "").apply();
            drainQueue();
            return;
        }

        SofiaEngine.Decision d = SofiaEngine.fastDecision(customer, memory);
        if (d != null) {
            log("path", "FAST_PATH");
            log("llm_ms", String.valueOf(System.currentTimeMillis() - turnStarted));
            handleGeneratedReply(d.reply, d.handoff, d.stage, mode);
            drainQueue();
            return;
        }

        busy = true;
        log("path", "QWEN_LOCAL");
        final String prompt = SofiaEngine.buildPrompt(customer, memory);
        worker.submit(() -> {
            try {
                String reply = sanitizeReply(QwenClient.generate(prompt));
                if (reply.isEmpty()) reply = fallback();
                log("qwen", "OK");
                log("llm_ms", String.valueOf(System.currentTimeMillis() - turnStarted));
                handleGeneratedReply(reply, false, "QUALIFICATION", mode);
            } catch (Exception e) {
                log("qwen", "ERRO: " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
                log("llm_ms", String.valueOf(System.currentTimeMillis() - turnStarted));
                handleGeneratedReply(fallback(), false, "QUALIFICATION", mode);
            } finally {
                busy = false;
                main.post(this::drainQueue);
            }
        });
    }

    private String sanitizeReply(String reply) {
        if (reply == null) return "";
        String r = reply.trim().replace('\n', ' ');
        String l = r.toLowerCase(Locale.ROOT);
        if (l.contains("és a sofia") || l.contains("es a sofia") || l.contains("system prompt") ||
                l.contains("factos conhecidos") || l.contains("cliente disse:") || l.contains("responde numa frase") ||
                l.contains("consultora mypoupar") || l.contains("especializada em telecomunicações") ||
                l.contains("especializada em telecomunicacoes") || l.contains("português de portugal") ||
                l.contains("portugues de portugal") || l.contains("responde apenas") ||
                l.contains("máximo 14 palavras") || l.contains("maximo 14 palavras") ||
                l.startsWith("agente:") || l.startsWith("marca:") || l.contains("script ativo:")) {
            log("guardrail", "PROMPT_LEAK_BLOCKED");
            return fallback();
        }
        if (r.startsWith("\"") && r.endsWith("\"") && r.length() > 1) r = r.substring(1, r.length() - 1).trim();
        if (r.length() > 260) r = r.substring(0, 260).trim();
        return r;
    }

    private void handleGeneratedReply(String reply, boolean handoff, String stage, String mode) {
        reply = sanitizeReply(reply);
        if (reply.isEmpty()) reply = fallback();
        memory.setLastAssistant(reply);
        customerQueue.clear();
        control.edit().putString("suggested_reply", reply).putString("live_stage", stage == null ? "" : stage).putBoolean("live_handoff", handoff).apply();
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

    private String fallback() { return "Certo. Diga-me só o que gostaria de melhorar no serviço atual."; }

    private void appendTranscript(String who, String text) {
        transcript += who + ": " + text + "\n";
        if (transcript.length() > 12000) transcript = transcript.substring(transcript.length() - 12000);
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        control.edit().putString("live_transcript", transcript).putString("live_customer", lastCustomer).apply();
    }

    private String findCustomerCandidate(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        List<NodeText> texts = new ArrayList<>();
        collectTexts(root, texts, edit);
        Rect editRect = new Rect();
        edit.getBoundsInScreen(editRect);
        NodeText best = null;
        int bestBottom = Integer.MIN_VALUE;

        // Important: first choose the newest bubble. Only afterwards decide whether it is a duplicate.
        // This prevents falling back to an old transcript line higher on the screen.
        for (NodeText nt : texts) {
            String s = nt.text == null ? "" : nt.text.trim();
            if (s.length() < 2 || s.length() > 220) continue;
            String l = s.toLowerCase(Locale.ROOT);
            if (isSamsungChrome(l) || isCallState(s) || looksLikePhoneNumber(s)) continue;

            if (!editRect.isEmpty() && !nt.bounds.isEmpty()) {
                if (nt.bounds.bottom >= editRect.top - 8) continue;
                if (nt.bounds.bottom > bestBottom) {
                    best = nt;
                    bestBottom = nt.bounds.bottom;
                }
            } else if (best == null) best = nt;
        }

        if (best == null) return "";
        String newest = best.text.trim();
        if (sameUtterance(newest, memory.getLastAssistant()) || sameUtterance(newest, lastCustomer)) return "";
        for (String old : recentCustomers) if (sameUtterance(newest, old)) return "";
        for (String q : customerQueue) if (sameUtterance(newest, q)) return "";

        log("candidate_bounds", best.bounds.flattenToString());
        log("candidate_class", best.className);
        log("candidate_strategy", "NEWEST_BUBBLE_ABOVE_COMPOSER");
        return newest;
    }

    private boolean sameUtterance(String a, String b) {
        if (a == null || b == null) return false;
        return normalizeUtterance(a).equals(normalizeUtterance(b));
    }

    private String normalizeUtterance(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    private boolean isCallState(String s) {
        String l = normalizeUtterance(s);
        return l.equals("a ligar") || l.equals("a chamar") || l.equals("a conectar") ||
                l.equals("ligacao em curso") || l.equals("chamada em curso") || l.equals("a aguardar") ||
                l.equals("calling") || l.equals("dialing") || l.equals("ringing") ||
                l.startsWith("a ligar para ") || l.startsWith("a chamar ");
    }

    private boolean looksLikePhoneNumber(String s) {
        String compact = s.replace(" ", "").replace("+", "");
        return compact.matches("\\d{7,15}");
    }

    private boolean isSamsungChrome(String l) {
        return l.contains("mudar para chamada") || l.contains("desligar") || l.contains("teclado") ||
                l.contains("altifalante") || l.contains("adicionar chamada") || l.contains("bluetooth") ||
                l.equals("escrever") || l.contains("escrever resposta") || l.contains("chamada de texto") || l.equals("mais") ||
                l.equals("repetir") || l.equals("reproduzir") || l.equals("parar") || l.equals("cancelar") ||
                l.equals("enviar") || l.equals("responder") || l.equals("voltar") || l.equals("fechar") ||
                l.contains("urgente") || l.contains("liga-lhe mais tarde") || l.contains("ligar-lhe mais tarde") ||
                l.contains("ligue-lhe mais tarde") || l.contains("quem fala") || l.contains("mensagem sugerida") ||
                l.contains("sugestão") || l.contains("assistente de chamada") ||
                l.contains("estou a utilizar um assistente de voz") || l.contains("converter a sua voz em texto") ||
                l.contains("responder-lhe") || l.contains("se quiser continuar") || l.contains("mantenha-se em linha") ||
                l.matches(".*\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b.*") || l.contains("minutos") || l.contains("segundos");
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
        final String text; final Rect bounds; final String className;
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
            main.postDelayed(() -> pressSend(safeReply, 0), 110L);
        });
    }

    private void pressSend(String expectedReply, int attempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) { log("last_error", "pressSend: samsung_root=null"); return; }
        AccessibilityNodeInfo edit = findEditable(root);
        AccessibilityNodeInfo send = findSendButton(root);

        if (attempt == 0 && send != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(send);
            boolean clicked = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            log("send", clicked ? "BUTTON_CLICK" : "BUTTON_CLICK_FAILED");
            main.postDelayed(() -> verifySent(expectedReply, 1), 220L);
            return;
        }

        if (attempt == 1 && edit != null && Build.VERSION.SDK_INT >= 30) {
            boolean enter = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            log("send", enter ? "IME_ENTER" : "IME_ENTER_FAILED");
            main.postDelayed(() -> verifySent(expectedReply, 2), 220L);
            return;
        }

        if (attempt >= 2 && edit != null) {
            Rect er = new Rect();
            edit.getBoundsInScreen(er);
            if (!er.isEmpty()) {
                int x = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), er.right + dp(36));
                int y = er.centerY();
                Path path = new Path();
                path.moveTo(x, y);
                GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 60);
                boolean dispatched = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
                log("send", dispatched ? "COMPOSER_RIGHT_GESTURE" : "COMPOSER_RIGHT_GESTURE_FAILED");
                main.postDelayed(() -> verifySent(expectedReply, 3), 280L);
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
        else log("last_error", "Resposta escrita mas Samsung não confirmou envio");
    }

    private AccessibilityNodeInfo findSamsungRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (isSamsungRoot(active)) return active;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window == null ? null : window.getRoot();
                if (isSamsungRoot(root)) return root;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isSamsungRoot(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null && "com.samsung.android.incallui".contentEquals(root.getPackageName());
    }

    private AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 5 && cur != null; i++) { if (cur.isClickable()) return cur; cur = cur.getParent(); }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo r = findEditable(node.getChild(i)); if (r != null) return r; }
        return null;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(Locale.ROOT);
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase(Locale.ROOT);
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(Locale.ROOT);
        if (text.contains("enviar") || text.equals("send") || desc.contains("enviar") || desc.contains("send") || id.contains("send") || id.contains("enter") || id.contains("text_call_send")) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo r = findSendButton(node.getChild(i)); if (r != null) return r; }
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
            } catch (Exception e) { log("sync", "ERRO: " + safe(e.getMessage())); }
        });
    }

    private void log(String key, String value) {
        if (diag == null) diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        diag.edit().putString(key, value == null ? "" : value).putLong("updated", System.currentTimeMillis()).apply();
    }

    private String safe(String s) { return s == null ? "" : s.replace('\n', ' '); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onInterrupt() { log("service", "INTERRUPTED"); }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacks(samsungWatcher);
        main.removeCallbacks(finalizeObservedTurn);
        if (overlay != null) { try { overlay.stop(); } catch (Throwable ignored) {} overlay = null; }
        try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }
}
