package com.lumin.app;

import android.accessibilityservice.AccessibilityButtonController;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

/**
 * Samsung-facing accessibility entry point.
 * The base service owns the SOFIA LIVE overlay and transcript driver.
 * This subclass adds the accessibility shortcut and the opt-in AI Calling
 * hand-off from a normal GSM call into Samsung Text Call.
 */
public class SofiaOverlayAccessibilityService extends SofiaAccessibilityService {
    private SharedPreferences control;
    private SharedPreferences diag;
    private long lastAutoTapAt = 0L;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        if (!control.contains("shortcut_overlay_enabled")) {
            control.edit().putBoolean("shortcut_overlay_enabled", true).apply();
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

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        super.onAccessibilityEvent(event);
        if (event == null || event.getPackageName() == null) return;
        if (!"com.samsung.android.incallui".contentEquals(event.getPackageName())) return;
        maybeAutoEnterTextCall();
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
            diag.edit().putString("auto_text_call", "EXPIRADO").apply();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null ||
                !"com.samsung.android.incallui".contentEquals(root.getPackageName())) return;

        // If the editable reply composer exists, Text Call is already active.
        if (hasEditable(root)) {
            control.edit().remove("auto_text_call_armed_at").apply();
            diag.edit().putString("auto_text_call", "TEXT_CALL_ATIVO").apply();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAutoTapAt < 700L) return;

        AccessibilityNodeInfo textCall = findAction(root, new String[]{
                "chamada de texto", "text call"
        });
        if (textCall != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(textCall);
            if (clickable != null) {
                lastAutoTapAt = now;
                boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                diag.edit().putString("auto_text_call", ok ? "A_ABRIR_TEXT_CALL" : "CLICK_TEXT_CALL_FALHOU").apply();
                return;
            }
        }

        // On some One UI layouts Text Call first lives behind Call Assist.
        AccessibilityNodeInfo callAssist = findAction(root, new String[]{
                "assistente de chamada", "call assist"
        });
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

    private boolean hasEditable(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasEditable(node.getChild(i))) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }
}
