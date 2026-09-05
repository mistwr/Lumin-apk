package com.lumin.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.Executors;

/** Simple on-device setup for the proven Wireless ADB -> VOICE_CALL PCM bridge. */
public class RebornAdbSetupActivity extends AppCompatActivity {
    private EditText pairPort;
    private EditText pairCode;
    private EditText connectHost;
    private EditText connectPort;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadSaved();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(4,8,17));

        TextView title = tv("REBORN · Captura PCM", 28, Color.WHITE, true);
        root.addView(title);
        TextView desc = tv("Liga o REBORN ao Wireless Debugging do próprio Samsung. O pairing é feito uma vez; depois a chave RSA fica guardada na app.", 14, Color.rgb(175,188,215), false);
        desc.setPadding(0, dp(8), 0, dp(14));
        root.addView(desc);

        Button open = button("Abrir Opções de programador / Wireless Debugging");
        open.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Throwable t) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        root.addView(open);

        root.addView(label("PAIRING"));
        pairPort = edit("Porta de pairing (temporária)");
        pairCode = edit("Código de 6 dígitos");
        pairCode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(pairPort);
        root.addView(pairCode);
        Button pair = button("Emparelhar REBORN");
        pair.setOnClickListener(v -> doPair());
        root.addView(pair);

        root.addView(label("LIGAÇÃO ADB NORMAL"));
        connectHost = edit("Host (ex.: 127.0.0.1 ou IP do telefone)");
        connectPort = edit("Porta do Wireless Debugging");
        root.addView(connectHost);
        root.addView(connectPort);
        Button connect = button("Guardar e testar ligação");
        connect.setOnClickListener(v -> doConnect());
        root.addView(connect);

        Button pcm = button("Testar ponte VOICE_CALL PCM");
        pcm.setOnClickListener(v -> testPcm());
        root.addView(pcm);

        status = tv("Estado: —", 14, Color.rgb(106,235,183), true);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status);

        TextView note = tv("Nota: a porta de pairing muda quando abres novamente o diálogo. A porta ADB normal é a que aparece no ecrã principal de Wireless Debugging.", 12, Color.rgb(145,155,180), false);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        setContentView(root);
    }

    private void loadSaved() {
        EmbeddedAdbManager adb = EmbeddedAdbManager.get(this);
        connectHost.setText(adb.savedConnectHost());
        int port = adb.savedConnectPort();
        if (port > 0) connectPort.setText(String.valueOf(port));
    }

    private void doPair() {
        final int port = parsePort(pairPort.getText().toString());
        final String code = pairCode.getText().toString().trim();
        if (port <= 0 || code.length() != 6) { toast("Confirma porta e código de 6 dígitos"); return; }
        status.setText("Estado: a emparelhar…");
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                boolean ok = EmbeddedAdbManager.get(this).pairLocal(port, code);
                runOnUiThread(() -> status.setText(ok ? "Estado: PAIRED ✅" : "Estado: pairing falhou"));
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Estado: erro pairing · " + safe(t)));
            }
        });
    }

    private void doConnect() {
        final String host = connectHost.getText().toString().trim();
        final int port = parsePort(connectPort.getText().toString());
        if (host.isEmpty() || port <= 0) { toast("Confirma host e porta ADB normal"); return; }
        status.setText("Estado: a ligar…");
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                EmbeddedAdbManager adb = EmbeddedAdbManager.get(this);
                adb.saveConnectEndpoint(host, port);
                boolean ok = adb.ensureConnected();
                runOnUiThread(() -> status.setText(ok ? "Estado: ADB CONNECTED ✅" : "Estado: ADB não ligou"));
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Estado: erro ADB · " + safe(t)));
            }
        });
    }

    private void testPcm() {
        status.setText("Estado: a testar PCM… faz uma chamada ativa para validar VOICE_CALL");
        try {
            RebornAudioEngine.start(this);
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                String s = RebornAudioBridge.state();
                long frames = RebornAudioBridge.frames();
                status.setText("Estado: " + s + " · frames " + frames + (frames > 0 ? " ✅" : ""));
                RebornAudioEngine.stop();
            }, 5000L);
        } catch (Throwable t) {
            status.setText("Estado: erro PCM · " + safe(t));
        }
    }

    private void refresh() {
        String pcm = getSharedPreferences("reborn_central", MODE_PRIVATE).getString("pcm_capture", "IDLE");
        status.setText("Estado: " + pcm);
    }

    private int parsePort(String s) {
        try { int p = Integer.parseInt(s.trim()); return p >= 1 && p <= 65535 ? p : -1; }
        catch (Exception e) { return -1; }
    }

    private String safe(Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private TextView label(String s) {
        TextView v = tv(s, 12, Color.rgb(148,160,194), true);
        v.setPadding(0, dp(18), 0, dp(6));
        return v;
    }
    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(120,132,160));
        e.setTextColor(Color.WHITE);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }
    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        return b;
    }
    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return v;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
