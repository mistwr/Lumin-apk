package com.lumin.app;

import android.app.role.RoleManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.Executors;

public class SetupActivity extends AppCompatActivity {
    private TextView bridge, ai, modelState, dialerState;
    private Button installBrain, testBrain;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshAll();
        testAi();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7,10,22));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(36));
        scroll.addView(root);

        root.addView(text("SOFIA", 40, Color.WHITE, true));
        root.addView(text("MyPoupar Intelligence · Build 58 Samsung Driver", 15, Color.rgb(161,173,218), false));
        TextView intro = text("SOFIA controla a conversa e usa o Samsung Text Call apenas como driver privilegiado de voz/transcrição GSM.", 16, Color.rgb(220,226,245), false);
        intro.setPadding(0, dp(18), 0, dp(8));
        root.addView(intro);

        root.addView(section("1 · SOFIA COMO TELEFONE"));
        dialerState = text("A verificar app Telefone…", 16, Color.rgb(255,210,120), true);
        root.addView(dialerState);
        Button role = button("Definir SOFIA como app Telefone");
        role.setOnClickListener(v -> requestDialerRole());
        root.addView(role, buttonParams());
        Button dialer = button("Abrir SOFIA Phone");
        dialer.setOnClickListener(v -> startActivity(new Intent(this, SofiaDialerActivity.class)));
        root.addView(dialer, buttonParams());
        Button nativeLab = button("Abrir teste de chamada / áudio nativo");
        nativeLab.setOnClickListener(v -> startActivity(new Intent(this, SofiaNativeCallActivity.class)));
        root.addView(nativeLab, buttonParams());

        root.addView(section("2 · SAMSUNG DRIVER"));
        bridge = text("A verificar…", 16, Color.rgb(106,235,183), true);
        root.addView(bridge);
        Button accessibility = button("Ativar / verificar Samsung Driver");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());
        TextView driverNote = text("Watcher contínuo 250 ms · estabilização 650 ms · fila de falas · filtro anti-repetição · bloqueio de prompt leak.", 13, Color.rgb(155,165,195), false);
        driverNote.setPadding(0, dp(10), 0, 0);
        root.addView(driverNote);

        root.addView(section("3 · CÉREBRO IA LOCAL"));
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

        root.addView(section("4 · CHAMADA / DIAGNÓSTICO"));
        Button console = button("Abrir consola SOFIA");
        console.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(console, buttonParams());
        Button phone = button("Abrir Telefone Samsung / Text Call");
        phone.setOnClickListener(v -> openSamsungPhone());
        root.addView(phone, buttonParams());

        TextView note = text("Nesta build o PCM remoto continua delegado à Samsung. A SOFIA lê apenas transcrições estabilizadas, decide a resposta e devolve texto para o TTS da Samsung.", 14, Color.rgb(106,235,183), true);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note);
        return scroll;
    }

    @Override protected void onResume() { super.onResume(); refreshAll(); }

    private void refreshAll() {
        if (bridge != null) {
            boolean enabled = isAccessibilityEnabled();
            bridge.setText(enabled ? "● Samsung Driver ATIVO" : "○ Samsung Driver DESLIGADO");
            bridge.setTextColor(enabled ? Color.rgb(106,235,183) : Color.rgb(255,210,120));
        }
        if (dialerState != null) {
            boolean held = isDefaultDialer();
            dialerState.setText(held ? "● SOFIA É A APP TELEFONE" : "○ Samsung ainda é a app Telefone");
            dialerState.setTextColor(held ? Color.rgb(106,235,183) : Color.rgb(255,210,120));
        }
        refreshModelState();
    }

    private boolean isDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = (RoleManager)getSystemService(ROLE_SERVICE);
            return rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        }
        TelecomManager tm = (TelecomManager)getSystemService(TELECOM_SERVICE);
        return tm != null && getPackageName().equals(tm.getDefaultDialerPackage());
    }

    private void requestDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = (RoleManager)getSystemService(ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 580);
                return;
            }
        }
        TelecomManager tm = (TelecomManager)getSystemService(TELECOM_SERVICE);
        if (tm != null) {
            Intent i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            i.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
            startActivity(i);
        }
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
        }
    }

    private void installLocalBrain() {
        installBrain.setEnabled(false); testBrain.setEnabled(false);
        ai.setText("◌ A descarregar cérebro local…");
        LocalQwenManager.installAsync(this, new LocalQwenManager.DownloadCallback() {
            @Override public void onProgress(int percent, long downloadedMb, long totalMb) {
                runOnUiThread(() -> ai.setText("↓ A instalar cérebro · " + (percent >= 0 ? percent + "%" : downloadedMb + " MB")));
            }
            @Override public void onComplete(String path) {
                runOnUiThread(() -> { installBrain.setEnabled(true); testBrain.setEnabled(true); refreshModelState(); testAi(); });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> { installBrain.setEnabled(true); testBrain.setEnabled(true); ai.setText("○ Falha · " + message); ai.setTextColor(Color.rgb(255,125,125)); });
            }
        });
    }

    private void testAi() {
        if (ai == null) return;
        ai.setText("◌ A testar IA…"); ai.setTextColor(Color.rgb(255,210,120));
        Executors.newSingleThreadExecutor().submit(() -> {
            SofiaAiHealth.Result r = SofiaAiHealth.check(this);
            runOnUiThread(() -> { ai.setText(r.online ? "● IA ONLINE · " + r.latencyMs + " ms · " + r.message : "○ IA OFFLINE · " + r.message); ai.setTextColor(r.online ? Color.rgb(106,235,183) : Color.rgb(255,125,125)); });
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
        TextView v = text(s, 13, Color.rgb(140,151,190), true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin = dp(24); p.bottomMargin = dp(8); v.setLayoutParams(p); return v;
    }
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0,1.12f); if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v;
    }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setTextSize(15); b.setAllCaps(false); b.setGravity(Gravity.CENTER); return b; }
    private LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56)); p.topMargin = dp(10); return p; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
