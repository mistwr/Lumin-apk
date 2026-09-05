package com.lumin.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Live SOFIA cockpit rendered above Samsung Text Call.
 * Samsung remains the privileged STT/TTS surface; this panel only shows
 * SOFIA state and lets the user choose AUTO / ASSISTIDO / MANUAL.
 */
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
    private TextView telemetry;
    private TextView queue;
    private Button approve;
    private Button auto;
    private Button assisted;
    private Button manual;
    private boolean added = false;

    public SofiaCallOverlay(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
        this.control = service.getSharedPreferences("sofia_control", AccessibilityService.MODE_PRIVATE);
        this.diag = service.getSharedPreferences("sofia_diag", AccessibilityService.MODE_PRIVATE);
    }

    public void start() {
        build();
        main.removeCallbacks(refresh);
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
        panel.setPadding(dp(16), dp(13), dp(16), dp(13));
        panel.setBackground(card(Color.rgb(10, 14, 30), 0xF5, 18, Color.rgb(45, 58, 94)));
        panel.setElevation(dp(10));

        LinearLayout header = new LinearLayout(service);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView brand = text("SOFIA  •  MyPoupar", 15, Color.WHITE, true);
        TextView live = pill("● LIVE", Color.rgb(105, 235, 183));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(live);
        panel.addView(header);

        status = text("Samsung Text Call · a preparar", 12, Color.rgb(158, 173, 211), false);
        status.setPadding(0, dp(5), 0, dp(10));
        panel.addView(status);

        TextView cLabel = text("CLIENTE", 10, Color.rgb(118, 136, 184), true);
        panel.addView(cLabel);
        customer = text("À espera da transcrição…", 15, Color.WHITE, true);
        customer.setMaxLines(3);
        customer.setPadding(0, dp(2), 0, dp(10));
        panel.addView(customer);

        TextView sLabel = text("SOFIA", 10, Color.rgb(118, 136, 184), true);
        panel.addView(sLabel);
        reply = text("À espera…", 14, Color.rgb(109, 239, 188), false);
        reply.setMaxLines(3);
        reply.setPadding(0, dp(2), 0, dp(8));
        panel.addView(reply);

        telemetry = text("IA pronta · resposta —", 11, Color.rgb(158, 173, 211), false);
        queue = text("Fila 0 · Samsung driver ativo", 11, Color.rgb(126, 141, 179), false);
        panel.addView(telemetry);
        panel.addView(queue);

        LinearLayout modes = new LinearLayout(service);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setPadding(0, dp(7), 0, 0);
        auto = smallButton("AUTO");
        assisted = smallButton("ASSISTIDO");
        manual = smallButton("MANUAL");
        auto.setOnClickListener(v -> setMode("AUTO"));
        assisted.setOnClickListener(v -> setMode("ASSISTED"));
        manual.setOnClickListener(v -> setMode("MANUAL"));
        modes.addView(auto, weight());
        modes.addView(assisted, weight());
        modes.addView(manual, weight());
        panel.addView(modes);

        approve = smallButton("ENVIAR RESPOSTA");
        approve.setVisibility(View.GONE);
        approve.setOnClickListener(v -> approveSuggestedReply());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(44));
        ap.topMargin = dp(6);
        panel.addView(approve, ap);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean shortcutEnabled = SofiaShortcutController.isEnabled(control);
            boolean textCallActuallyOpen = isSamsungTextCallOpenNow();
            if (shortcutEnabled && textCallActuallyOpen) show(); else hide();

            if (added) {
                String mode = control.getString("mode", "AUTO");
                String modeLabel = "ASSISTED".equals(mode) ? "ASSISTIDO" : mode;
                String surface = diag.getString("surface", "");
                String path = diag.getString("path", "READY");
                String qwen = diag.getString("qwen", "—");
                String stability = diag.getString("stability", "");
                String llmMs = diag.getString("llm_ms", "—");
                String q = diag.getString("queue", "0");

                status.setText("Samsung Text Call · " + modeLabel + (surface.contains("READY") ? " · ligado" : " · a detetar"));
                String c = control.getString("live_customer", "");
                customer.setText(c.isEmpty() ? "À espera da transcrição…" : c);
                String r = control.getString("suggested_reply", "");
                reply.setText(r.isEmpty() ? "À espera…" : r);

                String ai = path.contains("FAST") ? "Fast path" : (path.contains("QWEN") ? "Qwen local" : "IA pronta");
                if (qwen.startsWith("ERRO")) ai = "IA em fallback";
                telemetry.setText(ai + " · resposta " + ("—".equals(llmMs) ? "—" : llmMs + " ms"));
                queue.setText("Fila " + q + (stability.isEmpty() ? " · Samsung driver ativo" : " · " + stability));

                styleModeButton(auto, "AUTO".equals(mode));
                styleModeButton(assisted, "ASSISTED".equals(mode));
                styleModeButton(manual, "MANUAL".equals(mode));
                approve.setVisibility("ASSISTED".equals(mode) && !r.isEmpty() ? View.VISIBLE : View.GONE);
            }
            main.postDelayed(this, 180);
        }
    };

    private void approveSuggestedReply() {
        String r = control.getString("suggested_reply", "").trim();
        if (r.isEmpty()) return;
        Intent i = new Intent(SofiaAccessibilityService.ACTION_SEND_REPLY);
        i.setPackage(service.getPackageName());
        i.putExtra(SofiaAccessibilityService.EXTRA_REPLY, r);
        service.sendBroadcast(i);
    }

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
        if (added || panel == null) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = dp(52);
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
        v.setLineSpacing(0, 1.08f);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private TextView pill(String value, int color) {
        TextView v = text(value, 10, color, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(9), dp(4), dp(9), dp(4));
        v.setBackground(card(Color.rgb(17, 34, 42), 0xFF, 20, Color.rgb(39, 82, 75)));
        return v;
    }

    private Button smallButton(String label) {
        Button b = new Button(service);
        b.setText(label);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(216, 224, 248));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(card(Color.rgb(27, 34, 58), 0xFF, 12, Color.rgb(52, 64, 100)));
        return b;
    }

    private void styleModeButton(Button b, boolean selected) {
        if (selected) {
            b.setTextColor(Color.rgb(7, 21, 18));
            b.setBackground(card(Color.rgb(106, 235, 183), 0xFF, 12, Color.rgb(106, 235, 183)));
        } else {
            b.setTextColor(Color.rgb(216, 224, 248));
            b.setBackground(card(Color.rgb(27, 34, 58), 0xFF, 12, Color.rgb(52, 64, 100)));
        }
    }

    private GradientDrawable card(int rgb, int alpha, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor((alpha << 24) | (rgb & 0x00FFFFFF));
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int n) {
        return Math.round(n * service.getResources().getDisplayMetrics().density);
    }
}
