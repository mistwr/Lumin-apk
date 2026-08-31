package com.lumin.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;

/** Product home. Technical setup now lives under Settings. */
public class SetupActivity extends AppCompatActivity {
    private TextView profileLine, callsToday, salesToday, leadsTotal, agentsActive;
    private LinearLayout adminTools;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RebornAuthClient.isSignedIn(this)) { startActivity(new Intent(this,LoginActivity.class)); finish(); return; }
        setContentView(buildUi());
        loadProfile(); loadDashboard();
        LocalQwenManager.warmUpAsync(this);
        SdDialerBrainClient.refreshAsync(this);
        new Thread(() -> SupabaseSyncClient.flush(this),"reborn-sync-flush").start();
    }

    @Override protected void onResume() { super.onResume(); if(callsToday!=null) loadDashboard(); }

    private View buildUi() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(ProductUi.BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(ProductUi.dp(this,20),ProductUi.dp(this,28),ProductUi.dp(this,20),ProductUi.dp(this,40));scroll.addView(root);
        root.addView(ProductUi.text(this,"REBORN AI",12,ProductUi.ACCENT,true));root.addView(ProductUi.text(this,"Calling Intelligence",34,Color.WHITE,true));
        TextView sub=ProductUi.text(this,"MyPoupar · powered by REBORN AI · SD Dialer + CRM",14,ProductUi.MUTED,false);sub.setPadding(0,ProductUi.dp(this,4),0,ProductUi.dp(this,16));root.addView(sub);
        LinearLayout hero=ProductUi.card(this);profileLine=ProductUi.text(this,"A validar sessão e SD Dialer…",14,ProductUi.ACCENT,true);hero.addView(profileLine);
        TextView pitch=ProductUi.text(this,"Leads, chamadas, follow-ups, histórico, scripts e assistentes de voz num só fluxo comercial.",16,ProductUi.TEXT,false);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.topMargin=ProductUi.dp(this,8);pitch.setLayoutParams(pp);hero.addView(pitch);root.addView(hero);
        root.addView(ProductUi.section(this,"HOJE"));LinearLayout metrics=new LinearLayout(this);metrics.setOrientation(LinearLayout.HORIZONTAL);metrics.setWeightSum(2f);callsToday=metric(metrics,"0","Chamadas");salesToday=metric(metrics,"0","Vendas");root.addView(metrics);
        LinearLayout metrics2=new LinearLayout(this);metrics2.setOrientation(LinearLayout.HORIZONTAL);metrics2.setWeightSum(2f);leadsTotal=metric(metrics2,"—","Leads");agentsActive=metric(metrics2,"—","Agentes IA");LinearLayout.LayoutParams m2=new LinearLayout.LayoutParams(-1,-2);m2.topMargin=ProductUi.dp(this,8);metrics2.setLayoutParams(m2);root.addView(metrics2);
        root.addView(ProductUi.section(this,"OPERAÇÃO"));android.widget.Button call=ProductUi.primary(this,"Iniciar chamada com IA");call.setOnClickListener(v->startActivity(new Intent(this,SofiaAiCallingActivity.class)));root.addView(call,ProductUi.buttonParams(this));
        android.widget.Button leads=ProductUi.secondary(this,"Leads");leads.setOnClickListener(v->openRecords("leads"));root.addView(leads,ProductUi.buttonParams(this));android.widget.Button history=ProductUi.secondary(this,"Histórico de chamadas");history.setOnClickListener(v->openRecords("history"));root.addView(history,ProductUi.buttonParams(this));
        adminTools=new LinearLayout(this);adminTools.setOrientation(LinearLayout.VERTICAL);adminTools.setVisibility(View.GONE);adminTools.addView(ProductUi.section(this,"GESTÃO"));android.widget.Button agents=ProductUi.secondary(this,"Assistentes IA");agents.setOnClickListener(v->startActivity(new Intent(this,AgentBuilderActivity.class)));adminTools.addView(agents,ProductUi.buttonParams(this));android.widget.Button users=ProductUi.secondary(this,"Utilizadores e equipas");users.setOnClickListener(v->startActivity(new Intent(this,UsersAdminActivity.class)));adminTools.addView(users,ProductUi.buttonParams(this));root.addView(adminTools);
        root.addView(ProductUi.section(this,"SISTEMA"));android.widget.Button settings=ProductUi.secondary(this,"Definições e ligações");settings.setOnClickListener(v->startActivity(new Intent(this,ProductSettingsActivity.class)));root.addView(settings,ProductUi.buttonParams(this));
        TextView footer=ProductUi.text(this,"Sessão autenticada. Os dados da chamada são sincronizados com o SD Dialer quando a ligação está ativa.",12,ProductUi.MUTED,false);LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.topMargin=ProductUi.dp(this,20);footer.setLayoutParams(fp);root.addView(footer);return scroll;
    }

    private TextView metric(LinearLayout row,String value,String label){LinearLayout c=ProductUi.card(this);TextView v=ProductUi.text(this,value,26,Color.WHITE,true);c.addView(v);c.addView(ProductUi.text(this,label,12,ProductUi.MUTED,false));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1f);p.setMargins(ProductUi.dp(this,3),0,ProductUi.dp(this,3),0);row.addView(c,p);return v;}

    private void loadProfile(){RebornAdminClient.call(this,"me",new JSONObject(),new RebornAdminClient.Callback(){@Override public void onSuccess(JSONObject data){JSONObject p=data.optJSONObject("profile");if(p==null)return;String name=p.optString("full_name",p.optString("email","Utilizador"));String role=p.optString("role","");boolean superAdmin=p.optBoolean("is_super_admin",false);profileLine.setText("● "+name+" · "+(superAdmin?"SUPER ADMIN":role.toUpperCase())+" · "+(SdDialerBrainClient.isConnected(SetupActivity.this)?"SD DIALER LIGADO":"A SINCRONIZAR"));adminTools.setVisibility(superAdmin||"admin".equals(role)?View.VISIBLE:View.GONE);}@Override public void onError(String message){profileLine.setText("○ Sessão inválida · toca em Definições");}});}
    private void loadDashboard(){if(callsToday==null)return;RebornAdminClient.call(this,"dashboard",new JSONObject(),new RebornAdminClient.Callback(){@Override public void onSuccess(JSONObject data){JSONObject m=data.optJSONObject("metrics");if(m==null)return;callsToday.setText(String.valueOf(m.optInt("calls_today",0)));salesToday.setText(String.valueOf(m.optInt("sales_today",0)));leadsTotal.setText(String.valueOf(m.optInt("leads",0)));agentsActive.setText(String.valueOf(m.optInt("agents_active",0)));}@Override public void onError(String message){}});}
    private void openRecords(String mode){Intent i=new Intent(this,RecordsActivity.class);i.putExtra("mode",mode);startActivity(i);}
}
