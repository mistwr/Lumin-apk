package com.lumin.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
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

    private static final long POLL_MS = 160L;
    private static final long TURN_SILENCE_MS = 520L;
    private static final long REPLY_WATCHDOG_MS = 950L;
    private static final long SEND_DEDUP_MS = 10000L;

    private final SofiaMemory memory = new SofiaMemory();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> recentCustomers = new ArrayList<>();

    private String lastCustomer = "";
    private String transcript = "";
    private String observedCandidate = "";
    private long observedCandidateAt = 0L;
    private volatile boolean busy = false;
    private volatile boolean destroyed = false;
    private volatile long activeTurnId = 0L;
    private volatile long repliedTurnId = -1L;
    private String lastSentNormalized = "";
    private long lastSentAt = 0L;
    private SharedPreferences diag;
    private SharedPreferences control;
    private SofiaCallOverlay overlay;

    private final Runnable watcher = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try { scanSamsungSurface(); } catch (Throwable t) { log("watcher", "ERRO: " + safe(t.getMessage())); }
            main.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable finalizeTurn = new Runnable() {
        @Override public void run() {
            String c = observedCandidate == null ? "" : observedCandidate.trim();
            if (c.isEmpty()) return;
            long quiet = System.currentTimeMillis() - observedCandidateAt;
            if (quiet < TURN_SILENCE_MS - 30L) {
                main.postDelayed(this, Math.max(60L, TURN_SILENCE_MS - quiet));
                return;
            }
            observedCandidate = "";
            observedCandidateAt = 0L;
            log("stability", quiet + "ms · FINAL_RAPIDO");
            processCustomer(c);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SEND_REPLY.equals(intent.getAction())) return;
            String reply = intent.getStringExtra(EXTRA_REPLY);
            if (reply != null && !reply.trim().isEmpty()) sendReply(reply.trim(), true);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (!control.contains("mode")) control.edit().putString("mode", "AUTO").apply();
        IntentFilter f = new IntentFilter(ACTION_SEND_REPLY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, f);
        try { overlay = new SofiaCallOverlay(this); overlay.start(); } catch (Throwable ignored) {}
        log("service", "ATIVO · SAMSUNG TRANSCRIPT DRIVER 60.7 FAST TURN");
        main.removeCallbacks(watcher);
        main.removeCallbacks(finalizeTurn);
        main.post(watcher);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if ("com.samsung.android.incallui".contentEquals(event.getPackageName())) scanSamsungSurface();
    }

    private void scanSamsungSurface() {
        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) return;
        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) return;
        String candidate = findCustomerCandidate(root, edit);
        if (candidate.isEmpty()) return;
        if (!same(candidate, observedCandidate)) {
            observedCandidate = candidate;
            observedCandidateAt = System.currentTimeMillis();
            log("raw_candidate", candidate);
            log("stability", "0ms · a ouvir");
            main.removeCallbacks(finalizeTurn);
            main.postDelayed(finalizeTurn, TURN_SILENCE_MS);
        }
    }

    private synchronized boolean claimReply(long turnId) {
        if (repliedTurnId == turnId) return false;
        repliedTurnId = turnId;
        return true;
    }

    private void processCustomer(String customer) {
        customer = customer == null ? "" : customer.trim();
        if (customer.isEmpty() || isCallState(customer) || same(customer, lastCustomer) || same(customer, memory.getLastAssistant())) return;
        for (String old : recentCustomers) if (same(customer, old)) return;

        final long started = System.currentTimeMillis();
        final long turnId = ++activeTurnId;
        lastCustomer = customer;
        recentCustomers.add(customer);
        if (recentCustomers.size() > 24) recentCustomers.remove(0);
        appendTranscript("Cliente", customer);
        SofiaEngine.learnFreeText(customer, memory);
        control.edit().putString("live_customer", customer).putString("suggested_reply", "").apply();
        log("last_customer", customer);

        final String mode = mode();
        if ("MANUAL".equals(mode)) return;

        SofiaEngine.Decision fast = SofiaEngine.fastDecision(customer, memory);
        if (fast != null) {
            if (claimReply(turnId)) {
                log("path", "FAST_PATH");
                log("llm_ms", String.valueOf(System.currentTimeMillis() - started));
                deliverReply(fast.reply, fast.handoff, fast.stage, mode);
            }
            return;
        }

        final String customerForFallback = customer;
        main.postDelayed(() -> {
            if (activeTurnId != turnId || !claimReply(turnId)) return;
            busy = false;
            String fb = contextualFallback(customerForFallback);
            log("path", "FAST_WATCHDOG");
            log("llm_ms", String.valueOf(System.currentTimeMillis() - started));
            deliverReply(fb, false, "QUALIFICATION", mode);
        }, REPLY_WATCHDOG_MS);

        busy = true;
        final String prompt = SofiaEngine.buildPrompt(customer, memory);
        worker.submit(() -> {
            try {
                String r = sanitizeReply(QwenClient.generate(prompt));
                if (r.isEmpty()) r = contextualFallback(customerForFallback);
                if (claimReply(turnId)) {
                    log("path", "QWEN_LOCAL");
                    log("llm_ms", String.valueOf(System.currentTimeMillis() - started));
                    deliverReply(r, false, "QUALIFICATION", mode);
                } else log("qwen", "LATE_REPLY_IGNORED");
            } catch (Throwable t) {
                if (claimReply(turnId)) deliverReply(contextualFallback(customerForFallback), false, "QUALIFICATION", mode);
            } finally {
                busy = false;
            }
        });
    }

    private void deliverReply(String reply, boolean handoff, String stage, String mode) {
        reply = sanitizeReply(reply);
        if (reply.isEmpty()) return;
        if (same(reply, memory.getLastAssistant())) {
            log("guardrail", "REPEATED_ASSISTANT_BLOCKED");
            return;
        }
        memory.setLastAssistant(reply);
        control.edit().putString("suggested_reply", reply).putString("live_stage", stage == null ? "" : stage).putBoolean("live_handoff", handoff).apply();
        if ("ASSISTED".equals(mode)) return;
        appendTranscript("Sofia", reply);
        sendReply(reply, false);
        if (handoff) syncNow("interested", stage, true);
    }

    private String contextualFallback(String customer) {
        String n = normalize(customer);
        if (n.contains("luz") || n.contains("energia") || n.contains("eletric")) return "Sensivelmente quanto paga por mês de eletricidade?";
        if (n.contains("telecom") || n.contains("internet") || n.contains("tv") || n.contains("fibra")) return "Atualmente está com que operador?";
        if (memory.has("operator") && !memory.has("monthly_price")) return "Sensivelmente quanto paga por mês pelo pacote?";
        return "Diga-me só o que gostaria de melhorar no serviço atual.";
    }

    private String sanitizeReply(String reply) {
        if (reply == null) return "";
        String r = reply.trim().replace('\n', ' ').replaceAll("\\s+", " ");
        String l = r.toLowerCase(Locale.ROOT);
        if (l.contains("system prompt") || l.contains("factos conhecidos") || l.contains("cliente disse:") ||
                l.contains("consultora mypoupar") || l.contains("especializada em telecom") ||
                l.contains("português de portugal") || l.contains("portugues de portugal") ||
                l.contains("responde apenas") || l.contains("script ativo:") || l.startsWith("agente:")) return "";
        if (r.startsWith("\"") && r.endsWith("\"") && r.length() > 1) r = r.substring(1, r.length() - 1).trim();
        int half = r.length() / 2;
        if (r.length() >= 20 && r.length() % 2 == 0 && r.substring(0, half).equals(r.substring(half))) r = r.substring(0, half).trim();
        if (r.length() > 180) r = r.substring(0, 180).trim();
        return r;
    }

    private void sendReply(String reply, boolean userApproved) {
        final String safeReply = sanitizeReply(reply);
        if (safeReply.isEmpty()) return;
        final String norm = normalize(safeReply);
        long now = System.currentTimeMillis();
        if (norm.equals(lastSentNormalized) && now - lastSentAt < SEND_DEDUP_MS) {
            log("send", "DUPLICATE_BLOCKED");
            return;
        }
        lastSentNormalized = norm;
        lastSentAt = now;

        main.post(() -> {
            AccessibilityNodeInfo root = findSamsungRoot();
            AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
            if (edit == null) { log("last_error", "Samsung composer não encontrado"); return; }
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);

            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, safeReply);
            boolean set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            log("set_text", String.valueOf(set));

            // Critical 60.7 fix: never paste after a successful SET_TEXT.
            if (!set) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("SOFIA", safeReply));
                        set = edit.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                        log("paste_text", String.valueOf(set));
                    }
                } catch (Throwable t) { log("paste_text", "ERRO"); }
            }
            if (!set) { log("send", "WRITE_FAILED"); return; }

            if (userApproved) {
                appendTranscript("Sofia", safeReply);
                memory.setLastAssistant(safeReply);
            }
            main.postDelayed(() -> pressSend(safeReply, 0), 90L);
        });
    }

    private void pressSend(String expected, int attempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        if (root == null) return;
        AccessibilityNodeInfo edit = findEditable(root);
        AccessibilityNodeInfo send = findSendButton(root);

        if (attempt == 0 && send != null) {
            AccessibilityNodeInfo c = clickableSelfOrParent(send);
            boolean ok = c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            log("send", ok ? "BUTTON_CLICK" : "BUTTON_CLICK_FAILED");
            main.postDelayed(() -> verifySent(expected, 1), 160L);
            return;
        }
        if (attempt == 1 && edit != null && Build.VERSION.SDK_INT >= 30) {
            boolean ok = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            log("send", ok ? "IME_ENTER" : "IME_ENTER_FAILED");
            main.postDelayed(() -> verifySent(expected, 2), 160L);
            return;
        }
        if (edit != null) {
            Rect er = new Rect(); edit.getBoundsInScreen(er);
            if (!er.isEmpty()) {
                int x = Math.min(getResources().getDisplayMetrics().widthPixels - dp(18), er.right + dp(34));
                Path p = new Path(); p.moveTo(x, er.centerY());
                boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 45)).build(), null, null);
                log("send", ok ? "GESTURE_SEND" : "GESTURE_FAILED");
            }
        }
    }

    private void verifySent(String expected, int next) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        String current = edit == null || edit.getText() == null ? "" : edit.getText().toString().trim();
        if (current.isEmpty() || !same(current, expected)) {
            log("send", "SEND_CONFIRMED");
            control.edit().putString("suggested_reply", "").apply();
            return;
        }
        if (next <= 2) pressSend(expected, next); else log("last_error", "Resposta ficou no compositor Samsung");
    }

    private String findCustomerCandidate(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        List<NodeText> texts = new ArrayList<>();
        collectTexts(root, texts, edit);
        Rect editRect = new Rect(); edit.getBoundsInScreen(editRect);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        NodeText best = null;
        int bestBottom = Integer.MIN_VALUE;

        for (NodeText nt : texts) {
            String s = nt.text == null ? "" : nt.text.trim();
            if (s.length() < 2 || s.length() > 240) continue;
            String l = s.toLowerCase(Locale.ROOT);
            if (isSamsungChrome(l) || isCallState(s) || looksLikePhoneNumber(s)) continue;
            if (!nt.bounds.isEmpty()) {
                if (nt.bounds.bottom >= editRect.top - 8) continue;
                // Samsung customer transcript is left aligned; Sofia/Text Call replies are on the right.
                if (nt.bounds.left > (int)(screenW * 0.24f)) continue;
                if (nt.bounds.bottom > bestBottom) { best = nt; bestBottom = nt.bounds.bottom; }
            }
        }
        if (best == null) return "";
        String newest = best.text.trim();
        if (same(newest, memory.getLastAssistant()) || same(newest, lastCustomer)) return "";
        for (String old : recentCustomers) if (same(newest, old)) return "";
        return newest;
    }

    private void collectTexts(AccessibilityNodeInfo node, List<NodeText> out, AccessibilityNodeInfo edit) {
        if (node == null) return;
        if (node != edit && node.getText() != null && !node.isEditable()) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            out.add(new NodeText(node.getText().toString(), r));
        }
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), out, edit);
    }

    private static class NodeText { final String text; final Rect bounds; NodeText(String t, Rect r) { text=t; bounds=r; } }

    private AccessibilityNodeInfo findSamsungRoot() {
        AccessibilityNodeInfo a = getRootInActiveWindow();
        if (isSamsungRoot(a)) return a;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo r = w == null ? null : w.getRoot();
                if (isSamsungRoot(r)) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isSamsungRoot(AccessibilityNodeInfo r) {
        return r != null && r.getPackageName() != null && "com.samsung.android.incallui".contentEquals(r.getPackageName());
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i=0;i<n.getChildCount();i++) { AccessibilityNodeInfo r=findEditable(n.getChild(i)); if(r!=null)return r; }
        return null;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo n) {
        if (n == null) return null;
        String t = n.getText()==null?"":n.getText().toString().toLowerCase(Locale.ROOT);
        String d = n.getContentDescription()==null?"":n.getContentDescription().toString().toLowerCase(Locale.ROOT);
        String id = n.getViewIdResourceName()==null?"":n.getViewIdResourceName().toLowerCase(Locale.ROOT);
        if (t.contains("enviar") || t.equals("send") || d.contains("enviar") || d.contains("send") || id.contains("send") || id.contains("enter")) return n;
        for (int i=0;i<n.getChildCount();i++) { AccessibilityNodeInfo r=findSendButton(n.getChild(i)); if(r!=null)return r; }
        return null;
    }

    private AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c=n; for(int i=0;i<5 && c!=null;i++){ if(c.isClickable()) return c; c=c.getParent(); } return null;
    }

    private boolean same(String a, String b) { return a != null && b != null && normalize(a).equals(normalize(b)); }
    private String normalize(String s) { return s==null?"":s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," "); }
    private boolean isCallState(String s) { String n=normalize(s); return n.equals("a ligar")||n.equals("a chamar")||n.equals("calling")||n.equals("dialing")||n.equals("ringing")||n.startsWith("a ligar para "); }
    private boolean looksLikePhoneNumber(String s) { return s.replace(" ","").replace("+","").matches("\\d{7,15}"); }

    private boolean isSamsungChrome(String l) {
        return l.contains("escrever resposta") || l.contains("chamada de texto") || l.contains("assistente de chamada") ||
                l.contains("urgente") || l.contains("ligar-lhe mais tarde") || l.equals("repetir") || l.equals("enviar") ||
                l.contains("estou a utilizar um assistente de voz") || l.contains("converter a sua voz em texto") ||
                l.contains("mantenha-se em linha") || l.contains("desligar") || l.contains("altifalante") || l.contains("teclado") ||
                l.matches(".*\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b.*");
    }

    private String mode() { if(control==null)control=getSharedPreferences("sofia_control",MODE_PRIVATE); return control.getString("mode","AUTO"); }
    private void appendTranscript(String who,String text){ transcript+=who+": "+text+"\n"; if(transcript.length()>12000)transcript=transcript.substring(transcript.length()-12000); control.edit().putString("live_transcript",transcript).apply(); }

    private void syncNow(String result, String stage, boolean handoff) {
        worker.submit(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("phone_number", memory.has("phone_number") ? memory.get("phone_number") : "unknown");
                payload.put("client_name", memory.has("client_name") ? memory.get("client_name") : "Cliente SOFIA");
                payload.put("result", result); payload.put("facts", memory.toJson());
                JSONObject feedback = new JSONObject();
                feedback.put("summary", "Chamada qualificada automaticamente pela SOFIA");
                feedback.put("interest_level", handoff ? "high" : "medium");
                feedback.put("stage", stage); feedback.put("transcript", transcript);
                payload.put("feedback", feedback); payload.put("handoff_requested", handoff);
                SupabaseSyncClient.sync(this, payload);
            } catch (Throwable ignored) {}
        });
    }

    private void log(String key,String value){ if(diag==null)diag=getSharedPreferences("sofia_diag",MODE_PRIVATE); diag.edit().putString(key,value==null?"":value).putLong("updated",System.currentTimeMillis()).apply(); }
    private String safe(String s){return s==null?"":s.replace('\n',' ');} private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onInterrupt(){log("service","INTERRUPTED");}
    @Override public void onDestroy(){ destroyed=true; main.removeCallbacks(watcher); main.removeCallbacks(finalizeTurn); if(overlay!=null)try{overlay.stop();}catch(Throwable ignored){} try{unregisterReceiver(receiver);}catch(Throwable ignored){} worker.shutdownNow(); super.onDestroy(); }
}
