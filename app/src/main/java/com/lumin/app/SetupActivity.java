package com.lumin.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.Executors;

public class SetupActivity extends AppCompatActivity {
    private TextView bridge;
    private TextView ai;
    private TextView modelState;
    private TextView sync;
    private EditText endpoint;
    private Button installBrain;
    private Button testBrain;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshBridge();
        refreshModelState();
        testAi();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 10, 22));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(36));
        scroll.addView(root);

        root.addView(text("SOFIA", 40, Color.WHITE, true));
        root.addView(text("MyPoupar Intelligence · Build 56.1 Local Brain", 15, Color.rgb(161,173,218), false));
        TextView intro = text("Tudo numa página: ponte Samsung, cérebro IA local, atalho, chamada e SD Dialer.", 16, Color.rgb(220,226,245), false);
        intro.setPadding(0, dp(18), 0, dp(8));
        root.addView(intro);

        root.addView(section("1 · PONTE SAMSUNG"));
        bridge = text("A verificar…", 16, Color.rgb(106,235,183), true);
        root.addView(bridge);
        Button accessibility = button("Ativar / verificar acessibilidade");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());

        root.addView(section("2 · CÉREBRO IA LOCAL"));
        modelState = text("A verificar modelo…", 15, Color.rgb(255,210,120), true);
        root.addView(modelState);
        ai = text("A testar IA…", 16, Color.rgb(255,210,120), true);
        ai.setPadding(0, dp(8), 0, 0);
        root.addView(ai);

        installBrain = button("Instalar cérebro SOFIA (~491 MB)");
        installBrain.setOnClickListener(v -> installLocalBrain());
        root.addView(installBrain, buttonParams());

        testBrain = button("Testar IA local");
        testBrain.setOnClickListener(v -> testAi());
        root.addView(testBrain, buttonParams());

        TextView localNote = text("O modelo Qwen2.5 0.5B Q4_K_M é descarregado uma única vez para o armazenamento privado da SOFIA e executado no próprio telemóvel através de llama.cpp. Depois deixa de ser necessário ter Ollama/127.0.0.1 ativo.", 13, Color.rgb(155,165,195), false);
        localNote.setPadding(0, dp(10), 0, dp(8));
        root.addView(localNote);

        TextView fallback = section("ENDPOINT REMOTO · OPCIONAL");
        root.addView(fallback);
        endpoint = new EditText(this);
        endpoint.setText(SofiaAiHealth.endpoint(this));
        endpoint.setTextColor(Color.WHITE);
        endpoint.setHintTextColor(Color.rgb(130,139,168));
        endpoint.setHint("http://127.0.0.1:11434/api/generate");
        endpoint.setSingleLine(true);
        endpoint.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(endpoint);

        LinearLayout aiButtons = new LinearLayout(this);
        aiButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button save = button("Guardar endpoint");
        Button remoteTest = button("Testar fallback");
        save.setOnClickListener(v -> {
            SofiaAiHealth.saveEndpoint(this, endpoint.getText().toString());
            testAi();
        });
        remoteTest.setOnClickListener(v -> testAi());
        aiButtons.addView(save, weight());
        aiButtons.addView(remoteTest, weight());
        root.addView(aiButtons);

        root.addView(section("3 · SD DIALER"));
        boolean configured = !BuildConfig.SUPABASE_URL.isEmpty() && !BuildConfig.SUPABASE_ANON_KEY.isEmpty() && !BuildConfig.SUPABASE_ACCESS_TOKEN.isEmpty();
        sync = text(configured ? "● SD Dialer configurado" : "○ SD Dialer a configurar", 16, configured ? Color.rgb(106,235,183) : Color.rgb(255,210,120), true);
        root.addView(sync);

        root.addView(section("4 · CHAMADA"));
        Button console = button("Abrir consola SOFIA");
        console.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(console, buttonParams());
        Button phone = button("Abrir Telefone Samsung");
        phone.setOnClickListener(v -> openPhone());
        root.addView(phone, buttonParams());

        TextView ready = text("Quando Ponte Samsung = ATIVA e IA LOCAL = ONLINE, a SOFIA está pronta para conversar em AUTO.", 14, Color.rgb(106,235,183), true);
        ready.setPadding(0, dp(22), 0, 0);
        root.addView(ready);
        return scroll;
    }

    @Override protected void onResume() {
        super.onResume();
        refreshBridge();
        refreshModelState();
    }

    private void refreshBridge() {
        if (bridge == null) return;
        boolean enabled = isAccessibilityEnabled();
        bridge.setText(enabled ? "● Ponte Samsung ATIVA" : "○ Ponte Samsung DESLIGADA");
        bridge.setTextColor(enabled ? Color.rgb(106,235,183) : Color.rgb(255,210,120));
    }

    private void refreshModelState() {
        if (modelState == null) return;
        boolean installed = LocalQwenManager.isInstalled(this);
        if (installed) {
            modelState.setText("● Cérebro instalado · " + LocalQwenManager.MODEL_LABEL + " · " + LocalQwenManager.installedSizeMb(this) + " MB");
            modelState.setTextColor(Color.rgb(106,235,183));
            if (installBrain != null) installBrain.setText("Reinstalar cérebro local");
        } else {
            modelState.setText("○ Cérebro local ainda não instalado");
            modelState.setTextColor(Color.rgb(255,210,120));
            if (installBrain != null) installBrain.setText("Instalar cérebro SOFIA (~491 MB)");
        }
    }

    private void installLocalBrain() {
        installBrain.setEnabled(false);
        testBrain.setEnabled(false);
        ai.setText("◌ A descarregar cérebro local…");
        ai.setTextColor(Color.rgb(255,210,120));
        LocalQwenManager.installAsync(this, new LocalQwenManager.DownloadCallback() {
            @Override public void onProgress(int percent, long downloadedMb, long totalMb) {
                runOnUiThread(() -> {
                    String p = percent >= 0 ? percent + "%" : downloadedMb + " MB";
                    String total = totalMb > 0 ? " · " + downloadedMb + "/" + totalMb + " MB" : " · " + downloadedMb + " MB";
                    ai.setText("↓ A instalar cérebro · " + p + total);
                });
            }

            @Override public void onComplete(String path) {
                runOnUiThread(() -> {
                    installBrain.setEnabled(true);
                    testBrain.setEnabled(true);
                    refreshModelState();
                    ai.setText("✓ Modelo descarregado · a carregar IA…");
                    testAi();
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    installBrain.setEnabled(true);
                    testBrain.setEnabled(true);
                    ai.setText("○ Falha ao instalar · " + message);
                    ai.setTextColor(Color.rgb(255,125,125));
                });
            }
        });
    }

    private void testAi() {
        if (ai == null) return;
        ai.setText("◌ A testar IA…");
        ai.setTextColor(Color.rgb(255,210,120));
        Executors.newSingleThreadExecutor().submit(() -> {
            SofiaAiHealth.Result r = SofiaAiHealth.check(this);
            runOnUiThread(() -> {
                ai.setText(r.online ? "● IA ONLINE · " + r.latencyMs + " ms · " + r.message : "○ IA OFFLINE · " + r.message);
                ai.setTextColor(r.online ? Color.rgb(106,235,183) : Color.rgb(255,125,125));
            });
        });
    }

    private void openPhone() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.samsung.android.dialer");
        if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.samsung.android.incallui");
        if (i != null) startActivity(i);
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private TextView section(String s) {
        TextView v = text(s, 13, Color.rgb(140,151,190), true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.topMargin = dp(24);
        p.bottomMargin = dp(8);
        v.setLayoutParams(p);
        return v;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0,1.12f);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(15); b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56)); p.topMargin = dp(10); return p;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1f);
        p.setMargins(dp(2), dp(10), dp(2), 0);
        return p;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
