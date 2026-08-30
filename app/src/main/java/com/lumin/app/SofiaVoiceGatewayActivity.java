package com.lumin.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Build 59 lab: the Fold acts as the SOFIA voice brain.
 * It uses Android's currently selected audio input/output route, so a future
 * USB/Bluetooth HFP bridge can expose the S26 call audio without needing GSM PCM access.
 */
public class SofiaVoiceGatewayActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    private static final int REQ_AUDIO = 5901;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView state, route, heard, reply, latency;
    private Button toggle, testVoice;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean running = false;
    private boolean speaking = false;
    private long heardAt = 0L;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        tts = new TextToSpeech(this, this);
        refreshRoute();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7,10,22));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(36));
        scroll.addView(root);

        root.addView(text("SOFIA VOICE GATEWAY", 28, Color.WHITE, true));
        TextView sub = text("Build 59 · Fold = STT + LLM + TTS", 14, Color.rgb(161,173,218), false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        state = text("○ PARADO", 18, Color.rgb(255,210,120), true);
        root.addView(state);
        route = text("Áudio: a verificar…", 14, Color.rgb(180,190,220), false);
        route.setPadding(0, dp(10), 0, 0);
        root.addView(route);

        toggle = button("INICIAR AGENTE DE VOZ");
        toggle.setOnClickListener(v -> toggleGateway());
        root.addView(toggle, buttonParams());

        Button refresh = button("Atualizar rota de áudio");
        refresh.setOnClickListener(v -> refreshRoute());
        root.addView(refresh, buttonParams());

        testVoice = button("Testar voz SOFIA");
        testVoice.setOnClickListener(v -> speak("Olá. Sou a SOFIA. O canal de voz está pronto para teste."));
        root.addView(testVoice, buttonParams());

        root.addView(section("CLIENTE / STT"));
        heard = text("À espera de áudio…", 18, Color.WHITE, false);
        root.addView(heard);

        root.addView(section("SOFIA / LLM"));
        reply = text("À espera…", 18, Color.rgb(106,235,183), false);
        root.addView(reply);

        latency = text("STT — · LLM — · TTS —", 13, Color.rgb(155,165,195), false);
        latency.setPadding(0, dp(14), 0, 0);
        root.addView(latency);

        TextView note = text("Nesta fase a SOFIA usa a rota de áudio normal do Fold. Quando ligarmos um bridge HFP/USB ao S26, o mesmo pipeline passa a receber a chamada externa e a devolver TTS pelo bridge.", 13, Color.rgb(155,165,195), false);
        note.setPadding(0, dp(26), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void toggleGateway() {
        if (running) stopGateway();
        else startGateway();
    }

    private void startGateway() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state.setText("○ STT Android indisponível");
            state.setTextColor(Color.rgb(255,125,125));
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        running = true;
        state.setText("● AGENTE ATIVO · A OUVIR");
        state.setTextColor(Color.rgb(106,235,183));
        toggle.setText("PARAR AGENTE");
        startListeningSoon(80);
    }

    private void stopGateway() {
        running = false;
        speaking = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
        }
        if (tts != null) tts.stop();
        state.setText("○ PARADO");
        state.setTextColor(Color.rgb(255,210,120));
        toggle.setText("INICIAR AGENTE DE VOZ");
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { state.setText("● A OUVIR CLIENTE"); }
        @Override public void onBeginningOfSpeech() { heardAt = System.currentTimeMillis(); }
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() { state.setText("● A PROCESSAR"); }
        @Override public void onError(int error) {
            if (!running || speaking) return;
            state.setText("● A OUVIR · STT retry " + error);
            startListeningSoon(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 700 : 250);
        }
        @Override public void onResults(Bundle results) {
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = list == null || list.isEmpty() ? "" : list.get(0).trim();
            if (text.isEmpty()) { startListeningSoon(150); return; }
            long sttMs = heardAt > 0 ? System.currentTimeMillis() - heardAt : 0;
            heard.setText(text);
            askSofia(text, sttMs);
        }
        @Override public void onPartialResults(Bundle partialResults) {
            ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) heard.setText("… " + list.get(0));
        }
        @Override public void onEvent(int eventType, Bundle params) {}
    };

    private void askSofia(String customer, long sttMs) {
        speaking = true;
        if (recognizer != null) try { recognizer.cancel(); } catch (Throwable ignored) {}
        state.setText("● SOFIA A PENSAR");
        long started = System.currentTimeMillis();
        worker.submit(() -> {
            String out;
            try {
                String prompt = "Cliente: " + customer + "\nResponde apenas com a frase que a SOFIA deve dizer agora. Português de Portugal. Curto, natural, máximo 18 palavras. Uma pergunta no máximo.";
                out = QwenClient.generate(prompt);
                out = clean(out);
            } catch (Throwable t) {
                out = "Certo. Diga-me só o que gostaria de melhorar no serviço atual.";
            }
            final String finalOut = out.isEmpty() ? "Certo. Pode explicar-me melhor?" : out;
            final long llmMs = System.currentTimeMillis() - started;
            main.post(() -> {
                reply.setText(finalOut);
                latency.setText("STT " + sttMs + " ms · LLM " + llmMs + " ms · TTS a iniciar");
                speak(finalOut);
            });
        });
    }

    private String clean(String s) {
        if (s == null) return "";
        String r = s.trim().replace("\n", " ");
        String low = r.toLowerCase(Locale.ROOT);
        if (low.contains("és a sofia") || low.contains("es a sofia") || low.contains("system prompt") || low.contains("responde apenas") || low.contains("cliente:")) return "";
        if (r.startsWith("\"") && r.endsWith("\"") && r.length() > 1) r = r.substring(1, r.length()-1).trim();
        if (r.length() > 220) r = r.substring(0,220).trim();
        return r;
    }

    private void speak(String text) {
        if (tts == null || text == null || text.trim().isEmpty()) {
            speaking = false;
            startListeningSoon(100);
            return;
        }
        speaking = true;
        state.setText("● SOFIA A FALAR");
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "SOFIA_REPLY");
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(new Locale("pt", "PT"));
            tts.setSpeechRate(1.05f);
            tts.setPitch(1.0f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                long t0;
                @Override public void onStart(String utteranceId) { t0 = System.currentTimeMillis(); }
                @Override public void onDone(String utteranceId) {
                    long ttsMs = System.currentTimeMillis() - t0;
                    main.post(() -> {
                        speaking = false;
                        latency.setText(latency.getText() + " · TTS " + ttsMs + " ms");
                        if (running) { state.setText("● A OUVIR CLIENTE"); startListeningSoon(120); }
                        else state.setText("○ PARADO");
                    });
                }
                @Override public void onError(String utteranceId) {
                    main.post(() -> { speaking = false; if (running) startListeningSoon(200); });
                }
            });
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                state.setText("○ Voz pt-PT não instalada");
            }
        } else state.setText("○ TTS indisponível");
    }

    private void startListeningSoon(long delay) {
        main.postDelayed(() -> {
            if (!running || speaking || recognizer == null) return;
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            try { recognizer.startListening(i); } catch (Throwable t) { main.postDelayed(() -> startListeningSoon(500), 500); }
        }, delay);
    }

    private void refreshRoute() {
        AudioManager am = (AudioManager)getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        StringBuilder in = new StringBuilder();
        StringBuilder out = new StringBuilder();
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (in.length() > 0) in.append(", ");
                in.append(shortDevice(d));
            }
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (out.length() > 0) out.append(", ");
                out.append(shortDevice(d));
            }
        } catch (Throwable t) {
            route.setText("Áudio: rota disponível; detalhes protegidos pelo Android");
            return;
        }
        route.setText("ENTRADA: " + in + "\nSAÍDA: " + out);
    }

    private String shortDevice(AudioDeviceInfo d) {
        CharSequence p = d.getProductName();
        String name = p == null ? "" : p.toString().trim();
        return (name.isEmpty() ? "tipo " + d.getType() : name + " (" + d.getType() + ")");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startGateway();
    }

    @Override protected void onDestroy() {
        running = false;
        if (recognizer != null) { try { recognizer.destroy(); } catch (Throwable ignored) {} }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        worker.shutdownNow();
        super.onDestroy();
    }

    private TextView section(String s) {
        TextView v = text(s, 13, Color.rgb(140,151,190), true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin = dp(24); p.bottomMargin = dp(8); v.setLayoutParams(p); return v;
    }
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0,1.12f); if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v;
    }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setTextSize(15); b.setAllCaps(false); b.setGravity(Gravity.CENTER); return b; }
    private LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56)); p.topMargin = dp(10); return p; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
