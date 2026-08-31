package com.lumin.app;

import android.accessibilityservice.AccessibilityButtonController;
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
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

/** Samsung bridge for REBORN AI Calling -> Samsung Text Call. */
public class SofiaOverlayAccessibilityService extends SofiaAccessibilityService {
    // Prepare REBORN immediately. Samsung's mandatory disclosure is never suppressed;
    // the pending-reply watchdog injects the opening at the first instant the composer unlocks.
    private static final long OPENING_DELAY_MS = 0L;
    private static final long PENDING_REPLY_POLL_MS = 120L;

    private SharedPreferences control;
    private SharedPreferences diag;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastAutoTapAt = 0L;
    private long lastForceSendAt = 0L;
    private String lastForcedText = "";
    private boolean openingScheduled = false;
    private boolean callSeen = false;
    private boolean phoneReceiverRegistered = false;

    private final Runnable pendingReplyWatchdog = new Runnable() {
        @Override public void run() {
            try { maybeRecoverPendingReply(); } catch (Throwable ignored) {}
            if (callSeen) main.postDelayed(this, PENDING_REPLY_POLL_MS);
        }
    };

    private final BroadcastReceiver phoneStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            if (state == null) return;
            if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state) || TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                callSeen = true;
                if (diag != null) diag.edit().putString("call_state", state).apply();
                main.removeCallbacks(pendingReplyWatchdog);
                main.post(pendingReplyWatchdog);
                return;
            }
            if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                if (diag != null) diag.edit().putString("call_state", "IDLE").apply();
                if (callSeen) {
                    callSeen = false;
                    main.postDelayed(() -> RebornCallSessionSync.finalizeCallAsync(SofiaOverlayAccessibilityService.this), 700L);
                }
                main.removeCallbacks(pendingReplyWatchdog);
                openingScheduled = false;
                lastForcedText = "";
            }
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        if (!control.contains("shortcut_overlay_enabled")) {
            control.edit().putBoolean("shortcut_overlay_enabled", true).apply();
        }
        migrateLegacyOpening();

        try {
            IntentFilter phone = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneStateReceiver, phone, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(phoneStateReceiver, phone);
            phoneReceiverRegistered = true;
        } catch (Throwable t) {
            diag.edit().putString("phone_state_receiver", "ERRO: " + (t.getMessage() == null ? "" : t.getMessage())).apply();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                AccessibilityButtonController controller = getAccessibilityButtonController();
                controller.registerAccessibilityButtonCallback(
                        new AccessibilityButtonController.AccessibilityButtonCallback() {
                            @Override public void onClicked(AccessibilityButtonController controller) {
                                boolean enabled = SofiaShortcutController.toggle(control);
                                diag.edit().putString("shortcut", enabled ? "SOFIA_VISIBLE" : "SOFIA_HIDDEN").apply();
                            }
                        }
                );
            } catch (Exception ignored) {}
        }
    }

    private void migrateLegacyOpening() {
        if (control == null) return;
        String current = control.getString("agent_opening", "").trim();
        String n = normalize(current);
        if (current.isEmpty() ||
                n.contains("falo da mypoupar") ||
                n.contains("perceber se consegue poupar") ||
                n.contains("vamos direto ao assunto") ||
                n.contains("quer ver onde pode poupar")) {
            String replacement = "Olá, está a falar com a MyPoupar. Esta chamada usa assistência de voz para transformar a conversa em texto e gerar respostas. Se estiver de acordo, continuamos.";
            control.edit().putString("agent_opening", replacement).apply();
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        super.onAccessibilityEvent(event);
        if (event == null || event.getPackageName() == null) return;
        if (!"com.samsung.android.incallui".contentEquals(event.getPackageName())) return;
        callSeen = true;
        maybeAutoEnterTextCall();
        maybeRecoverPendingReply();
        maybeForceSendGeneratedReply();
        main.removeCallbacks(pendingReplyWatchdog);
        main.postDelayed(pendingReplyWatchdog, PENDING_REPLY_POLL_MS);
    }

    private void maybeAutoEnterTextCall() {
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (diag == null) diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        if (!control.getBoolean("auto_text_call", true)) return;

        long armedAt = control.getLong("auto_text_call_armed_at", 0L);
        if (armedAt <= 0L) return;
        long age = System.currentTimeMillis() - armedAt;
        if (age < 0L || age > 120_000L) {
            control.edit().remove("auto_text_call_armed_at").apply();
            openingScheduled = false;
            diag.edit().putString("auto_text_call", "EXPIRADO").apply();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isSamsung(root)) return;

        if (findEditable(root) != null) {
            if (!openingScheduled) {
                openingScheduled = true;
                control.edit().remove("auto_text_call_armed_at").apply();
                String opening = control.getString("agent_opening", SofiaAgentProfile.opening()).trim();
                if (!opening.isEmpty()) control.edit().putString("suggested_reply", opening).apply();
                diag.edit().putString("auto_text_call", "TEXT_CALL_ATIVO · ABERTURA_REBORN_PRONTA").apply();
                main.post(this::sendConfiguredOpeningIfStillInTextCall);
                main.removeCallbacks(pendingReplyWatchdog);
                main.post(pendingReplyWatchdog);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAutoTapAt < 700L) return;

        AccessibilityNodeInfo textCall = findAction(root, new String[]{"chamada de texto", "text call"});
        if (textCall != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(textCall);
            if (clickable != null) {
                lastAutoTapAt = now;
                boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                diag.edit().putString("auto_text_call", ok ? "A_ABRIR_TEXT_CALL" : "CLICK_TEXT_CALL_FALHOU").apply();
                return;
            }
        }

        AccessibilityNodeInfo callAssist = findAction(root, new String[]{"assistente de chamada", "call assist"});
        if (callAssist != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(callAssist);
            if (clickable != null) {
                lastAutoTapAt = now;
                boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                diag.edit().putString("auto_text_call", ok ? "A_ABRIR_CALL_ASSIST" : "CLICK_CALL_ASSIST_FALHOU").apply();
                return;
            }
        }

        diag.edit().putString("auto_text_call", "A_AGUARDAR_BOTAO").apply();
    }

    private void maybeRecoverPendingReply() {
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (diag == null) diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        if (!"AUTO".equals(control.getString("mode", "AUTO"))) return;

        String pending = control.getString("suggested_reply", "").trim();
        if (pending.isEmpty()) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isSamsung(root)) return;
        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null || !edit.isEnabled()) {
            diag.edit().putString("composer_watchdog", "PENDENTE · SAMSUNG_BLOQUEADO").apply();
            return;
        }

        String current = edit.getText() == null ? "" : edit.getText().toString().trim();
        if (same(current, pending)) {
            diag.edit().putString("composer_watchdog", "TEXTO_PRONTO · A_ENVIAR").apply();
            maybeForceSendGeneratedReply();
            return;
        }
        if (!current.isEmpty()) return;

        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pending);
        boolean ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        diag.edit().putString("composer_watchdog", ok ? "RECUPERADO · SET_TEXT" : "SET_TEXT_FALHOU · NOVA_TENTATIVA").apply();
        if (ok) main.postDelayed(this::maybeForceSendGeneratedReply, 40L);
    }

    private void maybeForceSendGeneratedReply() {
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (diag == null) diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        if (!"AUTO".equals(control.getString("mode", "AUTO"))) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isSamsung(root)) return;
        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null || !edit.isEnabled() || edit.getText() == null) return;

        String current = edit.getText().toString().trim();
        if (current.isEmpty() || current.length() < 2) return;
        String suggested = control.getString("suggested_reply", "").trim();
        String generated = diag.getString("last_reply", "").trim();
        if (!same(current, suggested) && !same(current, generated)) return;

        long now = System.currentTimeMillis();
        if (same(current, lastForcedText) && now - lastForceSendAt < 1200L) return;

        Rect editRect = new Rect();
        Rect rootRect = new Rect();
        edit.getBoundsInScreen(editRect);
        root.getBoundsInScreen(rootRect);
        if (editRect.isEmpty() || rootRect.isEmpty()) return;

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                boolean ime = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                if (ime) {
                    lastForcedText = current;
                    lastForceSendAt = now;
                    diag.edit().putString("force_send", "IME_ENTER").apply();
                    main.postDelayed(this::maybeForceSendGeneratedReply, 140L);
                    return;
                }
            } catch (Throwable ignored) {}
        }

        float density = getResources().getDisplayMetrics().density;
        float x = Math.min(rootRect.right - 18f * density,
                Math.max(editRect.right + 24f * density, rootRect.right - 48f * density));
        float y = editRect.centerY();
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 55);
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        lastForcedText = current;
        lastForceSendAt = now;
        diag.edit().putString("force_send", ok ? "GESTURE_SEND" : "GESTURE_FAILED").apply();
    }

    private void sendConfiguredOpeningIfStillInTextCall() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo edit = isSamsung(root) ? findEditable(root) : null;
        String opening = control.getString("agent_opening", SofiaAgentProfile.opening()).trim();
        if (opening.isEmpty()) return;
        control.edit().putString("suggested_reply", opening).apply();

        if (edit == null) {
            diag.edit().putString("auto_text_call", "ABERTURA_EM_FILA · A_AGUARDAR_COMPOSITOR").apply();
            main.removeCallbacks(pendingReplyWatchdog);
            main.post(pendingReplyWatchdog);
            return;
        }

        if (edit.isEnabled()) {
            maybeRecoverPendingReply();
            diag.edit().putString("auto_text_call", "ABERTURA_REBORN_IMEDIATA · 0ms").apply();
        } else {
            diag.edit().putString("auto_text_call", "ABERTURA_REBORN_EM_FILA · SAMSUNG_BLOQUEADO").apply();
            main.removeCallbacks(pendingReplyWatchdog);
            main.post(pendingReplyWatchdog);
        }
    }

    private boolean isSamsung(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null &&
                "com.samsung.android.incallui".contentEquals(root.getPackageName());
    }

    private boolean same(String a, String b) {
        if (a == null || b == null || a.trim().isEmpty() || b.trim().isEmpty()) return false;
        return normalize(a).equals(normalize(b));
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    private AccessibilityNodeInfo findAction(AccessibilityNodeInfo node, String[] needles) {
        if (node == null) return null;
        String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(Locale.ROOT);
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (text.contains(needle) || desc.contains(needle)) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findAction(node.getChild(i), needles);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo e = findEditable(node.getChild(i));
            if (e != null) return e;
        }
        return null;
    }

    private AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    @Override public void onDestroy() {
        main.removeCallbacks(pendingReplyWatchdog);
        if (phoneReceiverRegistered) {
            try { unregisterReceiver(phoneStateReceiver); } catch (Throwable ignored) {}
            phoneReceiverRegistered = false;
        }
        super.onDestroy();
    }
}
