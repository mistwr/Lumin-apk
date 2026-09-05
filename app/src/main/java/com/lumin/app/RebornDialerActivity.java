package com.lumin.app;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.ArrayList;
import java.util.List;

public class RebornDialerActivity extends AppCompatActivity {
    private static final int REQ_DIALER = 904;
    private static final int REQ_PERMS = 905;
    private EditText number;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        readIntentNumber(getIntent());
        requestRuntimePermissions();
        requestDialerRoleIfNeeded();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntentNumber(intent);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(42), dp(24), dp(28));
        root.setBackgroundColor(Color.rgb(4,8,17));

        TextView title = new TextView(this);
        title.setText("REBORN PHONE");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Telefone nativo · IA · transcrição · gravação · CRM");
        sub.setTextSize(15);
        sub.setTextColor(Color.rgb(150,165,195));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, dp(24));
        root.addView(sub);

        number = new EditText(this);
        number.setHint("Número de telefone");
        number.setTextSize(26);
        number.setTextColor(Color.WHITE);
        number.setHintTextColor(Color.rgb(120,130,155));
        number.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        root.addView(number, new LinearLayout.LayoutParams(-1, dp(70)));

        Button call = new Button(this);
        call.setText("Ligar com REBORN");
        call.setAllCaps(false);
        call.setTextSize(18);
        call.setOnClickListener(v -> placeCall());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(64));
        cp.topMargin = dp(16);
        root.addView(call, cp);

        Button role = new Button(this);
        role.setText("Definir REBORN como app Telefone");
        role.setAllCaps(false);
        role.setOnClickListener(v -> requestDialerRole());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(60));
        rp.topMargin = dp(12);
        root.addView(role, rp);

        Button pcm = new Button(this);
        pcm.setText("Configurar captura de áudio PCM");
        pcm.setAllCaps(false);
        pcm.setOnClickListener(v -> startActivity(new Intent(this, RebornAdbSetupActivity.class)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(60));
        pp.topMargin = dp(10);
        root.addView(pcm, pp);

        TextView info = new TextView(this);
        info.setText("Quando o REBORN é o telefone predefinido, as chamadas GSM entram no RebornInCallService. Em chamada ativa arrancam automaticamente Qwen3, STT, gravação PCM e resumo CRM.");
        info.setTextSize(14);
        info.setTextColor(Color.rgb(150,165,195));
        info.setPadding(0, dp(22), 0, 0);
        root.addView(info);

        setContentView(root);
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        String[] wanted = new String[] {
                Manifest.permission.CALL_PHONE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS
        };
        List<String> missing = new ArrayList<>();
        for (String p : wanted) if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMS);
    }

    private void readIntentNumber(Intent i) {
        if (i == null || number == null) return;
        Uri data = i.getData();
        if (data != null && "tel".equals(data.getScheme())) number.setText(data.getSchemeSpecificPart());
    }

    private void requestDialerRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < 29) return;
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) requestDialerRole();
    }

    private void requestDialerRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_DIALER);
                return;
            }
        }
        startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
    }

    private void placeCall() {
        String n = number.getText() == null ? "" : number.getText().toString().trim();
        if (n.isEmpty()) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions();
            return;
        }
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) {
                tm.placeCall(Uri.fromParts("tel", n, null), new Bundle());
                return;
            }
        } catch (SecurityException ignored) {}
        startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null)));
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
