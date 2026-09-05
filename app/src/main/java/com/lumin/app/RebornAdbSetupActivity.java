package com.lumin.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.Call;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.Executors;

/** On-device setup for Wireless ADB -> VOICE_CALL PCM + duplex calibration. */
public class RebornAdbSetupActivity extends AppCompatActivity {
    private EditText pairPort;
    private EditText pairCode;
    private EditText connectHost;
    private EditText connectPort;
    private TextView status;
    private TextView detail;
    private TextView duplexStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadSaved();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(4,8,17));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(4,8,17));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        root.addView(tv("REBORN · Captura PCM", 28, Color.WHITE, true));
        TextView desc = tv("Liga o REBORN ao Wireless Debugging do próprio Samsung. O pairing é feito uma vez; depois a chave RSA fica guardada na app.", 14, Color.rgb(175,188,215), false);
        desc.setPadding(0, dp(8), 0, dp(14));
        root.addView(desc);

        status = tv("Estado: —", 15, Color.rgb(106,235,183), true);
        status.setPadding(dp(12), dp(12), dp(12), dp(8));
        status.setBackgroundColor(Color.rgb(14,22,39));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        detail = tv("Diagnóstico: —", 12, Color.rgb(180,190,215), false);
        detail.setPadding(dp(12), 0, dp(12), dp(12));
        detail.setBackgroundColor(Color.rgb(14,22,39));
        root.addView(detail, new LinearLayout.LayoutParams(-1, -2));

        Button open = button("1 · Abrir Wireless Debugging e ver porta atual");
        open.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Throwable t) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        root.addView(open);

        root.addView(label("LIGAÇÃO ADB NORMAL · REPARAÇÃO RÁPIDA"));
        connectHost = edit("Host (ex.: 192.168.1.234)");
        connectPort = edit("2 · Porta atual do Wireless Debugging");
        connectPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(connectHost);
        root.addView(connectPort);

        Button quick = button("3 · Ligar porta atual + retomar teste completo");
        quick.setOnClickListener(v -> quickRepairAndResumeTest());
        root.addView(quick);

        TextView quickHint = tv("Durante uma chamada ativa: abre Wireless Debugging, copia apenas a porta ADB normal atual, volta aqui e toca no botão acima. O REBORN liga o shell, reinicia o PCM e volta automaticamente ao teste Digital EXP — sem novo pairing.", 12, Color.rgb(255,198,94), false);
        quickHint.setPadding(0, dp(8), 0, dp(4));
        root.addView(quickHint);

        Button connect = button("Guardar e testar ligação");
        connect.setOnClickListener(v -> doConnect());
        root.addView(connect);

        Button full = button("AUTO · ligar ADB + testar PCM em chamada");
        full.setOnClickListener(v -> autoConnectAndPcm());
        root.addView(full);

        Button pcm = button("Testar ponte VOICE_CALL PCM");
        pcm.setOnClickListener(v -> testPcm());
        root.addView(pcm);

        root.addView(label("PAIRING · SÓ SE A CHAVE FOR PERDIDA"));
        pairPort = edit("Porta de pairing (temporária)");
        pairCode = edit("Código de 6 dígitos");
        pairCode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(pairPort);
        root.addView(pairCode);
        Button pair = button("Emparelhar REBORN");
        pair.setOnClickListener(v -> doPair());
        root.addView(pair);

        root.addView(label("DUPLEX · CANAL DO CLIENTE"));
        TextView hint = tv("Em stereo o Samsung pode colocar uplink/downlink em lados diferentes. AUTO tenta separar dinamicamente; se o REBORN se ouvir a si próprio, fixa LEFT ou RIGHT durante uma chamada de teste.", 12, Color.rgb(160,174,205), false);
        root.addView(hint);
        LinearLayout channels = new LinearLayout(this);
        channels.setOrientation(LinearLayout.HORIZONTAL);
        channels.setWeightSum(3f);
        channels.addView(channelButton("AUTO", "AUTO"), weight());
        channels.addView(channelButton("LEFT", "LEFT"), weight());
        channels.addView(channelButton("RIGHT", "RIGHT"), weight());
        root.addView(channels);
        duplexStatus = tv("Duplex: —", 13, Color.rgb(255,198,94), true);
        duplexStatus.setPadding(0, dp(8), 0, 0);
        root.addView(duplexStatus);

        TextView note = tv("A porta de pairing muda ao abrir novamente o diálogo. A porta ADB normal é a que aparece no ecrã principal de Wireless Debugging. Para validar VOICE_CALL PCM tem de existir uma chamada celular ativa.", 12, Color.rgb(145,155,180), false);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void quickRepairAndResumeTest() {
        final String host = connectHost.getText().toString().trim();
        final int port = parsePort(connectPort.getText().toString());
        if (host.isEmpty() || port <= 0) {
            toast("Coloca a porta ADB normal atual do Wireless Debugging");
            return;
        }
        Call call = RebornInCallService.activeCall();
        if (call == null || call.getState() != Call.STATE_ACTIVE) {
            status.setText("Estado: falta chamada celular ATIVA");
            detail.setText("Diagnóstico: mantém a chamada ativa e volta a tocar no botão rápido");
            toast("Mantém uma chamada ativa");
            return;
        }

        status.setText("Estado: reparação rápida · a ligar ADB…");
        detail.setText("Diagnóstico: DIRECT_CONNECT " + host + ":" + port);
        getSharedPreferences("reborn_central", MODE_PRIVATE).edit()
            .putString("digital_uplink_state", "ADB_MANUAL_PORT_CONNECTING")
            .putString("digital_uplink_daemon", "MANUAL_PORT " + host + ":" + port)
            .apply();

        Executors.newSingleThreadExecutor().submit(() -> {
            EmbeddedAdbManager adb = EmbeddedAdbManager.get(this);
            try {
                adb.saveConnectEndpoint(host, port);
                boolean ok = adb.ensureConnected();
                String diag = adb.lastDiagnostic();
                if (!ok) {
                    runOnUiThread(() -> {
                        status.setText("Estado: porta atual NÃO LIGOU");
                        detail.setText("Diagnóstico: " + diag);
                        toast("Confirma a porta atual do Wireless Debugging");
                    });
                    return;
                }

                runOnUiThread(() -> {
                    connectPort.setText(String.valueOf(adb.savedConnectPort()));
                    status.setText("Estado: ADB CONNECTED ✅ · a retomar PCM + uplink…");
                    detail.setText("Diagnóstico: " + diag + " · RETOMAR_TESTE");
                    getSharedPreferences("reborn_central", MODE_PRIVATE).edit()
                        .putString("pcm_error", "")
                        .putString("digital_uplink_error", "")
                        .putString("digital_uplink_state", "ADB_CONNECTED_RESUMING")
                        .apply();
                    try {
                        RebornAudioEngine.start(this);
                    } catch (Throwable t) {
                        detail.setText("Diagnóstico: ADB OK · PCM start: " + safe(t));
                    }
                    RebornVoiceVerifier.cancel();
                    RebornVoiceController.setRoute(this, RebornVoiceController.ROUTE_DIGITAL);
                    RebornVoiceVerifier.start(this, RebornVoiceController.ROUTE_DIGITAL);
                    RebornVoiceController.speak(this, "Teste REBORN. Se me está a ouvir no outro telefone, diga agora: sim, ouvi.");
                    toast("ADB OK · PCM + Digital EXP retomados");
                    new android.os.Handler(getMainLooper()).postDelayed(this::finish, 350L);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    status.setText("Estado: erro na reparação rápida");
                    detail.setText("Diagnóstico: " + adb.lastDiagnostic() + " · " + safe(t));
                    toast("Erro ADB: " + safe(t));
                });
            }
        });
    }

    private Button channelButton(String label, String value) {
        Button b = button(label);
        b.setOnClickListener(v -> {
            getSharedPreferences("reborn_audio", MODE_PRIVATE).edit().putString("remote_channel", value).apply();
            refresh();
            toast("Canal remoto: " + value);
        });
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
        p.setMargins(dp(3), dp(6), dp(3), 0);
        return p;
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
        toast("A emparelhar…");
        Executors.newSingleThreadExecutor().submit(() -> {
            EmbeddedAdbManager adb = EmbeddedAdbManager.get(this);
            try {
                boolean ok = adb.pairLocal(port, code);
                runOnUiThread(() -> {
                    status.setText(ok ? "Estado: PAIRED ✅" : "Estado: pairing falhou");
                    detail.setText("Diagnóstico: " + adb.lastDiagnostic());
                    toast(ok ? "PAIRING OK" : "Pairing falhou");
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    status.setText("Estado: erro pairing · " + safe(t));
                    detail.setText("Diagnóstico: " + adb.lastDiagnostic());
                    toast("Erro pairing: " + safe(t));
                });
            }
        });
    }

    private void doConnect() {
        final String host = connectHost.getText().toString().trim();
        final int port = parsePort(connectPort.getText().toString());
        if (host.isEmpty() || port <= 0) { toast("Confirma host e porta ADB normal"); return; }
        status.setText("Estado: a ligar a " + host + ":" + port + " …");
        detail.setText("Diagnóstico: tentativa direta + descoberta automática");
        toast("A testar ADB…");
        Executors.newSingleThreadExecutor().submit(() -> {
            EmbeddedAdbManager adb = EmbeddedAdbManager.get(this);
            try {
                adb.saveConnectEndpoint(host, port);
                boolean ok = adb.ensureConnected();
                String diag = adb.lastDiagnostic();
                runOnUiThread(() -> {
                    status.setText(ok ? "Estado: ADB CONNECTED ✅ · " + host + ":" + port : "Estado: ADB NÃO LIGOU · " + host + ":" + port);
                    detail.setText("Diagnóstico: " + diag);
                    toast(ok ? "ADB CONNECTED" : "ADB não ligou");
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    status.setText("Estado: erro ADB · " + safe(t));
                    detail.setText("Diagnóstico: " + adb.lastDiagnostic());
                    toast("Erro ADB: " + safe(t));
                });
            }
        });
    }

    private void autoConnectAndPcm() {
        final String host = connectHost.getText().toString().trim();
        final int port = parsePort(connectPort.getText().toString());
        if (host.isEmpty() || port <= 0) { toast("Confirma host e porta ADB normal"); return; }
        Call call = RebornInCallService.activeCall();
        if (call == null || call.getState() != Call.STATE_ACTIVE) {
            status.setText("Estado: falta chamada celular ATIVA");
            detail.setText("Diagnóstico: primeiro inicia/atende uma chamada, depois volta aqui e toca AUTO");
            toast("Faz uma chamada ativa primeiro");
            return;
        }
        status.setText("Estado: AUTO · a ligar ADB…");
        detail.setText("Diagnóstico: " + host + ":" + port);
        Executors.newSingleThreadExecutor().submit(() -> {
            EmbeddedAdbManager adb = EmbeddedAdbManager.get(this);
            try {
                adb.saveConnectEndpoint(host, port);
                boolean ok = adb.ensureConnected();
                String diag = adb.lastDiagnostic();
                if (!ok) {
                    runOnUiThread(() -> {
                        status.setText("Estado: AUTO parou · ADB NÃO LIGOU");
                        detail.setText("Diagnóstico: " + diag);
                        toast("ADB não ligou");
                    });
                    return;
                }
                runOnUiThread(() -> {
                    status.setText("Estado: ADB CONNECTED ✅ · a iniciar PCM…");
                    detail.setText("Diagnóstico: " + diag);
                    startPcmProbe(8000L);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    status.setText("Estado: AUTO erro · " + safe(t));
                    detail.setText("Diagnóstico: " + adb.lastDiagnostic());
                    toast("Erro AUTO: " + safe(t));
                });
            }
        });
    }

    private void testPcm() {
        Call call = RebornInCallService.activeCall();
        if (call == null || call.getState() != Call.STATE_ACTIVE) {
            status.setText("Estado: PCM precisa de chamada celular ATIVA");
            detail.setText("Diagnóstico: sem Call.STATE_ACTIVE");
            toast("Faz uma chamada ativa primeiro");
            return;
        }
        startPcmProbe(8000L);
    }

    private void startPcmProbe(long durationMs) {
        status.setText("Estado: a testar VOICE_CALL PCM… fala dos dois lados");
        detail.setText("Diagnóstico: a aguardar frames durante " + (durationMs / 1000L) + " s");
        toast("Teste PCM iniciado");
        try {
            RebornAudioEngine.start(this);
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                String s = RebornAudioBridge.state();
                long frames = RebornAudioBridge.frames();
                android.content.SharedPreferences p = getSharedPreferences("reborn_central", MODE_PRIVATE);
                String pcm = p.getString("pcm_capture", "—");
                String err = p.getString("pcm_error", "");
                status.setText("Estado: " + s + " · frames " + frames + (frames > 0 ? " ✅" : " ❌"));
                detail.setText("Diagnóstico: PCM " + pcm + (err == null || err.isEmpty() ? "" : " · " + err));
                toast(frames > 0 ? "PCM ACTIVE · frames " + frames : "PCM sem frames · " + s);
                refreshDuplex();
                RebornAudioEngine.stop();
            }, durationMs);
        } catch (Throwable t) {
            status.setText("Estado: erro PCM · " + safe(t));
            detail.setText("Diagnóstico: " + safe(t));
            toast("Erro PCM: " + safe(t));
        }
    }

    private void refresh() {
        String pcm = getSharedPreferences("reborn_central", MODE_PRIVATE).getString("pcm_capture", "IDLE");
        status.setText("Estado: " + pcm);
        detail.setText("Diagnóstico: " + EmbeddedAdbManager.get(this).lastDiagnostic());
        refreshDuplex();
    }

    private void refreshDuplex() {
        if (duplexStatus == null) return;
        String pref = getSharedPreferences("reborn_audio", MODE_PRIVATE).getString("remote_channel", "AUTO");
        android.content.SharedPreferences p = getSharedPreferences("reborn_central", MODE_PRIVATE);
        int l = p.getInt("pcm_left_level", 0);
        int r = p.getInt("pcm_right_level", 0);
        String selected = p.getString("pcm_selected", "—");
        String stt = p.getString("stt_input", "—");
        duplexStatus.setText("Duplex: " + pref + " · usado " + selected + " · L " + l + " / R " + r + " · STT " + stt);
    }

    private int parsePort(String s) {
        try { int p = Integer.parseInt(s.trim()); return p >= 1 && p <= 65535 ? p : -1; }
        catch (Exception e) { return -1; }
    }

    private String safe(Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

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
