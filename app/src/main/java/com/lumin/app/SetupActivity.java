package com.lumin.app;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.Executors;

public class SetupActivity extends AppCompatActivity {
    private static final int PICK_MODEL = 4107;
    private TextView bridge;
    private TextView ai;
    private TextView sync;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshBridge();
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

        root.addView(text("REBORN AI", 40, Color.WHITE, true));
        root.addView(text("Calling Intelligence · Local Brain", 15, Color.rgb(161,173,218), false));
        TextView intro = text("Tudo local no Galaxy: Samsung Bridge + Qwen3 no próprio telemóvel. Sem OpenAI e sem servidor Ollama.", 16, Color.rgb(220,226,245), false);
        intro.setPadding(0, dp(18), 0, dp(8));
        root.addView(intro);

        root.addView(section("1 · PONTE SAMSUNG"));
        bridge = text("A verificar…", 16, Color.rgb(106,235,183), true);
        root.addView(bridge);
        Button accessibility = button("Ativar / verificar acessibilidade");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());

        root.addView(section("2 · CÉREBRO LOCAL"));
        ai = text("A verificar Qwen3…", 16, Color.rgb(255,210,120), true);
        root.addView(ai);

        Button importModel = button("Importar Qwen3 .litertlm");
        importModel.setOnClickListener(v -> chooseModel());
        root.addView(importModel, buttonParams());

        Button test = button("Testar Qwen3 local");
        test.setOnClickListener(v -> testAi());
        root.addView(test, buttonParams());

        File model = LocalRebornEngine.modelFile(this);
        TextView modelInfo = text("Destino: " + model.getAbsolutePath() + "\nModelo recomendado: Qwen3-1.7B INT4 LiteRT-LM (~1 GB). Depois de importado, a inferência é local e não tem custo por resposta.", 13, Color.rgb(155,165,195), false);
        modelInfo.setPadding(0, dp(10), 0, 0);
        root.addView(modelInfo);

        root.addView(section("3 · SD DIALER"));
        boolean configured = !BuildConfig.SUPABASE_URL.isEmpty() && !BuildConfig.SUPABASE_ANON_KEY.isEmpty() && !BuildConfig.SUPABASE_ACCESS_TOKEN.isEmpty();
        sync = text(configured ? "● SD Dialer configurado" : "○ SD Dialer a configurar", 16, configured ? Color.rgb(106,235,183) : Color.rgb(255,210,120), true);
        root.addView(sync);

        root.addView(section("4 · CHAMADA"));
        Button console = button("Abrir REBORN Calling Intelligence");
        console.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(console, buttonParams());
        Button phone = button("Abrir Telefone Samsung");
        phone.setOnClickListener(v -> openPhone());
        root.addView(phone, buttonParams());

        TextView ready = text("Quando Ponte Samsung = ATIVA e QWEN3 LOCAL = PRONTO, o modo AUTO pode responder sem usar OpenAI.", 14, Color.rgb(106,235,183), true);
        ready.setPadding(0, dp(22), 0, 0);
        root.addView(ready);
        return scroll;
    }

    private void chooseModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/*", "*/*"});
        startActivityForResult(i, PICK_MODEL);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        ai.setText("◌ A copiar Qwen3 para o REBORN…");
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                LocalRebornEngine.close();
                File dst = LocalRebornEngine.modelFile(this);
                File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new IllegalStateException("Não foi possível abrir o ficheiro");
                    byte[] buf = new byte[1024 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.getFD().sync();
                }
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) throw new IllegalStateException("Falha ao instalar o modelo");
                runOnUiThread(this::testAi);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    ai.setText("○ ERRO · " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    ai.setTextColor(Color.rgb(255,125,125));
                });
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refreshBridge();
    }

    private void refreshBridge() {
        if (bridge == null) return;
        boolean enabled = isAccessibilityEnabled();
        bridge.setText(enabled ? "● Ponte Samsung ATIVA" : "○ Ponte Samsung DESLIGADA");
        bridge.setTextColor(enabled ? Color.rgb(106,235,183) : Color.rgb(255,210,120));
    }

    private void testAi() {
        if (ai == null) return;
        ai.setText("◌ A iniciar Qwen3 local…");
        ai.setTextColor(Color.rgb(255,210,120));
        Executors.newSingleThreadExecutor().submit(() -> {
            SofiaAiHealth.Result r = SofiaAiHealth.check(this);
            runOnUiThread(() -> {
                ai.setText(r.online ? "● QWEN3 LOCAL PRONTO · " + r.latencyMs + " ms · " + r.message : "○ QWEN3 LOCAL OFF · " + r.message);
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

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
