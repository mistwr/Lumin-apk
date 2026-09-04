package com.lumin.app;

import android.app.role.RoleManager;
import android.content.Intent;
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

public class RebornDialerActivity extends AppCompatActivity {
    private static final int REQ_DIALER = 904;
    private EditText number;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        readIntentNumber(getIntent());
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
        sub.setText("Telefone nativo · IA · chamadas · histórico");
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

        TextView info = new TextView(this);
        info.setText("Depois de ser a app Telefone predefinida, as chamadas GSM entram no RebornInCallService e usam o ecrã REBORN.");
        info.setTextSize(14);
        info.setTextColor(Color.rgb(150,165,195));
        info.setPadding(0, dp(22), 0, 0);
        root.addView(info);

        setContentView(root);
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
        Intent i = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
        startActivity(i);
    }

    private void placeCall() {
        String n = number.getText() == null ? "" : number.getText().toString().trim();
        if (n.isEmpty()) return;
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) {
                tm.placeCall(Uri.fromParts("tel", n, null), new Bundle());
                return;
            }
        } catch (SecurityException ignored) {}
        Intent i = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null));
        startActivity(i);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
