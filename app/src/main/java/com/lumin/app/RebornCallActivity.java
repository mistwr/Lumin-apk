package com.lumin.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class RebornCallActivity extends Activity {
    private static volatile RebornCallActivity current;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("REBORN CALL — Audio Probe V4");
        title.setTextSize(22f);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(14f);
        status.setPadding(0, 24, 0, 24);
        root.addView(status, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button probe = new Button(this);
        probe.setText("TESTAR ROTAS AGORA");
        probe.setOnClickListener(v -> {
            String r = RebornCallAudioController.probeNow(this);
            RebornCentral.save("audio_route_probe_v4", r);
            refresh();
        });
        root.addView(probe);

        Button speaker = new Button(this);
        speaker.setText("ALTIFALANTE");
        speaker.setOnClickListener(v -> {
            RebornInCallService s = RebornInCallService.get();
            if (s != null) s.setSpeaker(true);
            refresh();
        });
        root.addView(speaker);

        Button earpiece = new Button(this);
        earpiece.setText("AUSCULTADOR");
        earpiece.setOnClickListener(v -> {
            RebornInCallService s = RebornInCallService.get();
            if (s != null) s.setSpeaker(false);
            refresh();
        });
        root.addView(earpiece);

        Button hangup = new Button(this);
        hangup.setText("DESLIGAR");
        hangup.setOnClickListener(v -> {
            RebornInCallService s = RebornInCallService.get();
            if (s != null) s.hangup();
        });
        root.addView(hangup);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    private void refresh() {
        if (status == null) return;
        StringBuilder b = new StringBuilder();
        b.append("CALL: ").append(RebornCentral.read("call_state")).append('\n');
        b.append("AUDIO: ").append(RebornCentral.read("audio_state")).append('\n');
        b.append("OUTPUT: ").append(RebornCentral.read("audio_output_mode")).append("\n\n");
        b.append("CLIENTE: ").append(RebornCentral.lastCustomer()).append("\n\n");
        b.append("REBORN: ").append(RebornCentral.lastAssistant()).append("\n\n");
        b.append(RebornCentral.read("audio_route_probe_v4"));
        status.setText(b.toString());
    }

    public static void refreshFromService() {
        RebornCallActivity a = current;
        if (a != null) a.main.post(a::refresh);
    }
}
