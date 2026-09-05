package com.lumin.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Manual, user-triggered bridge for Samsung Text Call.
 *
 * This activity does not call Samsung privileged APIs. It sends a local command
 * to the REBORN accessibility service, which then fills/clicks the currently
 * visible Samsung Text Call UI using normal Accessibility actions.
 */
public class RebornSamsungTextCallBridgeActivity extends AppCompatActivity {
    private static final String DEFAULT_TEST = "Olá. Esta é uma mensagem de teste do REBORN através do Samsung Text Call.";
    private EditText message;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(4, 8, 17));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        TextView title = tv("REBORN · Samsung Text Call Bridge", 24, Color.WHITE, true);
        root.addView(title);

        TextView desc = tv(
                "Teste manual da ponte REBORN → Samsung Text Call. Abre uma chamada, ativa o Text Call da Samsung e só depois toca em ENVIAR TESTE.",
                14, Color.rgb(175,188,215), false);
        desc.setPadding(0, dp(8), 0, dp(18));
        root.addView(desc);

        status = tv("Estado: pronto para teste manual", 14, Color.rgb(106,235,183), true);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status);

        message = new EditText(this);
        message.setText(DEFAULT_TEST);
        message.setTextColor(Color.WHITE);
        message.setHintTextColor(Color.rgb(120,135,160));
        message.setHint("Texto que a Samsung deverá dizer na chamada");
        message.setGravity(Gravity.TOP | Gravity.START);
        message.setMinLines(4);
        message.setBackgroundColor(Color.rgb(14,22,39));
        message.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(message, new LinearLayout.LayoutParams(-1, -2));

        Button accessibility = button("ABRIR DEFINIÇÕES DE ACESSIBILIDADE");
        accessibility.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Throwable t) { toast("Não foi possível abrir Acessibilidade"); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(58));
        bp.topMargin = dp(14);
        root.addView(accessibility, bp);

        Button send = button("ENVIAR TESTE PELO TEXT CALL");
        send.setOnClickListener(v -> sendTest());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(58));
        sp.topMargin = dp(10);
        root.addView(send, sp);

        Button openPhone = button("ABRIR SAMSUNG PHONE");
        openPhone.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.samsung.android.dialer");
            if (i == null) { toast("Samsung Phone não encontrado"); return; }
            startActivity(i);
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(58));
        pp.topMargin = dp(10);
        root.addView(openPhone, pp);

        TextView note = tv(
                "Prova de sucesso: o texto desaparece do campo Samsung e a pessoa do outro lado ouve a voz sintetizada pela própria Samsung. O REBORN não injeta áudio diretamente no GSM neste teste.",
                13, Color.rgb(175,188,215), false);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void sendTest() {
        String text = message.getText() == null ? "" : message.getText().toString().trim();
        if (text.isEmpty()) { toast("Escreve uma mensagem de teste"); return; }

        Intent command = new Intent(SofiaAccessibilityService.ACTION_SEND_REPLY);
        command.setPackage(getPackageName());
        command.putExtra(SofiaAccessibilityService.EXTRA_REPLY, text);
        sendBroadcast(command);
        status.setText("Estado: comando enviado ao bridge · verifica a chamada");
        toast("Comando enviado ao Text Call bridge");
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
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
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
