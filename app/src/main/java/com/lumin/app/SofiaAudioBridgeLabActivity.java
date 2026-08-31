package com.lumin.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;

/** Diagnostic probe for public, non-root Android audio paths. */
public class SofiaAudioBridgeLabActivity extends AppCompatActivity {
    private static final int REQ_MIC = 6401;
    private TextView logView;
    private volatile boolean running = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("SOFIA Audio Bridge Lab");
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(5, 8, 18));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(32));
        scroll.addView(root);

        root.addView(text("AUDIO BRIDGE LAB · 60.5", 25, Color.WHITE, true));
        TextView sub = text("Teste sem root: MIC + VOICE_COMMUNICATION + rota de reprodução de chamada", 13, Color.rgb(145,157,191), false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        Button test = new Button(this);
        test.setText("TESTAR ROTAS AGORA");
        test.setAllCaps(false);
        test.setOnClickListener(v -> startProbe());
        root.addView(test, new LinearLayout.LayoutParams(-1, dp(58)));

        Button tone = new Button(this);
        tone.setText("TOCAR TOM VOICE_COMMUNICATION");
        tone.setAllCaps(false);
        tone.setOnClickListener(v -> new Thread(this::playVoiceCommunicationTone).start());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(54));
        tp.topMargin = dp(8);
        root.addView(tone, tp);

        logView = text("Pronto. Corre durante a chamada e depois fora dela para comparar.\n", 12, Color.rgb(207,216,240), false);
        logView.setPadding(0, dp(18), 0, 0);
        logView.setGravity(Gravity.START);
        root.addView(logView);
        return scroll;
    }

    private void startProbe() {
        if (running) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        running = true;
        logView.setText("");
        new Thread(() -> {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                append("MODE antes: " + am.getMode());
                try { am.setMode(AudioManager.MODE_IN_COMMUNICATION); } catch (Throwable t) { append("setMode falhou: " + t.getClass().getSimpleName()); }
                append("MODE agora: " + am.getMode());
                dumpDevices(am);
                probeSource("MIC", MediaRecorder.AudioSource.MIC);
                probeSource("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                append("---");
                append("RMS abaixo de ~20 e peak de poucos pontos é silêncio/ruído digital, não voz útil.");
            } finally {
                running = false;
            }
        }).start();
    }

    private void dumpDevices(AudioManager am) {
        if (Build.VERSION.SDK_INT >= 23) {
            AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_ALL);
            append("Dispositivos áudio: " + devices.length);
            for (AudioDeviceInfo d : devices) append(" • " + typeName(d.getType()) + " in=" + d.isSource() + " out=" + d.isSink() + " id=" + d.getId());
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                append("Communication devices disponíveis: " + am.getAvailableCommunicationDevices().size());
                AudioDeviceInfo selected = am.getCommunicationDevice();
                append("Communication device atual: " + (selected == null ? "null" : typeName(selected.getType()) + " #" + selected.getId()));
            } catch (Throwable t) { append("CommunicationDevice API: " + t.getClass().getSimpleName()); }
        }
    }

    private void probeSource(String label, int source) {
        final int sr = 16000;
        int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, sr / 2);
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            append(label + " state=" + rec.getState() + " session=" + rec.getAudioSessionId());
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
            rec.startRecording();
            short[] buf = new short[Math.max(320, size / 2)];
            long sumSq = 0L, count = 0L;
            int peak = 0;
            long end = System.currentTimeMillis() + 1100L;
            while (System.currentTimeMillis() < end) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) {
                    int v = buf[i], a = Math.abs(v);
                    if (a > peak) peak = a;
                    sumSq += (long) v * v;
                    count++;
                }
            }
            double rms = count == 0 ? 0 : Math.sqrt(sumSq / (double) count);
            String verdict = (rms >= 20.0 || peak >= 100) ? "PCM ÚTIL/POSSÍVEL" : "SILÊNCIO/BLOQUEADO";
            append(String.format(Locale.US, "%s RMS=%.1f peak=%d frames=%d → %s", label, rms, peak, count, verdict));
        } catch (SecurityException se) { append(label + " BLOQUEADO: SecurityException"); }
        catch (Throwable t) { append(label + " ERRO: " + t.getClass().getSimpleName() + " " + safe(t.getMessage())); }
        finally {
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignored) {}
                try { rec.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private void playVoiceCommunicationTone() {
        final int sr = 16000, ms = 650;
        int samples = sr * ms / 1000;
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            double env = Math.min(1.0, Math.min(i / 800.0, (samples - i) / 800.0));
            pcm[i] = (short) (Math.sin(2.0 * Math.PI * 440.0 * i / sr) * 5000.0 * env);
        }
        AudioTrack track = null;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioFormat fmt = new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            track = new AudioTrack(attrs, fmt, pcm.length * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            int wrote = track.write(pcm, 0, pcm.length);
            append("AudioTrack VOICE_COMMUNICATION state=" + track.getState() + " wrote=" + wrote);
            track.play();
            Thread.sleep(ms + 150L);
            append("Tom terminado. Confirma no outro telefone se foi audível na chamada.");
        } catch (Throwable t) { append("AudioTrack ERRO: " + t.getClass().getSimpleName() + " " + safe(t.getMessage())); }
        finally { if (track != null) try { track.release(); } catch (Throwable ignored) {} }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startProbe();
    }

    private void append(String s) { runOnUiThread(() -> logView.append(s + "\n")); }
    private String safe(String s) { return s == null ? "" : s.replace('\n', ' '); }
    private TextView text(String s, int sp, int color, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v; }
    private String typeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "EARPIECE";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "SPEAKER";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "MIC";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BT_SCO";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "BT_A2DP";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED_HEADSET";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB_DEVICE";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB_HEADSET";
            default: return "TYPE_" + t;
        }
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
