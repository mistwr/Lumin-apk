package com.lumin.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SofiaCallOverlay {
    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final android.content.SharedPreferences control;
    private final android.content.SharedPreferences diag;
    private LinearLayout panel;
    private TextView status;
    private TextView customer;
    private TextView reply;
    private boolean added = false;

    public SofiaCallOverlay(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
        this.control = service.getSharedPreferences("sofia_control", AccessibilityService.MODE_PRIVATE);
        this.diag = service.getSharedPreferences("sofia_diag", AccessibilityService.MODE_PRIVATE);
    }

    public void start() {
        build();
        main.post(refresh);
    }

    public void stop() {
        main.removeCallbacks(refresh);
        hide();
    }

    public void onShortcutChanged(boolean enabled) {
        diag.edit().putString("shortcut", enabled ? "SOFIA_VISIBLE" : "SOFIA_HIDDEN").apply();
        if (!enabled) hide();
        else if (isSamsungTextCallOpenNow()) show();
    }

    private void build() {
        panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        panel.setBackgroundColor(Color.argb(225, 8, 12, 25));

        status = text("SOFIA · AUTO", 13, Color.rgb(106, 235, 183), true);
        customer = text("À espera do cliente…", 14, Color.WHITE, true);
        reply = text("", 13, Color.rgb(210, 217, 240), false);
        panel.addView(status);
        panel.addView(customer);
        panel.addView(reply);

        LinearLayout buttons = new LinearLayout(service);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button auto = smallButton("AUTO");
        Button assisted = smallButton("ASSISTIDO");
        Button manual = smallButton("MANUAL");
        auto.setOnClickListener(v -> setMode("AUTO"));
        assisted.setOnClickListener(v -> setMode("ASSISTED"));
        manual.setOnClickListener(v -> setMode("MANUAL"));
        buttons.addView(auto, weight());
        buttons.addView(assisted, weight());
        buttons.addView(manual, weight());
        panel.addView(buttons);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean shortcutEnabled = SofiaShortcutController.isEnabled(control);
            boolean textCallActuallyOpen = isSamsungTextCallOpenNow();
            if (shortcutEnabled && textCallActuallyOpen) show(); else hide();

            if (added) {
                String mode = control.getString("mode", "AUTO");
                String label = "ASSISTED".equals(mode) ? "ASSISTIDO" : mode;
                String qwen = diag.getString("qwen", "—");
                String ai = qwen.startsWith("OK") ? "ONLINE" : (qwen.startsWith("ERRO") ? "OFFLINE" : "READY");
                status.setText("SOFIA · " + label + " · IA " + ai);
                String c = control.getString("live_customer", "");
                customer.setText(c.isEmpty() ? "À espera do cliente…" : "Cliente: “" + c + "”");
                String r = control.getString("suggested_reply", "");
                reply.setText(r.isEmpty() ? "" : "Sofia: “" + r + "”");
            }
            main.postDelayed(this, 250);
        }
    };

    private boolean isSamsungTextCallOpenNow() {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null || root.getPackageName() == null ||
                    !"com.samsung.android.incallui".contentEquals(root.getPackageName())) return false;
            return hasEditable(root);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasEditable(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasEditable(node.getChild(i))) return true;
        }
        return false;
    }

    private void setMode(String mode) {
        control.edit().putString("mode", mode).apply();
    }

    private void show() {
        if (added) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = dp(72);
        try {
            wm.addView(panel, lp);
            added = true;
        } catch (Exception e) {
            diag.edit().putString("overlay", "ERRO: " + e.getClass().getSimpleName()).apply();
        }
    }

    private void hide() {
        if (!added || panel == null) return;
        try { wm.removeView(panel); } catch (Exception ignored) {}
        added = false;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(service);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setPadding(0, dp(2), 0, dp(2));
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button smallButton(String label) {
        Button b = new Button(service);
        b.setText(label);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1f);
        p.setMargins(dp(2), dp(4), dp(2), 0);
        return p;
    }

    private int dp(int n) {
        return Math.round(n * service.getResources().getDisplayMetrics().density);
    }
}
