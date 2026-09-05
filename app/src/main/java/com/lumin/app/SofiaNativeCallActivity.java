package com.lumin.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.telecom.Call;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.concurrent.Executors;

public class SofiaNativeCallActivity extends AppCompatActivity {
    private TextView callState;
    private TextView audioState;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        updateCallState();
        ensurePermissions();
        probeAudio();
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(34), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(7,10,22));

        TextView title = text("SOFIA", 38, Color.WHITE, true);
        root.addView(title);
        TextView sub = text("Build 57 · Native Call Lab", 15, Color.rgb(161,173,218), false);
        root.addView(sub);

        callState = text("CHAMADA: a verificar…", 18, Color.rgb(106,235,183), true);
        callState.setPadding(0, dp(26), 0, dp(8));
        root.addView(callState);

        audioState = text("ÁUDIO: a testar…", 16, Color.rgb(255,210,120), true);
        root.addView(audioState);

        TextView note = text("Objetivo deste ecrã: confirmar se uma app Telefone normal consegue receber PCM remoto da chamada. MIC LOCAL e REMOTE PCM são testados separadamente.", 14, Color.rgb(190,199,225), false);
        note.setPadding(0, dp(18), 0, dp(10));
        root.addView(note);

        Button probe = button("Testar áudio nativo agora");
        probe.setOnClickListener(v -> probeAudio());
        root.addView(probe, buttonParams());

        Button answer = button("Atender");
        answer.setOnClickListener(v -> {
            Call c = SofiaInCallService.activeCall;
            if (c != null) c.answer(0);
            updateCallState();
        });
        root.addView(answer, buttonParams());

        Button hang = button("Desligar");
        hang.setOnClickListener(v -> {
            Call c = SofiaInCallService.activeCall;
            if (c != null) c.disconnect();
            finish();
        });
        root.addView(hang, buttonParams());

        TextView verdict = text("Se REMOTE PCM = OK, a próxima fase liga STT local + Qwen + TTS sem Samsung Text Call. Se estiver BLOQUEADO, mantemos a SOFIA como UI/dialer e usamos Samsung apenas como driver de áudio/transcrição.", 14, Color.rgb(106,235,183), true);
        verdict.setPadding(0, dp(22), 0, 0);
        root.addView(verdict);
        return root;
    }

    private void ensurePermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE}, 803);
        }
    }

    private void probeAudio() {
        audioState.setText("ÁUDIO: a testar MIC LOCAL e REMOTE PCM…");
        Executors.newSingleThreadExecutor().submit(() -> {
            NativeCallAudioProbe.Result r = NativeCallAudioProbe.probe(this);
            runOnUiThread(() -> {
                String txt = "MIC LOCAL: " + (r.micOk ? "OK" : "NÃO") + "\nREMOTE PCM: " + (r.remotePcmOk ? "OK" : "BLOQUEADO/INDISPONÍVEL") + "\n" + r.detail;
                audioState.setText(txt);
                audioState.setTextColor(r.remotePcmOk ? Color.rgb(106,235,183) : Color.rgb(255,180,110));
            });
        });
    }

    private void updateCallState() {
        Call c = SofiaInCallService.activeCall;
        int s = c == null ? -1 : c.getState();
        String name;
        switch (s) {
            case Call.STATE_ACTIVE: name = "ATIVA"; break;
            case Call.STATE_RINGING: name = "A TOCAR"; break;
            case Call.STATE_DIALING: name = "A LIGAR"; break;
            case Call.STATE_CONNECTING: name = "A CONECTAR"; break;
            case Call.STATE_HOLDING: name = "EM ESPERA"; break;
            case Call.STATE_DISCONNECTED: name = "TERMINADA"; break;
            default: name = "SEM CHAMADA";
        }
        callState.setText("CHAMADA: " + name);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setGravity(Gravity.START);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(16); return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58)); p.topMargin = dp(10); return p;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
