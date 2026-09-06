package com.lumin.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * REBORN Voice Gateway controller.
 *
 * The APK never stores Twilio/Asterisk admin credentials. It only talks to the
 * REBORN gateway over HTTPS using a scoped bearer token. The gateway then asks
 * Asterisk ARI to originate the Twilio SIP call and hand the answered channel to
 * the VoiceBridge/Stasis app.
 */
public class RebornVoiceGatewayActivity extends Activity {
    private static final String PREFS = "reborn_voice_gateway";
    private EditText gatewayUrl;
    private EditText gatewayToken;
    private EditText phone;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(30));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("REBORN Voice Gateway");
        title.setTextSize(26f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, full());

        TextView hint = new TextView(this);
        hint.setText("Twilio → Asterisk → VoiceBridge → Sofia/Qwen\nA APK apenas controla a chamada; as credenciais ficam no gateway.");
        hint.setTextSize(15f);
        hint.setPadding(0, dp(10), 0, dp(18));
        root.addView(hint, full());

        gatewayUrl = field("https://gateway.exemplo.pt", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        gatewayUrl.setText(prefs.getString("url", ""));
        root.addView(label("Gateway HTTPS"));
        root.addView(gatewayUrl, full());

        gatewayToken = field("Token do gateway", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        gatewayToken.setText(prefs.getString("token", ""));
        root.addView(label("Token"));
        root.addView(gatewayToken, full());

        phone = field("Ex.: 912345678 ou +351912345678", InputType.TYPE_CLASS_PHONE);
        root.addView(label("Número a ligar"));
        root.addView(phone, full());

        Button save = new Button(this);
        save.setText("Guardar gateway");
        save.setOnClickListener(v -> {
            prefs.edit().putString("url", cleanBase(gatewayUrl.getText().toString()))
                    .putString("token", gatewayToken.getText().toString().trim()).apply();
            status.setText("Gateway guardado.");
        });
        root.addView(save, full());

        Button health = new Button(this);
        health.setText("Testar sistema");
        health.setOnClickListener(v -> runRequest("GET", "/status", null));
        root.addView(health, full());

        Button call = new Button(this);
        call.setText("LIGAR COM SOFIA IA");
        call.setTextSize(18f);
        call.setOnClickListener(v -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("to", phone.getText().toString().trim());
                runRequest("POST", "/calls", payload.toString());
            } catch (Exception e) {
                status.setText("Erro: " + e.getMessage());
            }
        });
        root.addView(call, full());

        status = new TextView(this);
        status.setText("Pronto.");
        status.setTextSize(14f);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status, full());

        setContentView(scroll);
    }

    private void runRequest(String method, String path, String body) {
        final String base = cleanBase(gatewayUrl.getText().toString());
        final String token = gatewayToken.getText().toString().trim();
        if (base.isEmpty()) { status.setText("Define primeiro o endereço HTTPS do gateway."); return; }
        status.setText("A comunicar com REBORN...");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + path).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(7000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/json");
                if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = c.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String out = "HTTP " + code + "\n" + sb;
                runOnUiThread(() -> status.setText(out));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Falha no gateway: " + e.getMessage()));
            } finally {
                if (c != null) c.disconnect();
            }
        }, "reborn-gateway-request").start();
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13f);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private String cleanBase(String s) {
        s = s == null ? "" : s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
