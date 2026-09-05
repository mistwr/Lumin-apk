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
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class RebornCallActivity extends AppCompatActivity implements RebornCentral.Listener {
    private static RebornCallActivity current;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView state;
    private TextView number;
    private TextView ai;
    private TextView pipeline;
    private TextView transcript;
    private TextView reply;
    private TextView voice;
    private TextView voiceRoute;
    private boolean muted = false;
    private boolean speaker = false;

    public static void refreshFromService() {
        RebornCallActivity a = current;
        if (a != null) a.ui.post(a::refresh);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        RebornCentral.setListener(this);
        buildUi();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        current = this;
        RebornCentral.setListener(this);
        refresh();
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        RebornCentral.setListener(null);
        super.onDestroy();
    }

    @Override public void onCentralChanged() { ui.post(this::refresh); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(4, 8, 17));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(32), dp(22), dp(32));
        scroll.addView(root);

        root.addView(t("REBORN AI · CENTRAL DE CHAMADA", 14, Color.rgb(106,235,183), true));

        number = t("Chamada", 32, Color.WHITE, true);
        number.setGravity(Gravity.CENTER);
        number.setPadding(0, dp(22), 0, dp(4));
        root.addView(number);

        state = t("A ligar…", 16, Color.rgb(180,190,215), false);
        state.setGravity(Gravity.CENTER);
        root.addView(state);

        ai = t("Qwen3 a preparar…", 15, Color.rgb(106,235,183), true);
        ai.setGravity(Gravity.CENTER);
        ai.setPadding(dp(8), dp(14), dp(8), dp(4));
        root.addView(ai);

        pipeline = t("Áudio: IDLE · STT: IDLE", 13, Color.rgb(255,198,94), false);
        pipeline.setGravity(Gravity.CENTER);
        root.addView(pipeline);

        voice = t("Voz: IDLE", 13, Color.rgb(255,198,94), false);
        voice.setGravity(Gravity.CENTER);
        root.addView(voice);

        voiceRoute = t("Rota de voz: AUTO", 13, Color.rgb(148,160,194), true);
        voiceRoute.setGravity(Gravity.CENTER);
        voiceRoute.setPadding(0, dp(4), 0, dp(4));
        root.addView(voiceRoute);

        LinearLayout controls1 = row();
        Button answer = b("Atender");
        answer.setOnClickListener(v -> { RebornInCallService s = RebornInCallService.get(); if (s != null) s.answer(); });
        Button end = b("Desligar");
        end.setOnClickListener(v -> { RebornInCallService s = RebornInCallService.get(); if (s != null) s.hangup(); finish(); });
        controls1.addView(answer, weight());
        controls1.addView(end, weight());
        root.addView(controls1);

        LinearLayout controls2 = row();
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
        controls2.addView(mute, weight());
        controls2.addView(spk, weight());
        root.addView(controls2);

        LinearLayout controls3 = row();
        Button hold = b("Em espera");
        hold.setOnClickListener(v -> { RebornInCallService s = RebornInCallService.get(); if (s != null) s.toggleHold(); });
        Button intro = b("Apresentar REBORN");
        intro.setOnClickListener(v -> RebornCentral.queueIntro());
        controls3.addView(hold, weight());
        controls3.addView(intro, weight());
        root.addView(controls3);

        TextView routeTitle = t("TESTE DE SAÍDA DE VOZ", 12, Color.rgb(148,160,194), true);
        routeTitle.setPadding(0, dp(20), 0, dp(8));
        root.addView(routeTitle);

        LinearLayout route1 = row();
        Button auto = b("AUTO");
        auto.setOnClickListener(v -> setVoiceRoute(RebornVoiceController.ROUTE_AUTO));
        Button samsung = b("Samsung");
        samsung.setOnClickListener(v -> setVoiceRoute(RebornVoiceController.ROUTE_SAMSUNG));
        route1.addView(auto, weight());
        route1.addView(samsung, weight());
        root.addView(route1);

        LinearLayout route2 = row();
        Button acoustic = b("Acústica");
        acoustic.setOnClickListener(v -> setVoiceRoute(RebornVoiceController.ROUTE_ACOUSTIC));
        Button digital = b("Digital EXP");
        digital.setOnClickListener(v -> setVoiceRoute(RebornVoiceController.ROUTE_DIGITAL));
        route2.addView(acoustic, weight());
        route2.addView(digital, weight());
        root.addView(route2);

        Button testVoice = b("TESTE: outro telefone deve ouvir esta frase");
        LinearLayout.LayoutParams testP = new LinearLayout.LayoutParams(-1, dp(62));
        testP.topMargin = dp(10);
        testVoice.setLayoutParams(testP);
        testVoice.setOnClickListener(v -> RebornVoiceController.speak(this,
                "Teste REBORN. Se me está a ouvir no outro telefone, a saída de voz desta rota funcionou."));
        root.addView(testVoice);

        TextView trTitle = t("TRANSCRIÇÃO AO VIVO", 12, Color.rgb(148,160,194), true);
        trTitle.setPadding(0, dp(24), 0, dp(8));
        root.addView(trTitle);
        transcript = t("À espera de voz…", 15, Color.rgb(225,230,242), false);
        transcript.setPadding(dp(14), dp(14), dp(14), dp(14));
        transcript.setBackgroundColor(Color.rgb(14,22,39));
        root.addView(transcript, new LinearLayout.LayoutParams(-1, -2));

        TextView rpTitle = t("RESPOSTA REBORN", 12, Color.rgb(148,160,194), true);
        rpTitle.setPadding(0, dp(20), 0, dp(8));
        root.addView(rpTitle);
        reply = t("A aguardar cliente…", 17, Color.rgb(106,235,183), true);
        reply.setPadding(dp(14), dp(14), dp(14), dp(14));
        reply.setBackgroundColor(Color.rgb(14,22,39));
        root.addView(reply, new LinearLayout.LayoutParams(-1, -2));

        Button speak = b("Falar resposta agora");
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(-1, dp(62));
        full.topMargin = dp(12);
        speak.setLayoutParams(full);
        speak.setOnClickListener(v -> {
            String text = RebornCentral.lastReply();
            if (text != null && !text.trim().isEmpty()) RebornVoiceController.speak(this, text.trim());
        });
        root.addView(speak);

        TextView note = t("AUTO tenta Samsung Text Call e mantém TTS local. ACÚSTICA força altifalante para provar o ciclo completo. DIGITAL EXP não é marcado como funcional enquanto o outro telefone não ouvir uma injeção digital direta.", 12, Color.rgb(145,155,180), false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void setVoiceRoute(String route) {
        RebornVoiceController.setRoute(this, route);
        refresh();
    }

    private void refresh() {
        Call c = RebornInCallService.activeCall();
        if (c == null) {
            state.setText("Sem chamada ativa");
        } else {
            try {
                if (c.getDetails() != null && c.getDetails().getHandle() != null) {
                    String p = c.getDetails().getHandle().getSchemeSpecificPart();
                    if (p != null && !p.isEmpty()) number.setText(p);
                }
            } catch (Throwable ignored) {}
            state.setText(label(c.getState()));
        }

        String mode = getSharedPreferences("sofia_control", MODE_PRIVATE).getString("mode", "AUTO");
        ai.setText("Qwen3 · " + LocalRebornEngine.backendName() + " · " + mode + " · " + RebornCentral.stage() +
                (RebornCentral.lastLatencyMs() > 0 ? " · " + RebornCentral.lastLatencyMs() + " ms" : ""));

        String pcm = getSharedPreferences("reborn_central", MODE_PRIVATE).getString("pcm_capture", "IDLE");
        String sttInput = getSharedPreferences("reborn_central", MODE_PRIVATE).getString("stt_input", "—");
        pipeline.setText("Áudio: " + RebornCallAudioController.state() + " / PCM " + pcm +
                " · STT: " + RebornTranscriptionService.state() + " / " + sttInput);

        String tr = RebornCentral.transcript();
        String partial = getSharedPreferences("reborn_central", MODE_PRIVATE).getString("stt_partial", "");
        if (tr == null || tr.trim().isEmpty()) {
            transcript.setText(partial == null || partial.trim().isEmpty() ? "À espera de voz…" : "… " + partial.trim());
        } else {
            transcript.setText(tr.trim() + (partial == null || partial.trim().isEmpty() ? "" : "\n… " + partial.trim()));
        }

        String r = RebornCentral.lastReply();
        reply.setText(r == null || r.trim().isEmpty() ? "A aguardar cliente…" : r);
        voice.setText("Voz: " + RebornVoiceController.state());
        voiceRoute.setText("Rota de voz: " + RebornVoiceController.route());
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
        p.setMargins(dp(5), 0, dp(5), 0);
        return p;
    }

    private Button b(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
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
