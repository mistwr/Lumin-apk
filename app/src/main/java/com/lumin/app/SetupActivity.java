package com.lumin.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;

/** Premium product home. Technical setup lives under Settings. */
public class SetupActivity extends AppCompatActivity {
    private TextView profileLine, connectionBadge, callsToday, salesToday, leadsTotal, agentsActive;
    private LinearLayout adminTools;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProductUi.applyWindow(this);
        if (!RebornAuthClient.isSignedIn(this)) { startActivity(new Intent(this,LoginActivity.class)); finish(); return; }
        setContentView(buildUi());
        loadProfile(); loadDashboard();
        LocalQwenManager.warmUpAsync(this);
        SdDialerBrainClient.refreshAsync(this);
        new Thread(() -> SupabaseSyncClient.flush(this),"reborn-sync-flush").start();
    }

    @Override protected void onResume() { super.onResume(); if(callsToday!=null) loadDashboard(); }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ProductUi.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ProductUi.dp(this,20),ProductUi.dp(this,24),ProductUi.dp(this,20),ProductUi.dp(this,42));
        scroll.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(ProductUi.eyebrow(this,"REBORN AI"));
        brand.addView(ProductUi.text(this,"Calling Intelligence",29,ProductUi.TEXT,true));
        top.addView(brand,new LinearLayout.LayoutParams(0,-2,1f));
        connectionBadge = ProductUi.badge(this,"● LIVE",true);
        top.addView(connectionBadge);
        root.addView(top);

        TextView sub = ProductUi.text(this,"A operação comercial, os agentes e as chamadas num único workspace.",14,ProductUi.MUTED,false);
        LinearLayout.LayoutParams subp = new LinearLayout.LayoutParams(-1,-2); subp.topMargin=ProductUi.dp(this,8); subp.bottomMargin=ProductUi.dp(this,20); sub.setLayoutParams(subp); root.addView(sub);

        LinearLayout hero = ProductUi.heroCard(this);
        TextView workspace = ProductUi.text(this,"WORKSPACE",10,ProductUi.MUTED,true); workspace.setLetterSpacing(0.11f); hero.addView(workspace);
        profileLine = ProductUi.text(this,"A validar sessão…",17,ProductUi.TEXT,true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1,-2); plp.topMargin=ProductUi.dp(this,8); profileLine.setLayoutParams(plp); hero.addView(profileLine);
        TextView pitch = ProductUi.text(this,"REBORN Brain ligado ao SD Dialer, histórico, follow-ups e inteligência comercial.",14,ProductUi.SOFT,false);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1,-2); pp.topMargin=ProductUi.dp(this,7); pitch.setLayoutParams(pp); hero.addView(pitch);
        root.addView(hero);

        root.addView(ProductUi.section(this,"Visão de hoje"));
        LinearLayout row1 = metricRow();
        callsToday = addMetric(row1,"0","Chamadas","↗ atividade");
        salesToday = addMetric(row1,"0","Vendas","resultado");
        root.addView(row1);
        LinearLayout row2 = metricRow();
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(-1,-2); r2p.topMargin=ProductUi.dp(this,10); row2.setLayoutParams(r2p);
        leadsTotal = addMetric(row2,"—","Leads","base ativa");
        agentsActive = addMetric(row2,"—","Agentes IA","disponíveis");
        root.addView(row2);

        root.addView(ProductUi.section(this,"Ação principal"));
        LinearLayout actionCard = ProductUi.heroCard(this);
        actionCard.addView(ProductUi.text(this,"Faz a próxima chamada com contexto, memória e assistência em tempo real.",16,ProductUi.TEXT,true));
        TextView actionSub = ProductUi.text(this,"Escolhe o agente, introduz o contacto e inicia.",13,ProductUi.MUTED,false);
        LinearLayout.LayoutParams asp = new LinearLayout.LayoutParams(-1,-2); asp.topMargin=ProductUi.dp(this,5); actionSub.setLayoutParams(asp); actionCard.addView(actionSub);
        Button call = ProductUi.primary(this,"Iniciar chamada com IA  →");
        call.setOnClickListener(v->startActivity(new Intent(this,SofiaAiCallingActivity.class)));
        actionCard.addView(call,ProductUi.buttonParams(this));
        root.addView(actionCard);

        root.addView(ProductUi.section(this,"Operação"));
        Button leads = ProductUi.secondary(this,"Leads e oportunidades                         →");
        leads.setOnClickListener(v->openRecords("leads")); root.addView(leads,ProductUi.buttonParams(this));
        Button history = ProductUi.secondary(this,"Histórico de chamadas                         →");
        history.setOnClickListener(v->openRecords("history")); root.addView(history,ProductUi.buttonParams(this));

        adminTools = new LinearLayout(this);
        adminTools.setOrientation(LinearLayout.VERTICAL); adminTools.setVisibility(View.GONE);
        adminTools.addView(ProductUi.section(this,"Gestão"));
        Button agents = ProductUi.secondary(this,"Agent Studio                                      →");
        agents.setOnClickListener(v->startActivity(new Intent(this,AgentBuilderActivity.class))); adminTools.addView(agents,ProductUi.buttonParams(this));
        Button users = ProductUi.secondary(this,"Utilizadores e equipas                         →");
        users.setOnClickListener(v->startActivity(new Intent(this,UsersAdminActivity.class))); adminTools.addView(users,ProductUi.buttonParams(this));
        root.addView(adminTools);

        root.addView(ProductUi.section(this,"Sistema"));
        Button settings = ProductUi.secondary(this,"Definições e ligações                         →");
        settings.setOnClickListener(v->startActivity(new Intent(this,ProductSettingsActivity.class))); root.addView(settings,ProductUi.buttonParams(this));

        TextView footer = ProductUi.text(this,"MyPoupar · Feito por REBORN AI",11,ProductUi.MUTED,false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1,-2); fp.topMargin=ProductUi.dp(this,28); footer.setLayoutParams(fp); root.addView(footer);
        return scroll;
    }

    private LinearLayout metricRow(){ LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setWeightSum(2f); return row; }

    private TextView addMetric(LinearLayout row,String value,String label,String detail){
        LinearLayout card=ProductUi.card(this);
        TextView lab=ProductUi.text(this,label.toUpperCase(),10,ProductUi.MUTED,true); lab.setLetterSpacing(0.08f); card.addView(lab);
        TextView val=ProductUi.text(this,value,30,ProductUi.TEXT,true); LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,-2); vp.topMargin=ProductUi.dp(this,8); val.setLayoutParams(vp); card.addView(val);
        card.addView(ProductUi.text(this,detail,11,ProductUi.SOFT,false));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1f); p.setMargins(ProductUi.dp(this,4),0,ProductUi.dp(this,4),0); row.addView(card,p); return val;
    }

    private void loadProfile(){
        RebornAdminClient.call(this,"me",new JSONObject(),new RebornAdminClient.Callback(){
            @Override public void onSuccess(JSONObject data){
                JSONObject p=data.optJSONObject("profile"); if(p==null)return;
                String name=p.optString("full_name",p.optString("email","Utilizador")); String role=p.optString("role",""); boolean superAdmin=p.optBoolean("is_super_admin",false);
                boolean linked=SdDialerBrainClient.isConnected(SetupActivity.this);
                profileLine.setText(name+"\n"+(superAdmin?"Super Admin":pretty(role)));
                connectionBadge.setText(linked?"● CONNECTED":"◌ SYNC");
                adminTools.setVisibility(superAdmin||"admin".equals(role)?View.VISIBLE:View.GONE);
            }
            @Override public void onError(String message){ profileLine.setText("Sessão precisa de atenção"); connectionBadge.setText("○ OFFLINE"); }
        });
    }

    private String pretty(String role){ if(role==null||role.isEmpty())return "Utilizador"; return role.substring(0,1).toUpperCase()+role.substring(1).toLowerCase(); }

    private void loadDashboard(){
        if(callsToday==null)return;
        RebornAdminClient.call(this,"dashboard",new JSONObject(),new RebornAdminClient.Callback(){
            @Override public void onSuccess(JSONObject data){ JSONObject m=data.optJSONObject("metrics"); if(m==null)return; callsToday.setText(String.valueOf(m.optInt("calls_today",0))); salesToday.setText(String.valueOf(m.optInt("sales_today",0))); leadsTotal.setText(String.valueOf(m.optInt("leads",0))); agentsActive.setText(String.valueOf(m.optInt("agents_active",0))); }
            @Override public void onError(String message){}
        });
    }
    private void openRecords(String mode){Intent i=new Intent(this,RecordsActivity.class);i.putExtra("mode",mode);startActivity(i);}
}
