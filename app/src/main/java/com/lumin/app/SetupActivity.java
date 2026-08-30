package com.lumin.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.Executors;

public class SetupActivity extends AppCompatActivity {
    private TextView driverState, aiState, modelState, modeState;
    private Button installBrain, testBrain;
    private SharedPreferences control;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (!control.contains("mode")) control.edit().putString("mode", "AUTO").apply();
        setContentView(buildUi());
        refreshAll();
        testAi();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(6,9,20));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(40));
        scroll.addView(root);

        root.addView(text("SOFIA", 42, Color.WHITE, true));
        root.addView(text("MyPoupar · Samsung Transcript AI", 16, Color.rgb(130,145,205), true));
        TextView badge = text("BUILD 60.1 · GSM + TRANSCRIÇÃO + IA", 12, Color.rgb(106,235,183), true);
        badge.setPadding(0, dp(8), 0, dp(18));
        root.addView(badge);

        TextView intro = text("O Samsung Text Call ouve e fala. A SOFIA lê a transcrição, decide a resposta e envia-a automaticamente durante a chamada.", 16, Color.rgb(222,228,245), false);
        intro.setPadding(0, 0, 0, dp(18));
        root.addView(intro);

        LinearLayout statusCard = card();
        driverState = text("○ Driver Samsung", 16, Color.rgb(255,210,120), true);
        aiState = text("○ IA local", 16, Color.rgb(255,210,120), true);
        modelState = text("○ Modelo local", 14, Color.rgb(180,190,220), false);
        modeState = text("MODO · AUTO", 14, Color.rgb(106,235,183), true);
        statusCard.addView(driverState);
        statusCard.addView(aiState);
        statusCard.addView(modelState);
        statusCard.addView(modeState);
        root.addView(statusCard);

        Button call = primaryButton("ABRIR SAMSUNG / INICIAR CHAMADA");
        call.setOnClickListener(v -> openSamsungPhone());
        root.addView(call, primaryParams());

        root.addView(section("CONTROLO DA SOFIA"));
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        Button auto = smallButton("AUTO");
        Button assisted = smallButton("ASSISTIDO");
        Button manual = smallButton("MANUAL");
        auto.setOnClickListener(v -> setMode("AUTO"));
        assisted.setOnClickListener(v -> setMode("ASSISTED"));
        manual.setOnClickListener(v -> setMode("MANUAL"));
        modes.addView(auto, weight());
        modes.addView(assisted, weight());
        modes.addView(manual, weight());
        root.addView(modes);

        TextView help = text("AUTO responde e envia. ASSISTIDO prepara a resposta para aprovares. MANUAL apenas acompanha a transcrição.", 13, Color.rgb(158,169,201), false);
        help.setPadding(0, dp(8), 0, 0);
        root.addView(help);

        root.addView(section("SAMSUNG TRANSCRIPT DRIVER"));
        Button accessibility = button("Ativar / verificar acessibilidade SOFIA");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());

        root.addView(section("CÉREBRO LOCAL"));
        installBrain = button("Instalar cérebro local (~491 MB)");
        installBrain.setOnClickListener(v -> installLocalBrain());
        root.addView(installBrain, buttonParams());
        testBrain = button("Testar IA local");
        testBrain.setOnClickListener(v -> testAi());
        root.addView(testBrain, buttonParams());

        root.addView(section("DIAGNÓSTICO"));
        Button console = button("Abrir consola / transcrição ao vivo");
        console.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(console, buttonParams());

        TextView note = text("Durante o Text Call aparece o painel SOFIA LIVE por cima da chamada. O áudio continua a ser tratado pelo próprio Samsung; a SOFIA trabalha sobre a transcrição.", 13, Color.rgb(106,235,183), false);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note);
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackgroundColor(Color.rgb(17,23,43));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        c.setLayoutParams(p);
        return c;
    }

    @Override protected void onResume() { super.onResume(); refreshAll(); }

    private void refreshAll() {
        boolean enabled = isAccessibilityEnabled();
        driverState.setText(enabled ? "● DRIVER SAMSUNG · ATIVO" : "○ DRIVER SAMSUNG · DESLIGADO");
        driverState.setTextColor(enabled ? Color.rgb(106,235,183) : Color.rgb(255,210,120));
        refreshModelState();
        refreshMode();
    }

    private void refreshMode() {
        String mode = control.getString("mode", "AUTO");
        String label = "ASSISTED".equals(mode) ? "ASSISTIDO" : mode;
        modeState.setText("MODO · " + label);
    }

    private void setMode(String mode) {
        control.edit().putString("mode", mode).apply();
        refreshMode();
    }

    private void refreshModelState() {
        boolean installed = LocalQwenManager.isInstalled(this);
        if (installed) {
            modelState.setText("● " + LocalQwenManager.MODEL_LABEL + " · " + LocalQwenManager.installedSizeMb(this) + " MB");
            modelState.setTextColor(Color.rgb(106,235,183));
            if (installBrain != null) installBrain.setText("Reinstalar cérebro local");
        } else {
            modelState.setText("○ Cérebro local ainda não instalado");
            modelState.setTextColor(Color.rgb(255,210,120));
        }
    }

    private void installLocalBrain() {
        installBrain.setEnabled(false);
        testBrain.setEnabled(false);
        aiState.setText("◌ A instalar cérebro local…");
        LocalQwenManager.installAsync(this, new LocalQwenManager.DownloadCallback() {
            @Override public void onProgress(int percent, long downloadedMb, long totalMb) {
                runOnUiThread(() -> aiState.setText("↓ MODELO · " + (percent >= 0 ? percent + "%" : downloadedMb + " MB")));
            }
            @Override public void onComplete(String path) {
                runOnUiThread(() -> { installBrain.setEnabled(true); testBrain.setEnabled(true); refreshModelState(); testAi(); });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> { installBrain.setEnabled(true); testBrain.setEnabled(true); aiState.setText("○ IA · falha: " + message); aiState.setTextColor(Color.rgb(255,125,125)); });
            }
        });
    }

    private void testAi() {
        if (aiState == null) return;
        aiState.setText("◌ IA LOCAL · A TESTAR");
        aiState.setTextColor(Color.rgb(255,210,120));
        Executors.newSingleThreadExecutor().submit(() -> {
            SofiaAiHealth.Result r = SofiaAiHealth.check(this);
            runOnUiThread(() -> {
                aiState.setText(r.online ? "● IA LOCAL · ONLINE · " + r.latencyMs + " ms" : "○ IA LOCAL · OFFLINE");
                aiState.setTextColor(r.online ? Color.rgb(106,235,183) : Color.rgb(255,125,125));
            });
        });
    }

    private void openSamsungPhone() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.samsung.android.dialer");
        if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.samsung.android.incallui");
        if (i != null) startActivity(i);
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private TextView section(String s) {
        TextView v = text(s, 12, Color.rgb(140,151,190), true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.topMargin = dp(24); p.bottomMargin = dp(8); v.setLayoutParams(p); return v;
    }
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0,1.12f);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v;
    }
    private Button primaryButton(String label) {
        Button b = button(label); b.setTextSize(15); b.setTextColor(Color.rgb(5,15,17)); b.setBackgroundColor(Color.rgb(106,235,183)); return b;
    }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setGravity(Gravity.CENTER); return b; }
    private Button smallButton(String label) { Button b = button(label); b.setTextSize(11); return b; }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f); p.setMargins(dp(2),0,dp(2),0); return p; }
    private LinearLayout.LayoutParams primaryParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(62)); p.topMargin = dp(6); return p; }
    private LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54)); p.topMargin = dp(8); return p; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
