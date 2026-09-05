package com.lumin.app;

import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;

public class RebornSamsungCapabilityActivity extends AppCompatActivity {
    private TextView status;
    private TextView output;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        runLocalScan();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(4,8,17));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        root.addView(tv("REBORN · Samsung Capability Map", 25, Color.WHITE, true));
        TextView desc = tv("Scanner só de leitura. Mapeia permissões, classes Samsung/Knox, serviços de sistema, dispositivos de áudio e APIs de chamada disponíveis à app e ao UID shell.", 13, Color.rgb(175,188,215), false);
        desc.setPadding(0, dp(8), 0, dp(14));
        root.addView(desc);

        status = tv("Estado: LOCAL", 14, Color.rgb(106,235,183), true);
        root.addView(status);

        Button shell = new Button(this);
        shell.setAllCaps(false);
        shell.setText("MAPEAR TUDO VIA ADB / SHELL");
        shell.setGravity(Gravity.CENTER);
        shell.setOnClickListener(v -> runShellScan());
        root.addView(shell, new LinearLayout.LayoutParams(-1, dp(58)));

        output = tv("", 12, Color.rgb(225,230,242), false);
        output.setPadding(dp(12), dp(12), dp(12), dp(12));
        output.setBackgroundColor(Color.rgb(14,22,39));
        root.addView(output, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void runLocalScan() {
        StringBuilder s = new StringBuilder();
        s.append("=== APP UID ===\n");
        s.append("uid=").append(Process.myUid()).append(" package=").append(getPackageName()).append('\n');
        String[] perms = {
            "android.permission.CALL_AUDIO_INTERCEPTION",
            "android.permission.MODIFY_AUDIO_ROUTING",
            "android.permission.MODIFY_PHONE_STATE",
            "android.permission.CAPTURE_AUDIO_OUTPUT",
            "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT",
            "android.permission.RECORD_AUDIO",
            "com.samsung.android.knox.permission.KNOX_APP_MGMT",
            "com.samsung.android.knox.permission.KNOX_CUSTOM_SYSTEM",
            "com.samsung.android.knox.permission.KNOX_CRITICAL_COMMUNICATIONS"
        };
        for (String p : perms) {
            boolean ok = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
            s.append("PERMISSION ").append(p).append('=').append(ok ? "GRANTED" : "DENIED").append('\n');
        }
        String[] classes = {
            "com.samsung.android.knox.EnterpriseDeviceManager",
            "com.samsung.android.knox.application.ApplicationPolicy",
            "com.samsung.android.knox.restriction.RestrictionPolicy",
            "com.samsung.android.knox.custom.CustomDeviceManager",
            "com.samsung.android.knox.custom.SystemManager",
            "com.samsung.android.knox.kpcc.KPCCManager",
            "com.samsung.android.telecom.SemTelecomManager",
            "com.samsung.android.telephony.SemTelephonyManager"
        };
        for (String c : classes) s.append("CLASS ").append(c).append('=').append(classExists(c) ? "PRESENT" : "ABSENT").append('\n');
        output.setText(s.toString());
    }

    private void runShellScan() {
        status.setText("Estado: a ligar UID shell…");
        Executors.newSingleThreadExecutor().submit(() -> {
            EmbeddedAdbManager adb = EmbeddedAdbManager.get(this);
            ServerSocket server = null;
            io.github.muntashirakon.adb.AdbStream stream = null;
            Socket socket = null;
            try {
                if (!adb.ensureConnected()) throw new IllegalStateException("ADB não ligado · " + adb.lastDiagnostic());
                server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                server.setSoTimeout(12000);
                int port = server.getLocalPort();
                String apk = getApplicationInfo().sourceDir;
                String cmd = "CLASSPATH='" + apk + "' exec app_process / com.lumin.app.RebornSamsungCapabilityDaemon " + port;
                stream = adb.openShell(cmd);
                socket = server.accept();
                socket.setSoTimeout(12000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder shell = new StringBuilder("\n=== SHELL UID / SYSTEM SURFACE ===\n");
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("CAP|")) continue;
                    String msg = line.substring(4);
                    shell.append(msg).append('\n');
                    if ("DONE".equals(msg)) break;
                }
                String text = output.getText().toString() + shell;
                runOnUiThread(() -> { output.setText(text); status.setText("Estado: MAPA COMPLETO ✅"); toast("Mapa Samsung concluído"); });
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                runOnUiThread(() -> { status.setText("Estado: erro · " + msg); toast(msg); });
            } finally {
                try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
                try { if (server != null) server.close(); } catch (Throwable ignored) {}
                try { if (stream != null) stream.close(); } catch (Throwable ignored) {}
            }
        });
    }

    private boolean classExists(String name) { try { Class.forName(name); return true; } catch (Throwable t) { return false; } }
    private TextView tv(String s, int sp, int color, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); return v; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
