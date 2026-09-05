package com.lumin.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

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
    private TextView transcript;
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
        ScrollView scroll = new ScrollView(service);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(4, 8, 17));

        panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.TOP);
        panel.setPadding(dp(20), dp(34), dp(20), dp(24));
        panel.setBackgroundColor(Color.rgb(4, 8, 17));
        scroll.addView(panel);

        LinearLayout header = new LinearLayout(service);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("REBORN AI\nCALLING", 28, Color.WHITE, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView live = text("● LIVE", 13, Color.rgb(106,235,183), true);
        live.setPadding(dp(12), dp(8), dp(12), dp(8));
        header.addView(live);
        panel.addView(header);

        status = text("SOFIA · AUTO · IA READY", 14, Color.rgb(106,235,183), true);
        status.setPadding(0, dp(22), 0, dp(8));
        panel.addView(status);

        TextView customerTitle = text("CLIENTE", 11, Color.rgb(148,160,194), true);
        panel.addView(customerTitle);
        customer = text("À espera do cliente…", 24, Color.WHITE, true);
        customer.setPadding(0, dp(5), 0, dp(22));
        panel.addView(customer);

        TextView replyTitle = text("RESPOSTA SOFIA", 11, Color.rgb(148,160,194), true);
        panel.addView(replyTitle);
        reply = text("A ouvir…", 20, Color.rgb(106,235,183), true);
        reply.setPadding(0, dp(5), 0, dp(18));
        panel.addView(reply);

        LinearLayout buttons = new LinearLayout(service);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button auto = smallButton("AUTO");
        Button assisted = smallButton("ASSISTIDO");
        Button manual = smallButton("FALAR EU");
        auto.setOnClickListener(v -> setMode("AUTO"));
        assisted.setOnClickListener(v -> setMode("ASSISTED"));
        manual.setOnClickListener(v -> {
            setMode("MANUAL");
            SofiaShortcutController.setEnabled(control, false);
            hide();
        });
        buttons.addView(auto, weight());
        buttons.addView(assisted, weight());
        buttons.addView(manual, weight());
        panel.addView(buttons);

        TextView trTitle = text("TRANSCRIÇÃO AO VIVO", 11, Color.rgb(148,160,194), true);
        trTitle.setPadding(0, dp(24), 0, dp(6));
        panel.addView(trTitle);
        transcript = text("À espera de voz…", 15, Color.rgb(215,222,239), false);
        transcript.setPadding(dp(14), dp(14), dp(14), dp(14));
        transcript.setBackgroundColor(Color.rgb(14,22,39));
        panel.addView(transcript, new LinearLayout.LayoutParams(-1, -2));

        TextView foot = text("Samsung Call Assistant ativo por baixo · REBORN controla a experiência", 12, Color.rgb(110,123,153), false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(24), 0, 0);
        panel.addView(foot);

        panel.setTag(scroll);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean shortcutEnabled = SofiaShortcutController.isEnabled(control);
            boolean textCallActuallyOpen = isSamsungTextCallOpenNow();
            if (shortcutEnabled && textCallActuallyOpen) show(); else if (!shortcutEnabled) hide();

            if (added) {
                String mode = control.getString("mode", "AUTO");
                String label = "ASSISTED".equals(mode) ? "ASSISTIDO" : mode;
                String qwen = diag.getString("qwen", "—");
                String ai = qwen.startsWith("OK") || qwen.startsWith("CALL READY") || qwen.startsWith("READY") ? "ONLINE" : (qwen.startsWith("ERRO") ? "OFFLINE" : "READY");
                status.setText("SOFIA · " + label + " · IA " + ai);

                String c = control.getString("live_customer", "");
                customer.setText(c.isEmpty() ? "À espera do cliente…" : "“" + c + "”");

                String r = control.getString("suggested_reply", "");
                reply.setText(r.isEmpty() ? "A ouvir e a pensar…" : r);

                String tr = control.getString("live_transcript", "");
                transcript.setText(tr.isEmpty() ? "À espera de voz…" : tr);
            }
            main.postDelayed(this, 250);
        }
    };

    private boolean isSamsungTextCallOpenNow() {
        try {
            AccessibilityNodeInfo active = service.getRootInActiveWindow();
            if (isSamsungTextCallRoot(active)) return true;
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo root = w == null ? null : w.getRoot();
                    if (isSamsungTextCallRoot(root)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isSamsungTextCallRoot(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null &&
                "com.samsung.android.incallui".contentEquals(root.getPackageName()) && hasEditable(root);
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
        Object tag = panel.getTag();
        if (!(tag instanceof ScrollView)) return;
        ScrollView rootView = (ScrollView) tag;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(rootView, lp);
            added = true;
            diag.edit().putString("overlay", "FULL_REBORN_VISIBLE").apply();
        } catch (Exception e) {
            diag.edit().putString("overlay", "ERRO: " + e.getClass().getSimpleName()).apply();
        }
    }

    private void hide() {
        if (!added || panel == null) return;
        Object tag = panel.getTag();
        if (!(tag instanceof ScrollView)) return;
        try { wm.removeView((ScrollView) tag); } catch (Exception ignored) {}
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
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
        p.setMargins(dp(3), dp(4), dp(3), 0);
        return p;
    }

    private int dp(int n) {
        return Math.round(n * service.getResources().getDisplayMetrics().density);
    }
}
