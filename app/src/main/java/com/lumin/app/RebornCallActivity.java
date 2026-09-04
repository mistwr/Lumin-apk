package com.lumin.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class RebornCallActivity extends AppCompatActivity {
    private static RebornCallActivity current;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView state;
    private TextView number;
    private TextView ai;
    private boolean muted = false;
    private boolean speaker = false;

    public static void refreshFromService() {
        RebornCallActivity a = current;
        if (a != null) a.ui.post(a::refresh);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        buildUi();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        current = this;
        refresh();
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(42), dp(24), dp(30));
        root.setBackgroundColor(Color.rgb(4, 8, 17));

        TextView brand = t("REBORN PHONE", 15, Color.rgb(106,235,183), true);
        root.addView(brand);

        number = t("Chamada", 34, Color.WHITE, true);
        number.setGravity(Gravity.CENTER);
        number.setPadding(0, dp(30), 0, dp(8));
        root.addView(number);

        state = t("A ligar…", 17, Color.rgb(180,190,215), false);
        state.setGravity(Gravity.CENTER);
        root.addView(state);

        ai = t("IA local pronta para transcrição e assistência.", 15, Color.rgb(106,235,183), false);
        ai.setGravity(Gravity.CENTER);
        ai.setPadding(dp(8), dp(28), dp(8), dp(24));
        root.addView(ai);

        LinearLayout row1 = row();
        Button answer = b("Atender");
        answer.setOnClickListener(v -> { RebornInCallService s = RebornInCallService.get(); if (s != null) s.answer(); });
        Button end = b("Desligar");
        end.setOnClickListener(v -> { RebornInCallService s = RebornInCallService.get(); if (s != null) s.hangup(); finish(); });
        row1.addView(answer, weight());
        row1.addView(end, weight());
        root.addView(row1);

        LinearLayout row2 = row();
        Button mute = b("Mute");
        mute.setOnClickListener(v -> {
            muted = !muted;
            RebornInCallService s = RebornInCallService.get();
            if (s != null) s.setMutedCompat(muted);
            mute.setText(muted ? "Mute ✓" : "Mute");
        });
        Button spk = b("Altifalante");
        spk.setOnClickListener(v -> {
            speaker = !speaker;
            RebornInCallService s = RebornInCallService.get();
            if (s != null) s.setSpeaker(speaker);
            spk.setText(speaker ? "Altifalante ✓" : "Altifalante");
        });
        row2.addView(mute, weight());
        row2.addView(spk, weight());
        root.addView(row2);

        LinearLayout row3 = row();
        Button hold = b("Em espera");
        hold.setOnClickListener(v -> { RebornInCallService s = RebornInCallService.get(); if (s != null) s.toggleHold(); });
        Button reject = b("Rejeitar");
        reject.setOnClickListener(v -> { RebornInCallService s = RebornInCallService.get(); if (s != null) s.reject(); finish(); });
        row3.addView(hold, weight());
        row3.addView(reject, weight());
        root.addView(row3);

        TextView note = t("REBORN Phone v1 · controlo nativo Android Telecom\nGravação/transcrição PCM entra na próxima camada do motor.", 13, Color.rgb(145,155,180), false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(26), 0, 0);
        root.addView(note);

        setContentView(root);
    }

    private void refresh() {
        Call c = RebornInCallService.activeCall();
        if (c == null) {
            state.setText("Sem chamada ativa");
            return;
        }
        try {
            if (c.getDetails() != null && c.getDetails().getHandle() != null) {
                String p = c.getDetails().getHandle().getSchemeSpecificPart();
                if (p != null && !p.isEmpty()) number.setText(p);
            }
        } catch (Throwable ignored) {}
        state.setText(label(c.getState()));
        ai.setText("Qwen3 · " + LocalRebornEngine.backendName() + " · modo " + getSharedPreferences("sofia_control", MODE_PRIVATE).getString("mode", "AUTO"));
    }

    private String label(int s) {
        switch (s) {
            case Call.STATE_NEW: return "Nova chamada";
            case Call.STATE_DIALING: return "A ligar…";
            case Call.STATE_RINGING: return "A receber chamada";
            case Call.STATE_ACTIVE: return "Chamada ativa";
            case Call.STATE_HOLDING: return "Em espera";
            case Call.STATE_DISCONNECTED: return "Chamada terminada";
            case Call.STATE_CONNECTING: return "A estabelecer ligação…";
            default: return "Estado " + s;
        }
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, 0);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(62), 1f);
        p.setMargins(dp(6), 0, dp(6), 0);
        return p;
    }

    private Button b(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(15);
        b.setAllCaps(false);
        return b;
    }

    private TextView t(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
