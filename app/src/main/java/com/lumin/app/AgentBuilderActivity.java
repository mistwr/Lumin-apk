package com.lumin.app;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;

public class AgentBuilderActivity extends AppCompatActivity {
    private LinearLayout list;
    private EditText name, personality, objective, opening, script, knowledge;
    private TextView state;

    @Override protected void onCreate(Bundle savedInstanceState){ super.onCreate(savedInstanceState); setContentView(buildUi()); loadAgents(); }

    private View buildUi(){
        ScrollView s=new ScrollView(this); s.setBackgroundColor(ProductUi.BG); s.setFillViewport(true);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(ProductUi.dp(this,20),ProductUi.dp(this,28),ProductUi.dp(this,20),ProductUi.dp(this,40)); s.addView(root);
        root.addView(ProductUi.text(this,"ASSISTENTES IA",12,ProductUi.ACCENT,true));
        root.addView(ProductUi.text(this,"Criar agente",32,Color.WHITE,true));
        TextView intro=ProductUi.text(this,"Define como o assistente fala, o que procura descobrir e que conhecimento deve usar. O agente fica disponível à empresa no SD Dialer.",14,ProductUi.SOFT,false); intro.setPadding(0,ProductUi.dp(this,5),0,ProductUi.dp(this,14)); root.addView(intro);

        root.addView(ProductUi.section(this,"AGENTES DA EMPRESA"));
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list);
        state=ProductUi.text(this,"A carregar…",13,ProductUi.MUTED,false); root.addView(state);

        root.addView(ProductUi.section(this,"NOVO ASSISTENTE"));
        name=ProductUi.field(this,"Nome do agente · ex: SOFIA Empresas");
        personality=ProductUi.field(this,"Personalidade / tom · ex: consultivo, natural, direto");
        objective=ProductUi.field(this,"Objetivo · ex: qualificar e aquecer leads");
        opening=ProductUi.field(this,"Abertura do agente");
        script=ProductUi.field(this,"Script / regras comerciais"); script.setMinLines(3);
        knowledge=ProductUi.field(this,"Conhecimento · ex: MEO, energia, campanhas MyPoupar, objeções"); knowledge.setMinLines(2);
        root.addView(name); root.addView(personality); root.addView(objective); root.addView(opening); root.addView(script); root.addView(knowledge);
        Button create=ProductUi.primary(this,"Criar assistente IA"); create.setOnClickListener(v->createAgent()); root.addView(create,ProductUi.buttonParams(this));
        return s;
    }

    private void loadAgents(){
        state.setText("A carregar agentes…");
        RebornAdminClient.call(this,"list_agents",new JSONObject(),new RebornAdminClient.Callback(){
            @Override public void onSuccess(JSONObject data){
                list.removeAllViews(); JSONArray a=data.optJSONArray("agents");
                if(a==null||a.length()==0){ state.setText("Ainda não existem agentes personalizados."); return; }
                state.setText(a.length()+" agente(s)");
                for(int i=0;i<a.length();i++){
                    JSONObject x=a.optJSONObject(i); if(x==null)continue;
                    LinearLayout c=ProductUi.card(AgentBuilderActivity.this);
                    c.addView(ProductUi.text(AgentBuilderActivity.this,(x.optBoolean("active",true)?"● ":"○ ")+x.optString("name","Agente"),17,Color.WHITE,true));
                    c.addView(ProductUi.text(AgentBuilderActivity.this,x.optString("personality","Sem personalidade definida"),13,ProductUi.MUTED,false));
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=ProductUi.dp(AgentBuilderActivity.this,8); list.addView(c,p);
                }
            }
            @Override public void onError(String message){ state.setText("Não foi possível carregar: "+message); }
        });
    }

    private void createAgent(){
        String n=val(name); if(n.isEmpty()){name.setError("Dá um nome ao agente");return;}
        try{
            JSONObject rules=new JSONObject(); rules.put("objective",val(objective)); rules.put("opening",val(opening)); rules.put("script",val(script));
            JSONObject scope=new JSONObject(); scope.put("description",val(knowledge)); scope.put("sd_dialer",true); scope.put("mypoupar",true);
            JSONObject body=new JSONObject();
            body.put("name",n); body.put("language","pt-PT"); body.put("personality",val(personality)); body.put("identify_as_ai",true); body.put("active",true);
            body.put("system_prompt","Objetivo: "+val(objective)+"\nAbertura: "+val(opening)+"\nRegras: "+val(script)); body.put("knowledge_scope",scope); body.put("response_rules",rules);
            state.setText("A criar assistente…");
            RebornAdminClient.call(this,"create_agent",body,new RebornAdminClient.Callback(){
                @Override public void onSuccess(JSONObject data){ Toast.makeText(AgentBuilderActivity.this,"Assistente criado no SD Dialer",Toast.LENGTH_SHORT).show(); clear(); loadAgents(); }
                @Override public void onError(String message){ state.setText("Erro: "+message); }
            });
        }catch(Exception e){ state.setText("Erro ao preparar agente."); }
    }

    private void clear(){ name.setText(""); personality.setText(""); objective.setText(""); opening.setText(""); script.setText(""); knowledge.setText(""); }
    private String val(EditText e){return e.getText()==null?"":e.getText().toString().trim();}
}
