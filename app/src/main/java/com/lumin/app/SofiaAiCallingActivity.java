package com.lumin.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SofiaAiCallingActivity extends AppCompatActivity {
    private static final int REQ_CALL = 6021;
    private EditText phone, agent, brand, objective, script, opening;
    private Switch autoTextCall;
    private SharedPreferences control;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        setContentView(buildUi());
        loadProfile();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(5,8,18));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(40));
        scroll.addView(root);

        TextView title = text("SOFIA AI CALLING", 30, Color.WHITE, true);
        root.addView(title);
        TextView sub = text("GSM + Samsung Text Call + LLM", 14, Color.rgb(126,145,205), true);
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        LinearLayout hero = card();
        hero.addView(text("● AGENTE PRONTO", 13, Color.rgb(106,235,183), true));
        hero.addView(text("A chamada usa o SIM do S26. A Samsung transcreve e fala; a SOFIA decide o que responder.", 15, Color.rgb(223,229,246), false));
        root.addView(hero);

        root.addView(section("CLIENTE"));
        phone = field("Número de telefone", "");
        phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        root.addView(phone);

        root.addView(section("AGENTE"));
        agent = field("Nome da agente", "SOFIA");
        brand = field("Marca / empresa", "MyPoupar");
        objective = field("Objetivo da chamada", "Ajudar o cliente a poupar em telecomunicações e energia");
        script = field("Script / regras comerciais", "Qualifica primeiro. Faz uma pergunta de cada vez. Não inventes preços. Só faz handoff com intenção explícita.");
        opening = field("Frase de abertura", "Olá. Falo da MyPoupar. Posso fazer-lhe duas perguntas rápidas para perceber se consegue poupar?");
        root.addView(agent); root.addView(brand); root.addView(objective); root.addView(script); root.addView(opening);

        autoTextCall = new Switch(this);
        autoTextCall.setText("Entrar automaticamente em Samsung Text Call");
        autoTextCall.setTextColor(Color.WHITE);
        autoTextCall.setTextSize(14);
        autoTextCall.setPadding(0, dp(14), 0, dp(6));
        root.addView(autoTextCall);

        Button call = new Button(this);
        call.setText("CHAMAR AGORA");
        call.setTextSize(17);
        call.setAllCaps(false);
        call.setTextColor(Color.rgb(4,16,14));
        call.setBackground(round(Color.rgb(106,235,183), 18));
        call.setOnClickListener(v -> prepareAndCall());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(64)); cp.topMargin = dp(16);
        root.addView(call, cp);

        Button save = secondary("Guardar perfil");
        save.setOnClickListener(v -> saveProfile());
        root.addView(save, buttonParams());

        TextView note = text("Quando o Text Call abrir, o painel SOFIA LIVE acompanha a transcrição e responde em modo AUTO. A mensagem de aviso da Samsung ao cliente não é removida.", 12, Color.rgb(145,157,191), false);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void loadProfile() {
        phone.setText(control.getString("dial_phone", ""));
        agent.setText(control.getString("agent_name", "SOFIA"));
        brand.setText(control.getString("agent_brand", "MyPoupar"));
        objective.setText(control.getString("agent_objective", "Ajudar o cliente a poupar em telecomunicações e energia"));
        script.setText(control.getString("active_script", "Qualifica primeiro. Faz uma pergunta de cada vez. Não inventes preços. Só faz handoff com intenção explícita."));
        opening.setText(control.getString("agent_opening", "Olá. Falo da MyPoupar. Posso fazer-lhe duas perguntas rápidas para perceber se consegue poupar?"));
        autoTextCall.setChecked(control.getBoolean("auto_text_call", true));
        applyRuntimeProfile();
    }

    private void saveProfile() {
        control.edit()
                .putString("dial_phone", val(phone))
                .putString("agent_name", val(agent))
                .putString("agent_brand", val(brand))
                .putString("agent_objective", val(objective))
                .putString("active_script", val(script))
                .putString("agent_opening", val(opening))
                .putBoolean("auto_text_call", autoTextCall.isChecked())
                .putString("mode", "AUTO")
                .apply();
        applyRuntimeProfile();
    }

    private void applyRuntimeProfile() {
        SofiaAgentProfile.configure(val(agent), val(brand), "consultivo, natural e profissional", val(objective), val(script), val(opening));
    }

    private void prepareAndCall() {
        saveProfile();
        String number = val(phone).replace(" ", "");
        if (number.isEmpty()) { phone.setError("Introduz um número"); return; }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL);
            return;
        }
        startCall(number);
    }

    private void startCall(String number) {
        control.edit().putLong("auto_text_call_armed_at", System.currentTimeMillis()).apply();
        Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
        startActivity(i);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            String number = val(phone).replace(" ", "");
            if (!number.isEmpty()) startCall(number);
        }
    }

    private String val(EditText e) { return e.getText() == null ? "" : e.getText().toString().trim(); }
    private TextView section(String s) { TextView v = text(s, 11, Color.rgb(123,137,177), true); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(22); p.bottomMargin=dp(8); v.setLayoutParams(p); return v; }
    private EditText field(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(Color.rgb(120,132,164)); e.setTextColor(Color.WHITE); e.setText(value); e.setTextSize(14); e.setPadding(dp(14),dp(12),dp(14),dp(12)); e.setBackground(round(Color.rgb(17,23,43), 14)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(7); e.setLayoutParams(p); return e; }
    private TextView text(String s,int sp,int c,boolean b){ TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(c); v.setLineSpacing(0,1.1f); if(b)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD); return v; }
    private LinearLayout card(){ LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(16),dp(14),dp(16),dp(14)); c.setBackground(round(Color.rgb(13,19,36),18)); return c; }
    private Button secondary(String label){ Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setGravity(Gravity.CENTER); return b; }
    private LinearLayout.LayoutParams buttonParams(){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54)); p.topMargin=dp(8); return p; }
    private GradientDrawable round(int color,int radius){ GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
    private int dp(int n){ return Math.round(n*getResources().getDisplayMetrics().density); }
}
