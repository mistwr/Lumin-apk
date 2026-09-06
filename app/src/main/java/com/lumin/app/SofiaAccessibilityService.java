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

/** Thin Samsung Text Call transport bridge. RebornCentral/Qwen is the only brain. */
public class SofiaAccessibilityService extends AccessibilityService {
    public static final String ACTION_SEND_REPLY = "com.lumin.app.SEND_REPLY";
    public static final String EXTRA_REPLY = "reply";

    private static final String SAMSUNG_INCALL = "com.samsung.android.incallui";
    private static final long POLL_MS = 250L;
    private static final long STABLE_MS = 900L;
    private static final long SESSION_GONE_MS = 5000L;
    private static final long OPEN_COOLDOWN_MS = 1400L;
    private static final long PENDING_RETRY_MS = 700L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences diag;
    private SharedPreferences control;
    private boolean destroyed;
    private long lastSamsungSeenAt;
    private long lastOpenAttemptAt;
    private long lastPendingAttemptAt;
    private boolean sendInFlight;
    private String pendingReply = "";
    private String observedCandidate = "";
    private long observedChangedAt;
    private String lastForwardedCanonical = "";
    private String lastReplyCanonical = "";

    private static final class TextCandidate {
        final String text;
        final Rect bounds;
        final String id;
        final String desc;
        TextCandidate(String text, Rect bounds, String id, String desc) {
            this.text = text;
            this.bounds = bounds;
            this.id = id;
            this.desc = desc;
        }
    }

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SEND_REPLY.equals(intent.getAction())) return;
            String reply = clean(intent.getStringExtra(EXTRA_REPLY));
            if (reply.isEmpty()) return;
            lastReplyCanonical = canonical(reply);
            pendingReply = reply;
            sendInFlight = false;
            setBridgeState("REPLY_QUEUED");
            attemptPendingReply();
        }
    };

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try { inspectSamsungSurface(); }
            catch (Throwable t) { log("poll_error", t.getClass().getSimpleName() + ": " + safe(t.getMessage())); }
            main.postDelayed(this, POLL_MS);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        IntentFilter filter = new IntentFilter(ACTION_SEND_REPLY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(commandReceiver, filter);
        destroyed = false;
        setBridgeState("WAITING_SAMSUNG");
        log("service", "ATIVO · THIN TRANSPORT · QUEUED SEND V2");
        main.removeCallbacks(poller);
        main.post(poller);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!SAMSUNG_INCALL.contentEquals(event.getPackageName())) return;
        inspectSamsungSurface();
    }

    private void inspectSamsungSurface() {
        AccessibilityNodeInfo root = findSamsungRoot();
        long now = System.currentTimeMillis();
        if (root == null) {
            if (!pendingReply.isEmpty()) setBridgeState("WAITING_SAMSUNG_WITH_REPLY");
            else if (lastSamsungSeenAt > 0 && now - lastSamsungSeenAt > SESSION_GONE_MS) {
                observedCandidate = "";
                observedChangedAt = 0L;
                lastForwardedCanonical = "";
                lastReplyCanonical = "";
                setBridgeState("WAITING_SAMSUNG");
                lastSamsungSeenAt = 0L;
            }
            return;
        }
        lastSamsungSeenAt = now;

        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) {
            tryOpenTextCall(root);
            setBridgeState(pendingReply.isEmpty() ? "OPENING_TEXT_CALL" : "OPENING_TEXT_CALL_FOR_REPLY");
            return;
        }

        if (!pendingReply.isEmpty()) {
            attemptPendingReply();
            return;
        }

        setBridgeState("LISTENING");

        // Primary ears are VOICE_CALL PCM. Samsung transcript is fallback only.
        if (RebornTranscriptionService.isRunning() && RebornTranscriptionService.isUsingExternalPcm()) return;

        String candidate = clean(findCustomerCandidate(root, edit));
        if (candidate.isEmpty()) {
            observedCandidate = "";
            observedChangedAt = 0L;
            return;
        }

        String canon = canonical(candidate);
        if (canon.isEmpty() || canon.equals(lastReplyCanonical) || canon.equals(lastForwardedCanonical)) return;

        if (!canon.equals(canonical(observedCandidate))) {
            observedCandidate = candidate;
            observedChangedAt = now;
            control.edit().putString("live_customer_partial", candidate).apply();
            return;
        }

        if (observedChangedAt > 0 && now - observedChangedAt >= STABLE_MS) {
            lastForwardedCanonical = canon;
            observedCandidate = "";
            observedChangedAt = 0L;
            control.edit().putString("live_customer", candidate).putString("live_customer_partial", "").apply();
            log("fallback_customer", candidate);
            RebornCentral.onCustomerText(candidate);
        }
    }

    private void attemptPendingReply() {
        if (pendingReply.isEmpty() || sendInFlight) return;
        long now = System.currentTimeMillis();
        if (now - lastPendingAttemptAt < PENDING_RETRY_MS) return;
        lastPendingAttemptAt = now;

        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        if (root == null || edit == null) {
            if (root != null) tryOpenTextCall(root);
            setBridgeState("WAITING_TEXT_CALL_FOR_REPLY");
            return;
        }

        sendInFlight = true;
        setBridgeState("SENDING");
        final String reply = pendingReply;
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
        boolean set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        log("set_text", String.valueOf(set));
        if (!set) {
            sendInFlight = false;
            setBridgeState("SET_TEXT_RETRY");
            return;
        }
        main.postDelayed(() -> pressSend(reply, 0), 220L);
    }

    /** Never clicks generic More/RTT. Only explicit Call Assistant/Text Call nodes. */
    private boolean tryOpenTextCall(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - lastOpenAttemptAt < OPEN_COOLDOWN_MS) return false;
        AccessibilityNodeInfo target = findTextCallEntry(root);
        if (target == null) target = findExplicitCallAssistant(root);
        if (target == null) return false;
        AccessibilityNodeInfo clickable = clickableSelfOrParent(target);
        if (clickable == null) return false;
        lastOpenAttemptAt = now;
        boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        log("auto_open", ok ? "EXPLICIT_ASSIST_CLICKED" : "EXPLICIT_ASSIST_CLICK_FAILED");
        return ok;
    }

    private AccessibilityNodeInfo findTextCallEntry(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String t = canonical(value(node.getText()));
        String d = canonical(value(node.getContentDescription()));
        String id = lower(node.getViewIdResourceName());
        if (t.contains("chamada de texto") || t.contains("text call") || t.contains("bixby text call") ||
                d.contains("chamada de texto") || d.contains("text call") || d.contains("bixby text call") ||
                id.contains("text_call") || id.contains("bixby_text_call")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findTextCallEntry(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findExplicitCallAssistant(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String t = canonical(value(node.getText()));
        String d = canonical(value(node.getContentDescription()));
        String id = lower(node.getViewIdResourceName());
        if (t.equals("assistente de chamada") || t.equals("call assistant") ||
                d.equals("assistente de chamada") || d.equals("call assistant") ||
                id.contains("call_assistant") || id.contains("callassistant")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findExplicitCallAssistant(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private String findCustomerCandidate(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        List<TextCandidate> items = new ArrayList<>();
        collectTexts(root, items, edit);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        Rect editor = new Rect();
        edit.getBoundsInScreen(editor);
        for (int i = items.size() - 1; i >= 0; i--) {
            TextCandidate c = items.get(i);
            String s = clean(c.text);
            if (s.length() < 2 || s.length() > 300) continue;
            String canon = canonical(s);
            if (isSamsungChrome(canon)) continue;
            if (canon.equals(lastReplyCanonical)) continue;
            if (!c.bounds.isEmpty()) {
                if (c.bounds.centerX() > (int) (screenWidth * 0.62f)) continue;
                if (!editor.isEmpty() && c.bounds.top >= editor.top - dp(30)) continue;
            }
            String meta = canonical(c.id + " " + c.desc);
            if (meta.contains("outgoing") || meta.contains("sender") || meta.contains("assistant reply") || meta.contains("my message")) continue;
            return s;
        }
        return "";
    }

    private void collectTexts(AccessibilityNodeInfo node, List<TextCandidate> out, AccessibilityNodeInfo edit) {
        if (node == null) return;
        if (node != edit && !node.isEditable() && node.getText() != null) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            out.add(new TextCandidate(node.getText().toString(), r,
                    value(node.getViewIdResourceName()), value(node.getContentDescription())));
        }
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), out, edit);
    }

    private boolean isSamsungChrome(String l) {
        return l.isEmpty() || l.equals("escrever resposta") || l.equals("escrever") || l.equals("enviar") || l.equals("send") ||
                l.equals("repetir") || l.equals("urgente") || l.equals("quem fala") || l.equals("mais") ||
                l.contains("ligar lhe mais tarde") || l.contains("chamada de texto") || l.contains("text call") ||
                l.contains("assistente de chamada") || l.contains("assistente de voz") || l.contains("converter a sua voz em texto") ||
                l.contains("mantenha se em linha") || l.contains("se quiser continuar") || l.contains("rtt") ||
                l.contains("mudada para chamada de voz") || l.contains("desligar") || l.contains("teclado") ||
                l.contains("altifalante") || l.contains("bluetooth") || l.contains("mensagem sugerida") || l.contains("sugestao");
    }

    private void pressSend(String expected, int attempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        if (root == null || edit == null) {
            sendInFlight = false;
            setBridgeState("WAITING_TEXT_CALL_FOR_REPLY");
            return;
        }

        AccessibilityNodeInfo send = findSendButton(root);
        if (send != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(send);
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                main.postDelayed(() -> verifySent(expected, attempt + 1), 450L);
                return;
            }
            Rect r = new Rect();
            send.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                tapBounds(r);
                main.postDelayed(() -> verifySent(expected, attempt + 1), 500L);
                return;
            }
        }

        if (attempt == 0 && Build.VERSION.SDK_INT >= 30) {
            edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            main.postDelayed(() -> verifySent(expected, 1), 450L);
            return;
        }

        tapRightOfEditor(edit);
        main.postDelayed(() -> verifySent(expected, attempt + 1), 500L);
    }

    private void verifySent(String expected, int nextAttempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);

        // Losing the editor is NOT proof that the message was sent. Keep it queued and retry.
        if (root == null || edit == null) {
            sendInFlight = false;
            setBridgeState("VERIFY_WAITING_TEXT_CALL");
            return;
        }

        String current = edit.getText() == null ? "" : clean(edit.getText().toString());
        if (current.isEmpty() || !current.equals(expected)) {
            log("send", "SEND_CONFIRMED");
            if (expected.equals(pendingReply)) pendingReply = "";
            sendInFlight = false;
            setBridgeState("LISTENING");
            return;
        }

        if (nextAttempt <= 2) {
            pressSend(expected, nextAttempt);
        } else {
            log("send", "SEND_NOT_CONFIRMED_REQUEUED");
            sendInFlight = false;
            setBridgeState("SEND_RETRY_QUEUED");
        }
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = lower(node.getText());
        String desc = lower(node.getContentDescription());
        String id = lower(node.getViewIdResourceName());
        if (text.contains("enviar") || text.equals("send") || desc.contains("enviar") || desc.contains("send") ||
                id.contains("send") || id.contains("text_call_send") || id.contains("message_send")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findSendButton(node.getChild(i));
            if (r != null) return r;
        }
        return null;
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

    private void tapBounds(Rect r) {
        if (Build.VERSION.SDK_INT < 24) return;
        Path path = new Path();
        path.moveTo(r.centerX(), r.centerY());
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70)).build(), null, null);
    }

    private void tapRightOfEditor(AccessibilityNodeInfo edit) {
        if (Build.VERSION.SDK_INT < 24) return;
        Rect r = new Rect();
        edit.getBoundsInScreen(r);
        if (r.isEmpty()) return;
        int width = getResources().getDisplayMetrics().widthPixels;
        Path path = new Path();
        path.moveTo(Math.min(width - dp(24), r.right + dp(34)), r.centerY());
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null);
    }

    private void setBridgeState(String state) {
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        control.edit().putString("turn_state", state).putString("bridge_state", state).apply();
        log("turn_state", state);
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
    private String value(CharSequence s) { return s == null ? "" : s.toString(); }
    private String value(String s) { return s == null ? "" : s; }
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
        super.onDestroy();
    }
}
