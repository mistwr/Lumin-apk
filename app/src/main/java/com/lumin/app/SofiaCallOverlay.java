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
    private ScrollView rootView;
    private TextView status;
    private TextView turn;
    private TextView customer;
    private TextView reply;
    private TextView transcript;
    private boolean added = false;
    private long peekSamsungUntil = 0L;

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
        rootView = new ScrollView(service);
        rootView.setFillViewport(true);
        rootView.setVerticalScrollBarEnabled(true);
        rootView.setScrollbarFadingEnabled(false);
        rootView.setBackgroundColor(Color.rgb(4, 8, 17));

        panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.TOP);
        panel.setPadding(dp(20), dp(34), dp(20), dp(40));
        panel.setBackgroundColor(Color.rgb(4, 8, 17));
        rootView.addView(panel);

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
        status.setPadding(0, dp(22), 0, dp(4));
        panel.addView(status);

        turn = text("● A iniciar bridge…", 13, Color.rgb(255,198,94), true);
        turn.setPadding(0, 0, 0, dp(16));
        panel.addView(turn);

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
            hide();
        });
        buttons.addView(auto, weight());
        buttons.addView(assisted, weight());
        buttons.addView(manual, weight());
        panel.addView(buttons);

        LinearLayout tools = new LinearLayout(service);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        Button samsung = smallButton("VER SAMSUNG 10s");
        Button top = smallButton("↑ TOPO");
        samsung.setOnClickListener(v -> {
            peekSamsungUntil = System.currentTimeMillis() + 10000L;
            hide();
        });
        top.setOnClickListener(v -> rootView.smoothScrollTo(0, 0));
        tools.addView(samsung, weight());
        tools.addView(top, weight());
        panel.addView(tools);

        TextView trTitle = text("TRANSCRIÇÃO AO VIVO", 11, Color.rgb(148,160,194), true);
        trTitle.setPadding(0, dp(24), 0, dp(6));
        panel.addView(trTitle);
        transcript = text("À espera de voz…", 15, Color.rgb(215,222,239), false);
        transcript.setPadding(dp(14), dp(14), dp(14), dp(14));
        transcript.setBackgroundColor(Color.rgb(14,22,39));
        panel.addView(transcript, new LinearLayout.LayoutParams(-1, -2));

        TextView foot = text("Samsung Call Assistant fica ativo por baixo · REBORN controla a experiência", 12, Color.rgb(110,123,153), false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(24), 0, dp(24));
        panel.addView(foot);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean shortcutEnabled = SofiaShortcutController.isEnabled(control);
            boolean textCallActuallyOpen = isSamsungTextCallOpenNow();
            boolean peeking = System.currentTimeMillis() < peekSamsungUntil;
            if (shortcutEnabled && textCallActuallyOpen && !peeking) show();
            else if (!shortcutEnabled || peeking) hide();

            if (added) {
                String mode = control.getString("mode", "AUTO");
                String label = "ASSISTED".equals(mode) ? "ASSISTIDO" : mode;
                String qwen = diag.getString("qwen", "—");
                String ai = qwen.startsWith("OK") || qwen.startsWith("CALL READY") || qwen.startsWith("READY") ? "ONLINE" : (qwen.startsWith("ERRO") ? "OFFLINE" : "READY");
                status.setText("SOFIA · " + label + " · IA " + ai);

                String state = diag.getString("turn_state", "LISTENING");
                turn.setText(turnLabel(state));

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

    private String turnLabel(String state) {
        if ("STABILIZING".equals(state)) return "● A confirmar frase do cliente";
        if ("THINKING".equals(state)) return "● Cérebro REBORN a pensar";
        if ("SENDING".equals(state)) return "● A escrever/enviar no Samsung Text Call";
        if ("WAITING_REMOTE".equals(state)) return "● Resposta enviada · à espera do cliente";
        if ("MANUAL".equals(state)) return "● Controlo manual";
        if ("IDLE".equals(state)) return "● A preparar chamada";
        return "● A ouvir cliente";
    }

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
        if (!"MANUAL".equals(mode)) SofiaShortcutController.setEnabled(control, true);
    }

    private void show() {
        if (added || rootView == null) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(rootView, lp);
            added = true;
            diag.edit().putString("overlay", "FULL_REBORN_INTERACTIVE").apply();
        } catch (Exception e) {
            diag.edit().putString("overlay", "ERRO: " + e.getClass().getSimpleName()).apply();
        }
    }

    private void hide() {
        if (!added || rootView == null) return;
        try { wm.removeView(rootView); } catch (Exception ignored) {}
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
        b.setClickable(true);
        b.setFocusable(true);
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
