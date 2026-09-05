package com.lumin.app;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class SofiaDialerActivity extends AppCompatActivity {
    private EditText number;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(36), dp(24), dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("SOFIA PHONE");
        title.setTextSize(34);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Build 57 · Native Dialer Lab");
        sub.setTextSize(16);
        root.addView(sub);

        number = new EditText(this);
        number.setHint("Número de telefone");
        number.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        Uri data = getIntent() == null ? null : getIntent().getData();
        if (data != null && data.getSchemeSpecificPart() != null) number.setText(data.getSchemeSpecificPart());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = dp(32);
        root.addView(number, ep);

        Button call = new Button(this);
        call.setText("Ligar com SOFIA");
        call.setAllCaps(false);
        call.setOnClickListener(v -> placeCall());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(58));
        bp.topMargin = dp(16);
        root.addView(call, bp);

        Button role = new Button(this);
        role.setText("Definir SOFIA como app Telefone");
        role.setAllCaps(false);
        role.setOnClickListener(v -> requestDialerRole());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(58));
        rp.topMargin = dp(10);
        root.addView(role, rp);

        setContentView(root);
    }

    private void requestDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 701);
                return;
            }
        }
        TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
        if (tm != null) {
            Intent i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            i.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
            startActivity(i);
        }
    }

    private void placeCall() {
        String n = number.getText().toString().trim();
        if (n.isEmpty()) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.RECORD_AUDIO}, 702);
            return;
        }
        TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
        if (tm != null) tm.placeCall(Uri.parse("tel:" + Uri.encode(n)), new Bundle());
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
