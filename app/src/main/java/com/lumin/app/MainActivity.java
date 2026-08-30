package com.lumin.app;

import android.content.Intent;
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

public class MainActivity extends AppCompatActivity {
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        Executors.newSingleThreadExecutor().submit(() -> SupabaseSyncClient.flush(this));
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(7, 10, 22));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(34), dp(24), dp(28));
        scroll.addView(root);

        TextView brand = text("SOFIA", 42, Color.WHITE, true);
        root.addView(brand);
        TextView sub = text("MyPoupar Intelligence · Build 53", 17, Color.rgb(161, 173, 218), false);
        root.addView(sub);

        TextView hero = text("A consultora de IA que conversa, qualifica e prepara vendas por ti.", 24, Color.WHITE, true);
        LinearLayout.LayoutParams heroP = new LinearLayout.LayoutParams(-1, -2); heroP.topMargin = dp(30); hero.setLayoutParams(heroP);
        root.addView(hero);

        status = text("A verificar ligação…", 18, Color.rgb(106, 235, 183), true);
        LinearLayout.LayoutParams statusP = new LinearLayout.LayoutParams(-1, -2); statusP.topMargin = dp(28); status.setLayoutParams(statusP);
        root.addView(status);

        TextView info = text("Fast Path ativo · memória anti-repetição · Qwen local · sincronização SD Dialer", 15, Color.rgb(205, 211, 234), false);
        LinearLayout.LayoutParams infoP = new LinearLayout.LayoutParams(-1, -2); infoP.topMargin = dp(12); info.setLayoutParams(infoP);
        root.addView(info);

        Button accessibility = button("Ativar ponte Samsung");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());

        Button phone = button("Abrir Telefone Samsung");
        phone.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.samsung.android.dialer");
            if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.samsung.android.incallui");
            if (i != null) startActivity(i);
        });
        root.addView(phone, buttonParams());

        Button dev = button("Developer / Diagnóstico");
        dev.setOnClickListener(v -> {
            boolean configured = !BuildConfig.SUPABASE_URL.isEmpty() && !BuildConfig.SUPABASE_ANON_KEY.isEmpty() && !BuildConfig.SUPABASE_ACCESS_TOKEN.isEmpty();
            status.setText("Samsung Bridge: " + (isAccessibilityEnabled() ? "ATIVO" : "INATIVO") + "\nQwen: " + BuildConfig.QWEN_ENDPOINT + "\nSD Dialer Sync: " + (configured ? "CONFIGURADO" : "A CONFIGURAR"));
        });
        root.addView(dev, buttonParams());

        TextView footer = text("A tecnologia fica escondida durante a utilização normal. O cliente vê apenas a conversa; o diagnóstico fica aqui.", 14, Color.rgb(142, 151, 183), false);
        LinearLayout.LayoutParams footP = new LinearLayout.LayoutParams(-1, -2); footP.topMargin = dp(28); footer.setLayoutParams(footP);
        root.addView(footer);
        return scroll;
    }

    private void refreshStatus() {
        if (status == null) return;
        status.setText(isAccessibilityEnabled() ? "● SOFIA AUTOMÁTICA ATIVA\n✓ Ponte Samsung pronta" : "○ SOFIA pronta\nAtiva a ponte Samsung para começar");
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0, 1.12f);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setTextSize(17); b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58)); p.topMargin = dp(16); return p;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
