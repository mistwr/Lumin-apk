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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SofiaAiCallingActivity extends AppCompatActivity {
    private static final int REQ_CALL = 6021;
    private EditText phone, agent, brand, objective, script, opening;
    private Spinner agentSelector;
    private Switch autoTextCall;
    private SharedPreferences control;
    private boolean loadingProfile = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        setContentView(buildUi());
        loadProfile();
        LocalQwenManager.warmUpAsync(this);
        SdDialerBrainClient.refreshAsync(this);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(5,8,18));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(40));
        scroll.addView(root);

        root.addView(text("REBORN AI", 12, Color.rgb(106,235,183), true));
        root.addView(text("AI CALLING", 32, Color.WHITE, true));
        TextView sub = text("MyPoupar Agent Hub · SD Dialer + Samsung Text Call + IA local", 14, Color.rgb(126,145,205), true);
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        LinearLayout hero = card();
        hero.addView(text("● REBORN BRAIN ONLINE", 13, Color.rgb(106,235,183), true));
        hero.addView(text("Cada agente tem objetivo, tom, guião e abertura próprios. O cérebro comercial sincroniza com o SD Dialer.", 15, Color.rgb(223,229,246), false));
        root.addView(hero);

        root.addView(section("CLIENTE"));
        phone = field("Número de telefone", "");
        phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        root.addView(phone);

        root.addView(section("AGENTE REBORN AI"));
        agentSelector = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, RebornAgentCatalog.labels());
        agentSelector.setAdapter(adapter);
        agentSelector.setBackground(round(Color.rgb(17,23,43),14));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(56)); sp.topMargin=dp(7);
        root.addView(agentSelector, sp);

        agent = field("Nome do agente", "SOFIA");
        brand = field("Marca / empresa", "MyPoupar");
        objective = field("Objetivo da chamada", "");
        script = field("Script / regras comerciais", "");
        opening = field("Frase de abertura", "");
        root.addView(agent); root.addView(brand); root.addView(objective); root.addView(script); root.addView(opening);

        agentSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (loadingProfile) return;
                applyCatalogProfile(position, true);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        autoTextCall = new Switch(this);
        autoTextCall.setText("Entrar automaticamente em Samsung Text Call");
        autoTextCall.setTextColor(Color.WHITE);
        autoTextCall.setTextSize(14);
        autoTextCall.setPadding(0, dp(14), 0, dp(6));
        root.addView(autoTextCall);

        Button call = new Button(this);
        call.setText("INICIAR CHAMADA COM IA");
        call.setTextSize(17); call.setAllCaps(false);
        call.setTextColor(Color.rgb(4,16,14));
        call.setBackground(round(Color.rgb(106,235,183),18));
        call.setOnClickListener(v -> prepareAndCall());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(64)); cp.topMargin=dp(16);
        root.addView(call,cp);

        Button sync = secondary("Sincronizar SD Dialer agora");
        sync.setOnClickListener(v -> SdDialerBrainClient.refreshAsync(this));
        root.addView(sync,buttonParams());

        Button save = secondary("Guardar alterações deste agente");
        save.setOnClickListener(v -> saveProfile());
        root.addView(save,buttonParams());

        TextView note = text("Build 61.0 · perfis completos por agente + fecho automático da chamada para o SD Dialer", 12, Color.rgb(106,235,183), true);
        note.setPadding(0,dp(18),0,0); root.addView(note);

        TextView credit = text("Criado pela MyPoupar · Desenvolvido pelo CEO da MyPoupar com apoio da Soluções Diferentes · Feito por REBORN AI", 12, Color.rgb(145,157,191), false);
        credit.setPadding(0,dp(8),0,0); root.addView(credit);
        return scroll;
    }

    private void loadProfile() {
        loadingProfile = true;
        phone.setText(control.getString("dial_phone",""));
        String savedId = control.getString("agent_profile_id", control.getString("agent_name","SOFIA"));
        int index = RebornAgentCatalog.indexFor(savedId);
        agentSelector.setSelection(index, false);
        RebornAgentCatalog.AgentProfile base = RebornAgentCatalog.at(index);

        agent.setText(control.getString("agent_name",base.name));
        brand.setText(control.getString("agent_brand",base.brand));
        objective.setText(control.getString("agent_objective",base.objective));
        script.setText(control.getString("active_script",base.script));
        opening.setText(control.getString("agent_opening",base.opening));
        autoTextCall.setChecked(control.getBoolean("auto_text_call",true));
        loadingProfile = false;
        applyRuntimeProfile();
    }

    private void applyCatalogProfile(int position, boolean persist) {
        RebornAgentCatalog.AgentProfile p = RebornAgentCatalog.at(position);
        agent.setText(p.name);
        brand.setText(p.brand);
        objective.setText(p.objective);
        script.setText(p.script);
        opening.setText(p.opening);
        SofiaAgentProfile.configure(p.name,p.brand,p.tone,p.objective,p.script,p.opening);
        if (persist) {
            control.edit()
                    .putString("agent_profile_id",p.id)
                    .putString("agent_name",p.name)
                    .putString("agent_brand",p.brand)
                    .putString("agent_objective",p.objective)
                    .putString("active_script",p.script)
                    .putString("agent_opening",p.opening)
                    .apply();
        }
    }

    private void saveProfile() {
        RebornAgentCatalog.AgentProfile selected = RebornAgentCatalog.at(agentSelector.getSelectedItemPosition());
        control.edit()
                .putString("dial_phone",val(phone))
                .putString("agent_profile_id",selected.id)
                .putString("agent_name",val(agent))
                .putString("agent_brand",val(brand))
                .putString("agent_objective",val(objective))
                .putString("active_script",val(script))
                .putString("agent_opening",val(opening))
                .putString("sd_company_id",SdDialerBrainClient.DEFAULT_COMPANY_ID)
                .putBoolean("auto_text_call",autoTextCall.isChecked())
                .putString("mode","AUTO").apply();
        applyRuntimeProfile();
    }

    private void applyRuntimeProfile() {
        RebornAgentCatalog.AgentProfile selected = RebornAgentCatalog.at(agentSelector == null ? 0 : agentSelector.getSelectedItemPosition());
        SofiaAgentProfile.configure(val(agent),val(brand),selected.tone,val(objective),val(script),val(opening));
    }

    private void prepareAndCall() {
        saveProfile();
        LocalQwenManager.warmUpAsync(this);
        SdDialerBrainClient.refreshAsync(this);
        String number=val(phone).replace(" ","");
        if(number.isEmpty()){phone.setError("Introduz um número");return;}
        control.edit()
                .putString("dial_phone",number)
                .putLong("call_started_at",System.currentTimeMillis())
                .putBoolean("call_final_synced",false)
                .apply();
        if(checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CALL_PHONE},REQ_CALL);return;}
        startCall(number);
    }

    private void startCall(String number) {
        LocalQwenManager.warmUpAsync(this);
        SdDialerBrainClient.refreshAsync(this);
        control.edit().putLong("auto_text_call_armed_at",System.currentTimeMillis()).apply();
        startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:"+Uri.encode(number))));
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_CALL&&grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED){String n=val(phone).replace(" ","");if(!n.isEmpty())startCall(n);}
    }

    private String val(EditText e){return e.getText()==null?"":e.getText().toString().trim();}
    private TextView section(String s){TextView v=text(s,11,Color.rgb(123,137,177),true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(22);p.bottomMargin=dp(8);v.setLayoutParams(p);return v;}
    private EditText field(String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Color.rgb(120,132,164));e.setTextColor(Color.WHITE);e.setText(value);e.setTextSize(14);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setBackground(round(Color.rgb(17,23,43),14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(7);e.setLayoutParams(p);return e;}
    private TextView text(String s,int sp,int c,boolean b){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(c);v.setLineSpacing(0,1.1f);if(b)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);return v;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackground(round(Color.rgb(13,19,36),18));return c;}
    private Button secondary(String label){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setGravity(Gravity.CENTER);return b;}
    private LinearLayout.LayoutParams buttonParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.topMargin=dp(8);return p;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
