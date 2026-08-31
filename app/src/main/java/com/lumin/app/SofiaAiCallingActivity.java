package com.lumin.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public class SofiaAiCallingActivity extends AppCompatActivity {
    private static final int REQ_CALL = 6021;
    private EditText phone, agent, brand, objective, script, opening;
    private Spinner agentSelector;
    private Switch autoTextCall;
    private SharedPreferences control;
    private LinearLayout adminConfig;
    private TextView aiStatus;
    private boolean loadingProfile = false;
    private final ArrayList<RemoteAgent> remoteAgents = new ArrayList<>();
    private String savedProfileId = "";

    private static final class RemoteAgent {
        String id,name,personality,systemPrompt,objective,opening,script;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        savedProfileId = control.getString("agent_profile_id", control.getString("agent_name","SOFIA"));
        setContentView(buildUi());
        loadProfile();
        loadRemoteAgents();
        loadRole();
        refreshBrains();
    }

    private void refreshBrains() {
        aiStatus.setText("● A preparar REBORN Brain…");
        LocalQwenManager.warmUpAsync(this);
        SdDialerBrainClient.refreshAsync(this);
        RebornEnergyDataClient.refreshAsync(this);
        TelecomCampaignClient.refreshAsync(this);
        aiStatus.postDelayed(() -> aiStatus.setText("● REBORN Brain pronto · SD Dialer + MyPoupar"), 900);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ProductUi.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ProductUi.dp(this,20), ProductUi.dp(this,28), ProductUi.dp(this,20), ProductUi.dp(this,40));
        scroll.addView(root);

        root.addView(ProductUi.text(this,"REBORN AI",12,ProductUi.ACCENT,true));
        root.addView(ProductUi.text(this,"Chamada com IA",32,Color.WHITE,true));
        TextView sub = ProductUi.text(this,"Escolhe o assistente, indica o contacto e liga. O resto é tratado pelo motor REBORN + SD Dialer.",14,ProductUi.SOFT,false);
        sub.setPadding(0,ProductUi.dp(this,5),0,ProductUi.dp(this,14)); root.addView(sub);

        LinearLayout hero=ProductUi.card(this);
        aiStatus=ProductUi.text(this,"● REBORN Brain",13,ProductUi.ACCENT,true); hero.addView(aiStatus);
        hero.addView(ProductUi.text(this,"A conversa é transcrita, analisada e registada para follow-up e histórico comercial.",14,ProductUi.TEXT,false));
        root.addView(hero);

        root.addView(ProductUi.section(this,"CLIENTE"));
        phone=ProductUi.field(this,"Número de telefone"); phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE); root.addView(phone);

        root.addView(ProductUi.section(this,"ASSISTENTE"));
        agentSelector=new Spinner(this); agentSelector.setBackground(ProductUi.round(this,ProductUi.CARD,14));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,ProductUi.dp(this,56)); sp.topMargin=ProductUi.dp(this,7); root.addView(agentSelector,sp);
        rebuildAgentSpinner();
        agentSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){ if(loadingProfile)return; applySelectedAgent(position,true); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });

        Button call=ProductUi.primary(this,"Iniciar chamada com IA"); call.setOnClickListener(v->prepareAndCall());
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,ProductUi.dp(this,64)); cp.topMargin=ProductUi.dp(this,16); root.addView(call,cp);

        TextView tip=ProductUi.text(this,"AUTO · a IA prepara e envia respostas pelo fluxo Samsung Text Call configurado neste dispositivo.",12,ProductUi.MUTED,false);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2); tp.topMargin=ProductUi.dp(this,10); tip.setLayoutParams(tp); root.addView(tip);

        adminConfig=new LinearLayout(this); adminConfig.setOrientation(LinearLayout.VERTICAL); adminConfig.setVisibility(View.GONE);
        adminConfig.addView(ProductUi.section(this,"CONFIGURAÇÃO DO AGENTE · ADMIN"));
        agent=ProductUi.field(this,"Nome do agente"); brand=ProductUi.field(this,"Marca / empresa"); objective=ProductUi.field(this,"Objetivo da chamada"); script=ProductUi.field(this,"Script / regras comerciais"); script.setMinLines(3); opening=ProductUi.field(this,"Frase de abertura");
        adminConfig.addView(agent); adminConfig.addView(brand); adminConfig.addView(objective); adminConfig.addView(script); adminConfig.addView(opening);
        autoTextCall=new Switch(this); autoTextCall.setText("Entrar automaticamente em Samsung Text Call"); autoTextCall.setTextColor(Color.WHITE); autoTextCall.setTextSize(14); autoTextCall.setPadding(0,ProductUi.dp(this,12),0,ProductUi.dp(this,4)); adminConfig.addView(autoTextCall);
        Button save=ProductUi.secondary(this,"Guardar configuração neste dispositivo"); save.setOnClickListener(v->{saveProfile();Toast.makeText(this,"Configuração guardada",Toast.LENGTH_SHORT).show();}); adminConfig.addView(save,ProductUi.buttonParams(this));
        Button sync=ProductUi.secondary(this,"Sincronizar SD Dialer + MyPoupar"); sync.setOnClickListener(v->refreshBrains()); adminConfig.addView(sync,ProductUi.buttonParams(this));
        root.addView(adminConfig);

        TextView credit=ProductUi.text(this,"MyPoupar · Feito por REBORN AI",12,ProductUi.MUTED,false); LinearLayout.LayoutParams cr=new LinearLayout.LayoutParams(-1,-2); cr.topMargin=ProductUi.dp(this,22); credit.setLayoutParams(cr); root.addView(credit);
        return scroll;
    }

    private void rebuildAgentSpinner(){
        ArrayList<String> labels=new ArrayList<>();
        for(String x:RebornAgentCatalog.labels())labels.add(x);
        for(RemoteAgent r:remoteAgents)labels.add(r.name+" · Personalizado");
        loadingProfile=true;
        agentSelector.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        loadingProfile=false;
    }

    private void loadRemoteAgents(){
        RebornAdminClient.call(this,"list_agents",new JSONObject(),new RebornAdminClient.Callback(){
            @Override public void onSuccess(JSONObject data){
                remoteAgents.clear(); JSONArray a=data.optJSONArray("agents");
                if(a!=null)for(int i=0;i<a.length();i++){
                    JSONObject x=a.optJSONObject(i); if(x==null||!x.optBoolean("active",true))continue;
                    RemoteAgent r=new RemoteAgent(); r.id=x.optString("id",""); r.name=x.optString("name","Agente"); r.personality=x.optString("personality","consultivo, natural e profissional"); r.systemPrompt=x.optString("system_prompt","");
                    JSONObject rr=x.optJSONObject("response_rules"); if(rr!=null){r.objective=rr.optString("objective","");r.opening=rr.optString("opening","");r.script=rr.optString("script","");}
                    remoteAgents.add(r);
                }
                rebuildAgentSpinner(); selectSavedProfile();
            }
            @Override public void onError(String message){ selectSavedProfile(); }
        });
    }

    private void selectSavedProfile(){
        loadingProfile=true;
        int index=RebornAgentCatalog.indexFor(savedProfileId);
        if(savedProfileId.startsWith("remote:")){
            String id=savedProfileId.substring(7); int base=RebornAgentCatalog.size();
            for(int i=0;i<remoteAgents.size();i++)if(id.equals(remoteAgents.get(i).id)){index=base+i;break;}
        }
        if(index>=0&&index<agentSelector.getCount())agentSelector.setSelection(index,false);
        loadingProfile=false;
        applySelectedAgent(index,false);
    }

    private void loadProfile(){
        loadingProfile=true;
        phone.setText(control.getString("dial_phone",""));
        int index=RebornAgentCatalog.indexFor(savedProfileId);
        RebornAgentCatalog.AgentProfile base=RebornAgentCatalog.at(index);
        if(agent!=null){agent.setText(control.getString("agent_name",base.name));brand.setText(control.getString("agent_brand",base.brand));objective.setText(control.getString("agent_objective",base.objective));script.setText(control.getString("active_script",base.script));opening.setText(control.getString("agent_opening",base.opening));autoTextCall.setChecked(control.getBoolean("auto_text_call",true));}
        loadingProfile=false;
        selectSavedProfile();
    }

    private void loadRole(){
        RebornAdminClient.call(this,"me",new JSONObject(),new RebornAdminClient.Callback(){
            @Override public void onSuccess(JSONObject data){JSONObject p=data.optJSONObject("profile");if(p==null)return;boolean admin=p.optBoolean("is_super_admin",false)||"admin".equals(p.optString("role"));adminConfig.setVisibility(admin?View.VISIBLE:View.GONE);}
            @Override public void onError(String message){adminConfig.setVisibility(View.GONE);}
        });
    }

    private void applySelectedAgent(int position,boolean persist){
        if(position<0)return;
        if(position<RebornAgentCatalog.size()){
            RebornAgentCatalog.AgentProfile p=RebornAgentCatalog.at(position);
            setFields(p.name,p.brand,p.objective,p.script,p.opening);
            SofiaAgentProfile.configure(p.name,p.brand,p.tone,p.objective,p.script,p.opening);
            if(persist)control.edit().putString("agent_profile_id",p.id).putString("agent_name",p.name).putString("agent_brand",p.brand).putString("agent_objective",p.objective).putString("active_script",p.script).putString("agent_opening",p.opening).apply();
            return;
        }
        int ri=position-RebornAgentCatalog.size(); if(ri<0||ri>=remoteAgents.size())return;
        RemoteAgent r=remoteAgents.get(ri); String obj=r.objective==null?"":r.objective; String sc=r.script==null||r.script.isEmpty()?r.systemPrompt:r.script; String op=r.opening==null?"":r.opening;
        setFields(r.name,"MyPoupar",obj,sc,op); SofiaAgentProfile.configure(r.name,"MyPoupar",r.personality,obj,sc,op);
        if(persist)control.edit().putString("agent_profile_id","remote:"+r.id).putString("agent_name",r.name).putString("agent_brand","MyPoupar").putString("agent_objective",obj).putString("active_script",sc).putString("agent_opening",op).apply();
    }

    private void setFields(String n,String b,String o,String s,String op){if(agent==null)return;agent.setText(n);brand.setText(b);objective.setText(o);script.setText(s);opening.setText(op);}

    private void saveProfile(){
        int pos=agentSelector.getSelectedItemPosition(); String id;
        if(pos<RebornAgentCatalog.size())id=RebornAgentCatalog.at(pos).id; else {int ri=pos-RebornAgentCatalog.size();id=ri>=0&&ri<remoteAgents.size()?"remote:"+remoteAgents.get(ri).id:"sofia_sales";}
        control.edit().putString("dial_phone",val(phone)).putString("agent_profile_id",id).putString("agent_name",val(agent)).putString("agent_brand",val(brand)).putString("agent_objective",val(objective)).putString("active_script",val(script)).putString("agent_opening",val(opening)).putString("sd_company_id",SdDialerBrainClient.DEFAULT_COMPANY_ID).putBoolean("auto_text_call",autoTextCall==null||autoTextCall.isChecked()).putString("mode","AUTO").apply();
        applyRuntimeProfile();
    }

    private void applyRuntimeProfile(){int pos=agentSelector==null?0:agentSelector.getSelectedItemPosition();if(pos<RebornAgentCatalog.size()){RebornAgentCatalog.AgentProfile p=RebornAgentCatalog.at(pos);SofiaAgentProfile.configure(val(agent),val(brand),p.tone,val(objective),val(script),val(opening));}else{int ri=pos-RebornAgentCatalog.size();String tone=ri>=0&&ri<remoteAgents.size()?remoteAgents.get(ri).personality:"consultivo, natural e profissional";SofiaAgentProfile.configure(val(agent),val(brand),tone,val(objective),val(script),val(opening));}}

    private void prepareAndCall(){
        saveProfile();
        if(isThisAppDefaultDialer()){Toast.makeText(this,"Escolhe Telefone Samsung como app de telefone predefinida e volta ao REBORN AI.",Toast.LENGTH_LONG).show();try{startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));}catch(Throwable t){startActivity(new Intent(Settings.ACTION_SETTINGS));}return;}
        if(!isAccessibilityDriverEnabled()){Toast.makeText(this,"Ativa REBORN AI/SOFIA em Acessibilidade para usar o Samsung Text Call automaticamente.",Toast.LENGTH_LONG).show();try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(Throwable t){startActivity(new Intent(Settings.ACTION_SETTINGS));}return;}
        refreshBrains(); String number=val(phone).replace(" ",""); if(number.isEmpty()){phone.setError("Introduz um número");return;}
        control.edit().putString("dial_phone",number).putLong("call_started_at",System.currentTimeMillis()).putBoolean("call_final_synced",false).apply();
        if(checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CALL_PHONE},REQ_CALL);return;} startCall(number);
    }

    private boolean isThisAppDefaultDialer(){try{TelecomManager tm=(TelecomManager)getSystemService(TELECOM_SERVICE);String current=tm==null?null:tm.getDefaultDialerPackage();return current!=null&&current.equals(getPackageName());}catch(Throwable ignored){return false;}}
    private boolean isAccessibilityDriverEnabled(){try{String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);if(enabled==null||enabled.trim().isEmpty())return false;String pkg=getPackageName().toLowerCase();String cls=SofiaOverlayAccessibilityService.class.getName().toLowerCase();for(String s:enabled.split(":")){String n=s==null?"":s.toLowerCase();if(n.contains(pkg)&&(n.contains("sofiaoverlayaccessibilityservice")||n.contains(cls)))return true;}return false;}catch(Throwable ignored){return false;}}
    private void startCall(String number){refreshBrains();control.edit().putLong("auto_text_call_armed_at",System.currentTimeMillis()).apply();startActivity(new Intent(Intent.ACTION_CALL,Uri.parse("tel:"+Uri.encode(number))));}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQ_CALL&&grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED){String n=val(phone).replace(" ","");if(!n.isEmpty())startCall(n);}}
    private String val(EditText e){return e==null||e.getText()==null?"":e.getText().toString().trim();}
}
