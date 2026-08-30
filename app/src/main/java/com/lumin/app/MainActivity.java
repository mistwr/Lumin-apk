package com.lumin.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

public class MainActivity extends AppCompatActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView bridgeStatus;
    private TextView modeStatus;
    private TextView customerView;
    private TextView suggestionView;
    private TextView transcriptView;
    private EditText replyBox;
    private SharedPreferences control;
    private boolean diagnosticsVisible = false;

    private final Runnable liveRefresh = new Runnable() {
        @Override public void run() {
            refreshLive();
            ui.postDelayed(this, 450);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (!control.contains("mode")) control.edit().putString("mode", "AUTO").apply();
        setContentView(buildUi());
        Executors.newSingleThreadExecutor().submit(() -> SupabaseSyncClient.flush(this));
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(liveRefresh);
        ui.post(liveRefresh);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(liveRefresh);
        super.onPause();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 10, 22));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(34));
        scroll.addView(root);

        root.addView(text("SOFIA", 38, Color.WHITE, true));
        root.addView(text("MyPoupar Intelligence · Build 54 Call Console", 15, Color.rgb(161, 173, 218), false));

        bridgeStatus = text("A verificar ponte Samsung…", 15, Color.rgb(106, 235, 183), true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.topMargin = dp(20); bridgeStatus.setLayoutParams(bp);
        root.addView(bridgeStatus);

        TextView callTitle = text("CHAMADA AO VIVO", 13, Color.rgb(140, 151, 190), true);
        LinearLayout.LayoutParams ctp = new LinearLayout.LayoutParams(-1, -2); ctp.topMargin = dp(24); callTitle.setLayoutParams(ctp);
        root.addView(callTitle);

        customerView = text("À espera do cliente…", 22, Color.WHITE, true);
        LinearLayout.LayoutParams cvp = new LinearLayout.LayoutParams(-1, -2); cvp.topMargin = dp(8); customerView.setLayoutParams(cvp);
        root.addView(customerView);

        modeStatus = text("Modo AUTO", 14, Color.rgb(106, 235, 183), true);
        LinearLayout.LayoutParams msp = new LinearLayout.LayoutParams(-1, -2); msp.topMargin = dp(18); modeStatus.setLayoutParams(msp);
        root.addView(modeStatus);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setWeightSum(3f);
        modes.addView(modeButton("AUTO", "AUTO"), weightedButtonParams());
        modes.addView(modeButton("ASSISTIDO", "ASSISTED"), weightedButtonParams());
        modes.addView(modeButton("MANUAL", "MANUAL"), weightedButtonParams());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(54)); mp.topMargin = dp(8); modes.setLayoutParams(mp);
        root.addView(modes);

        TextView sugTitle = text("RESPOSTA SOFIA", 13, Color.rgb(140, 151, 190), true);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2); stp.topMargin = dp(22); sugTitle.setLayoutParams(stp);
        root.addView(sugTitle);

        suggestionView = text("A resposta aparece aqui.", 18, Color.rgb(220, 226, 245), false);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(-1, -2); svp.topMargin = dp(7); suggestionView.setLayoutParams(svp);
        root.addView(suggestionView);

        replyBox = new EditText(this);
        replyBox.setTextColor(Color.WHITE);
        replyBox.setHintTextColor(Color.rgb(130, 139, 168));
        replyBox.setHint("Editar ou escrever resposta…");
        replyBox.setTextSize(17);
        replyBox.setMinLines(2);
        replyBox.setMaxLines(4);
        replyBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(-1, -2); rbp.topMargin = dp(12); replyBox.setLayoutParams(rbp);
        root.addView(replyBox);

        Button send = button("Enviar agora pelo Samsung");
        send.setOnClickListener(v -> sendManualReply());
        root.addView(send, buttonParams());

        Button phone = button("Abrir Telefone Samsung");
        phone.setOnClickListener(v -> openPhone());
        root.addView(phone, buttonParams());

        TextView trTitle = text("TRANSCRIÇÃO", 13, Color.rgb(140, 151, 190), true);
        LinearLayout.LayoutParams ttp = new LinearLayout.LayoutParams(-1, -2); ttp.topMargin = dp(24); trTitle.setLayoutParams(ttp);
        root.addView(trTitle);

        transcriptView = text("Sem mensagens ainda.", 15, Color.rgb(204, 211, 235), false);
        LinearLayout.LayoutParams tvp = new LinearLayout.LayoutParams(-1, -2); tvp.topMargin = dp(8); transcriptView.setLayoutParams(tvp);
        root.addView(transcriptView);

        Button accessibility = button("Ativar / verificar ponte Samsung");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());

        Button dev = button("Developer / Diagnóstico");
        dev.setOnClickListener(v -> { diagnosticsVisible = !diagnosticsVisible; refreshLive(); });
        root.addView(dev, buttonParams());

        TextView footer = text("AUTO responde sozinho. ASSISTIDO prepara a resposta e espera por ti. MANUAL apenas transcreve e memoriza.", 13, Color.rgb(139, 149, 181), false);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2); fp.topMargin = dp(20); footer.setLayoutParams(fp);
        root.addView(footer);
        return scroll;
    }

    private Button modeButton(String label, String value) {
        Button b = button(label);
        b.setTextSize(12);
        b.setOnClickListener(v -> {
            control.edit().putString("mode", value).apply();
            refreshLive();
        });
        return b;
    }

    private void refreshLive() {
        if (bridgeStatus == null) return;
        SharedPreferences d = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        String mode = control.getString("mode", "AUTO");
        String modeLabel = "ASSISTED".equals(mode) ? "ASSISTIDO" : mode;
        modeStatus.setText("Modo " + modeLabel);

        String customer = control.getString("live_customer", "");
        customerView.setText(customer.isEmpty() ? "À espera do cliente…" : "Cliente\n“" + customer + "”");

        String suggested = control.getString("suggested_reply", "");
        suggestionView.setText(suggested.isEmpty() ? "A resposta aparece aqui." : "“" + suggested + "”");
        if (!suggested.isEmpty() && !replyBox.hasFocus()) replyBox.setText(suggested);

        String transcript = control.getString("live_transcript", "");
        transcriptView.setText(transcript.isEmpty() ? "Sem mensagens ainda." : transcript);

        if (!diagnosticsVisible) {
            bridgeStatus.setText(isAccessibilityEnabled() ? "● Ponte Samsung ativa · pronta para Text Call" : "○ Ponte Samsung desligada");
        } else {
            boolean configured = !BuildConfig.SUPABASE_URL.isEmpty() && !BuildConfig.SUPABASE_ANON_KEY.isEmpty() && !BuildConfig.SUPABASE_ACCESS_TOKEN.isEmpty();
            String report = "Samsung Bridge: " + (isAccessibilityEnabled() ? "ATIVO" : "INATIVO") +
                    "\nSurface: " + d.getString("surface", "—") +
                    "\nÚltimo cliente: " + d.getString("last_customer", "—") +
                    "\nCaminho: " + d.getString("path", "—") +
                    "\nQwen: " + d.getString("qwen", "—") +
                    "\nSET_TEXT: " + d.getString("set_text", "—") +
                    "\nSEND: " + d.getString("send", "—") +
                    "\nErro: " + d.getString("last_error", "—") +
                    "\nSD Dialer Sync: " + (configured ? "CONFIGURADO" : "A CONFIGURAR");
            bridgeStatus.setText(report);
        }
    }

    private void sendManualReply() {
        String reply = replyBox.getText().toString().trim();
        if (reply.isEmpty()) {
            reply = control.getString("suggested_reply", "").trim();
            if (reply.isEmpty()) return;
        }
        Intent i = new Intent(SofiaAccessibilityService.ACTION_SEND_REPLY);
        i.setPackage(getPackageName());
        i.putExtra(SofiaAccessibilityService.EXTRA_REPLY, reply);
        sendBroadcast(i);
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

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0, 1.12f);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(16); b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56)); p.topMargin = dp(12); return p;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
