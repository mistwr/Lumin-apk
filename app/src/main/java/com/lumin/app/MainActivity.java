package com.lumin.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(4, 8, 17);
    private static final int CARD = Color.rgb(14, 22, 39);
    private static final int CARD2 = Color.rgb(20, 31, 54);
    private static final int TEXT = Color.rgb(242, 245, 252);
    private static final int MUTED = Color.rgb(148, 160, 194);
    private static final int MINT = Color.rgb(106, 235, 183);
    private static final int LINE = Color.rgb(39, 55, 84);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences control;
    private TextView liveStatus;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            ui.postDelayed(this, 700);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (!control.contains("mode")) control.edit().putString("mode", "AUTO").apply();
        showHome();
        Executors.newSingleThreadExecutor().submit(() -> SupabaseSyncClient.flush(this));
        warmLocalBrainAsync();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void warmLocalBrainAsync() {
        if (!LocalRebornEngine.isInstalled(this)) return;
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                LocalRebornEngine.warmUp(this);
                getSharedPreferences("sofia_diag", MODE_PRIVATE).edit()
                        .putString("qwen", "READY · " + LocalRebornEngine.backendName() +
                                " · init " + LocalRebornEngine.lastInitMs() + " ms")
                        .apply();
            } catch (Throwable ex) {
                getSharedPreferences("sofia_diag", MODE_PRIVATE).edit()
                        .putString("last_error", "Warm-up: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()))
                        .apply();
            }
        });
    }

    private void showHome() {
        ScrollView scroll = baseScroll();
        LinearLayout root = contentRoot(scroll);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("REBORN AI\nCalling Intelligence", 30, TEXT, true);
        hero.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chip = text("● CONNECTED", 13, MINT, true);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(round(Color.rgb(12, 62, 49), MINT, 999));
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        hero.addView(chip);
        root.addView(hero);

        TextView subtitle = text("A operação comercial, os agentes e as chamadas num único workspace.", 16, MUTED, false);
        subtitle.setPadding(0, dp(10), 0, 0);
        root.addView(subtitle);

        LinearLayout workspace = card();
        workspace.addView(sectionLabel("WORKSPACE"));
        workspace.addView(text("Miguel Ferreira\nSuper Admin", 22, TEXT, true));
        TextView w = text("REBORN Brain ligado ao SD Dialer, histórico, follow-ups e inteligência comercial.", 15, Color.rgb(197, 206, 229), false);
        w.setPadding(0, dp(8), 0, 0);
        workspace.addView(w);
        root.addView(workspace, cardParams(22));

        root.addView(sectionTitle("VISÃO DE HOJE"));
        LinearLayout row1 = metricRow();
        row1.addView(metric("CHAMADAS", "1", "↗ atividade"), weight());
        row1.addView(metric("VENDAS", "0", "resultado"), weight());
        root.addView(row1);
        LinearLayout row2 = metricRow();
        row2.addView(metric("LEADS", "85050", "base ativa"), weight());
        row2.addView(metric("AGENTES IA", "1", "disponíveis"), weight());
        root.addView(row2);

        root.addView(sectionTitle("AÇÃO PRINCIPAL"));
        LinearLayout action = card();
        action.addView(text("Faz a próxima chamada com contexto, memória e assistência em tempo real.", 19, TEXT, true));
        TextView a2 = text("Escolhe o agente, introduz o contacto e inicia.", 14, MUTED, false);
        a2.setPadding(0, dp(6), 0, dp(10));
        action.addView(a2);
        Button start = primary("Iniciar chamada com IA  →");
        start.setOnClickListener(v -> prepareAndOpenPhone());
        action.addView(start, new LinearLayout.LayoutParams(-1, dp(58)));
        root.addView(action, cardParams(8));

        root.addView(sectionTitle("OPERAÇÃO"));
        root.addView(nav("Leads e oportunidades", () -> toast("Leads ligados ao SD Dialer")));
        root.addView(nav("Histórico de chamadas", () -> toast("Histórico REBORN")));

        root.addView(sectionTitle("GESTÃO"));
        root.addView(nav("Agent Studio", () -> toast("Agent Studio")));
        root.addView(nav("Utilizadores e equipas", () -> toast("Gestão de equipas")));

        root.addView(sectionTitle("SISTEMA"));
        root.addView(nav("Definições e ligações", this::showSettings));

        TextView foot = text("MyPoupar · Feito por REBORN AI", 12, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(28), 0, dp(8));
        root.addView(foot);
        setContentView(scroll);
    }

    private void showSettings() {
        ScrollView scroll = baseScroll();
        LinearLayout root = contentRoot(scroll);

        TextView back = text("←  REBORN AI", 14, MINT, true);
        back.setPadding(0, 0, 0, dp(14));
        back.setOnClickListener(v -> showHome());
        root.addView(back);
        root.addView(text("SISTEMA", 13, MINT, true));
        root.addView(text("Definições", 34, TEXT, true));
        TextView intro = text("Configuração técnica do REBORN AI Calling. Esta área fica separada da operação diária.", 16, Color.rgb(191, 201, 226), false);
        intro.setPadding(0, dp(8), 0, 0);
        root.addView(intro);

        LinearLayout statusCard = card();
        liveStatus = text("A verificar…", 17, MINT, true);
        statusCard.addView(liveStatus);
        root.addView(statusCard, cardParams(20));

        root.addView(sectionTitle("MODO DE CHAMADA"));
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setWeightSum(3f);
        modes.addView(modeButton("AUTO", "AUTO"), weight());
        modes.addView(modeButton("ASSISTIDO", "ASSISTED"), weight());
        modes.addView(modeButton("MANUAL", "MANUAL"), weight());
        root.addView(modes);

        root.addView(sectionTitle("SAMSUNG BRIDGE"));
        Button accessibility = navButton("Abrir Acessibilidade Android");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        root.addView(sectionTitle("CÉREBRO LOCAL"));
        Button install = navButton("Instalar / reinstalar modelo local");
        install.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        root.addView(install);
        Button test = navButton("Testar IA local");
        test.setOnClickListener(v -> testLocalAi());
        root.addView(test);

        root.addView(sectionTitle("ADMIN TÉCNICO"));
        Button diag = navButton("Consola e diagnóstico ao vivo");
        diag.setOnClickListener(v -> showDiagnostics());
        root.addView(diag);

        setContentView(scroll);
        refreshStatus();
    }

    private void showDiagnostics() {
        SharedPreferences d = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        String report = "Samsung Bridge: " + (isAccessibilityEnabled() ? "ATIVO" : "INATIVO") +
                "\nQwen backend: " + LocalRebornEngine.backendName() +
                "\nQwen init: " + LocalRebornEngine.lastInitMs() + " ms" +
                "\nÚltima geração: " + LocalRebornEngine.lastGenerationMs() + " ms" +
                "\nSurface: " + d.getString("surface", "—") +
                "\nÚltimo cliente: " + d.getString("last_customer", "—") +
                "\nCaminho: " + d.getString("path", "—") +
                "\nQwen: " + d.getString("qwen", "—") +
                "\nSET_TEXT: " + d.getString("set_text", "—") +
                "\nSEND: " + d.getString("send", "—") +
                "\nErro: " + d.getString("last_error", "—");
        new android.app.AlertDialog.Builder(this)
                .setTitle("REBORN · diagnóstico")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show();
    }

    private void testLocalAi() {
        toast("A testar cérebro local…");
        Executors.newSingleThreadExecutor().submit(() -> {
            SofiaAiHealth.Result r = SofiaAiHealth.check(this);
            runOnUiThread(() -> toast((r.online ? "IA LOCAL OK · " : "IA LOCAL OFF · ") + r.message));
        });
    }

    private void refreshStatus() {
        if (liveStatus == null) return;
        String mode = control.getString("mode", "AUTO");
        String label = "ASSISTED".equals(mode) ? "ASSISTIDO" : mode;
        String backend = LocalRebornEngine.backendName();
        String brain = LocalRebornEngine.isInstalled(this)
                ? "Qwen3 1.7B INT4 · " + ("NONE".equals(backend) ? "A AQUECER" : backend)
                : "Qwen3 1.7B INT4 · NÃO INSTALADO";
        liveStatus.setText("● Samsung Bridge · " + (isAccessibilityEnabled() ? "ATIVO" : "INATIVO") +
                "\n● " + brain +
                "\nModo · " + label);
    }

    private Button modeButton(String label, String value) {
        Button b = navButton(label);
        b.setTextSize(14);
        b.setOnClickListener(v -> {
            control.edit().putString("mode", value).apply();
            refreshStatus();
        });
        return b;
    }

    private void prepareAndOpenPhone() {
        if (!LocalRebornEngine.isInstalled(this)) {
            toast("Importa primeiro o Qwen3 local");
            return;
        }
        toast("REBORN a preparar Qwen3 para a chamada…");
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                LocalRebornEngine.resetConversation();
                LocalRebornEngine.warmUp(this);
                getSharedPreferences("sofia_diag", MODE_PRIVATE).edit()
                        .putString("qwen", "CALL READY · " + LocalRebornEngine.backendName())
                        .putString("last_error", "—")
                        .apply();
                runOnUiThread(() -> {
                    toast("Qwen3 " + LocalRebornEngine.backendName() + " pronto · abrir REBORN Phone");
                    openPhone();
                });
            } catch (Throwable ex) {
                String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                getSharedPreferences("sofia_diag", MODE_PRIVATE).edit().putString("last_error", msg).apply();
                runOnUiThread(() -> toast("Qwen3 não ficou pronto: " + msg));
            }
        });
    }

    private void openPhone() {
        startActivity(new Intent(this, RebornDialerActivity.class));
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private ScrollView baseScroll() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        return s;
    }

    private LinearLayout contentRoot(ScrollView scroll) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(34));
        scroll.addView(root);
        return root;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        c.setBackground(round(CARD, LINE, 26));
        return c;
    }

    private LinearLayout metric(String label, String value, String sub) {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.addView(sectionLabel(label));
        c.addView(text(value, 31, TEXT, true));
        c.addView(text(sub, 13, Color.rgb(185, 196, 221), false));
        return c;
    }

    private LinearLayout metricRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(2f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        row.setLayoutParams(p);
        return row;
    }

    private View nav(String label, Runnable action) {
        Button b = navButton(label + "     →");
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Button navButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackground(round(CARD2, LINE, 22));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(68));
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private Button primary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.rgb(5, 18, 17));
        b.setAllCaps(false);
        b.setBackground(round(MINT, MINT, 22));
        return b;
    }

    private TextView sectionTitle(String s) {
        TextView v = text(s, 13, MUTED, true);
        v.setPadding(0, dp(28), 0, dp(8));
        return v;
    }

    private TextView sectionLabel(String s) {
        TextView v = text(s, 12, MUTED, true);
        v.setLetterSpacing(.08f);
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(0, 1.12f);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams cardParams(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(dp(5), 0, dp(5), 0);
        return p;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
