package com.lumin.app;

import android.content.Intent;
import android.content.SharedPreferences;
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

/** REBORN Agent Studio. It manages agents only; the stable Samsung call engine is untouched. */
public class AgentBuilderActivity extends AppCompatActivity {
    private LinearLayout list, editor;
    private EditText name, personality, objective, opening, script, knowledge;
    private TextView state, editorTitle;
    private Button saveButton;
    private String editingId = "";

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadAgents();
    }

    private View buildUi(){
        ScrollView s=new ScrollView(this); s.setBackgroundColor(ProductUi.BG); s.setFillViewport(true);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ProductUi.dp(this,20),ProductUi.dp(this,28),ProductUi.dp(this,20),ProductUi.dp(this,40)); s.addView(root);

        LinearLayout brand=ProductUi.card(this);
        LinearLayout titleRow=new LinearLayout(this); titleRow.setOrientation(LinearLayout.HORIZONTAL); titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView mark=ProductUi.text(this,"R",24,ProductUi.ACCENT,true); mark.setGravity(android.view.Gravity.CENTER); mark.setBackground(ProductUi.round(this,Color.rgb(15,42,41),18));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(ProductUi.dp(this,52),ProductUi.dp(this,52)); titleRow.addView(mark,mp);
        LinearLayout words=new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL); words.setPadding(ProductUi.dp(this,12),0,0,0);
        words.addView(ProductUi.text(this,"REBORN AI",12,ProductUi.ACCENT,true)); words.addView(ProductUi.text(this,"Agent Studio",28,Color.WHITE,true));
        titleRow.addView(words,new LinearLayout.LayoutParams(0,-2,1f)); brand.addView(titleRow);
        TextView intro=ProductUi.text(this,"Cria assistentes especializados e usa-os diretamente nas chamadas REBORN. O motor Samsung existente não é alterado.",14,ProductUi.SOFT,false);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);ip.topMargin=ProductUi.dp(this,10);intro.setLayoutParams(ip);brand.addView(intro);root.addView(brand);

        root.addView(ProductUi.section(this,"AGENTES DA EMPRESA"));
        Button newAgent=ProductUi.primary(this,"＋ Criar novo agente"); newAgent.setOnClickListener(v->showNewEditor()); root.addView(newAgent,ProductUi.buttonParams(this));
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=ProductUi.dp(this,10);list.setLayoutParams(lp);root.addView(list);
        state=ProductUi.text(this,"A carregar…",13,ProductUi.MUTED,false); LinearLayout.LayoutParams stp=new LinearLayout.LayoutParams(-1,-2);stp.topMargin=ProductUi.dp(this,10);state.setLayoutParams(stp);root.addView(state);

        editor=new LinearLayout(this); editor.setOrientation(LinearLayout.VERTICAL); editor.setVisibility(View.GONE);
        editor.addView(ProductUi.section(this,"EDITOR DO AGENTE"));
        editorTitle=ProductUi.text(this,"Novo assistente",24,Color.WHITE,true);editor.addView(editorTitle);
        TextView hint=ProductUi.text(this,"Pensa no agente como um colaborador: define função, maneira de falar, objetivo, abertura e conhecimento.",13,ProductUi.MUTED,false);hint.setPadding(0,ProductUi.dp(this,4),0,ProductUi.dp(this,8));editor.addView(hint);

        LinearLayout presets=new LinearLayout(this); presets.setOrientation(LinearLayout.HORIZONTAL); presets.setWeightSum(3f);
        presets.addView(preset("Vendas","consultivo, natural e direto","qualificar, descobrir necessidade e conduzir a próxima ação"),weight());
        presets.addView(preset("Follow-up","próximo, breve e útil","retomar leads e descobrir se existe interesse ou próximo passo"),weight());
        presets.addView(preset("Suporte","calmo, claro e paciente","perceber a dúvida e orientar o cliente sem pressão comercial"),weight());
        editor.addView(presets);

        name=ProductUi.field(this,"Nome do agente · ex: SOFIA Empresas");
        personality=ProductUi.field(this,"Personalidade / tom · ex: consultivo, natural, direto");
        objective=ProductUi.field(this,"Função / objetivo · o que este agente deve conseguir");
        opening=ProductUi.field(this,"Abertura · primeira frase depois do fluxo Samsung");
        script=ProductUi.field(this,"Regras e método comercial"); script.setMinLines(4);
        knowledge=ProductUi.field(this,"Conhecimento · produtos, campanhas, objeções, áreas a consultar"); knowledge.setMinLines(3);
        editor.addView(name);editor.addView(personality);editor.addView(objective);editor.addView(opening);editor.addView(script);editor.addView(knowledge);
        saveButton=ProductUi.primary(this,"Criar assistente IA");saveButton.setOnClickListener(v->saveAgent());editor.addView(saveButton,ProductUi.buttonParams(this));
        Button cancel=ProductUi.secondary(this,"Cancelar edição");cancel.setOnClickListener(v->{clear();editor.setVisibility(View.GONE);});editor.addView(cancel,ProductUi.buttonParams(this));
        root.addView(editor);

        TextView footer=ProductUi.text(this,"Os agentes ativos aparecem automaticamente em Chamada com IA. Podes criar vários para vendas, qualificação, follow-up, energia, telecom ou outros fluxos.",12,ProductUi.MUTED,false);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.topMargin=ProductUi.dp(this,24);footer.setLayoutParams(fp);root.addView(footer);
        return s;
    }

    private Button preset(String label,String tone,String goal){Button b=ProductUi.secondary(this,label);b.setTextSize(11);b.setOnClickListener(v->{personality.setText(tone);objective.setText(goal);});return b;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ProductUi.dp(this,48),1f);p.setMargins(ProductUi.dp(this,2),0,ProductUi.dp(this,2),0);return p;}

    private void showNewEditor(){clear();editorTitle.setText("Novo assistente");saveButton.setText("Criar assistente IA");editor.setVisibility(View.VISIBLE);editor.requestFocus();}

    private void loadAgents(){
        state.setText("A carregar agentes…");
        RebornAdminClient.call(this,"list_agents",new JSONObject(),new RebornAdminClient.Callback(){
            @Override public void onSuccess(JSONObject data){
                list.removeAllViews();JSONArray a=data.optJSONArray("agents");
                if(a==null||a.length()==0){state.setText("Ainda não existem agentes personalizados. Cria o primeiro acima.");return;}
                state.setText(a.length()+" agente(s) no REBORN AI");
                for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null)addAgentCard(x);}
            }
            @Override public void onError(String message){state.setText("Não foi possível carregar agentes: "+message);}
        });
    }

    private void addAgentCard(JSONObject x){
        boolean active=x.optBoolean("active",true);
        LinearLayout c=ProductUi.card(this);
        TextView title=ProductUi.text(this,(active?"● ":"○ ")+x.optString("name","Agente"),18,Color.WHITE,true);c.addView(title);
        String tone=x.optString("personality","consultivo, natural e profissional");
        JSONObject rr=x.optJSONObject("response_rules");String goal=rr==null?"":rr.optString("objective","");
        c.addView(ProductUi.text(this,(goal.isEmpty()?"Assistente REBORN":goal),13,ProductUi.SOFT,false));
        TextView toneView=ProductUi.text(this,"Tom · "+tone+"   ·   "+(active?"ATIVO":"PAUSADO"),12,active?ProductUi.ACCENT:ProductUi.MUTED,true);LinearLayout.LayoutParams tvp=new LinearLayout.LayoutParams(-1,-2);tvp.topMargin=ProductUi.dp(this,6);toneView.setLayoutParams(tvp);c.addView(toneView);

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setWeightSum(3f);
        Button use=ProductUi.secondary(this,"Usar");use.setEnabled(active);use.setOnClickListener(v->useAgent(x));actions.addView(use,weight());
        Button edit=ProductUi.secondary(this,"Editar");edit.setOnClickListener(v->editAgent(x));actions.addView(edit,weight());
        Button toggle=ProductUi.secondary(this,active?"Pausar":"Ativar");toggle.setOnClickListener(v->toggleAgent(x,!active));actions.addView(toggle,weight());
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=ProductUi.dp(this,10);actions.setLayoutParams(ap);c.addView(actions);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=ProductUi.dp(this,8);list.addView(c,cp);
    }

    private void useAgent(JSONObject x){
        JSONObject rr=x.optJSONObject("response_rules");if(rr==null)rr=new JSONObject();
        SharedPreferences p=getSharedPreferences("sofia_control",MODE_PRIVATE);
        String sc=rr.optString("script",x.optString("system_prompt",""));
        p.edit().putString("agent_profile_id","remote:"+x.optString("id"))
                .putString("agent_name",x.optString("name","Agente"))
                .putString("agent_brand","MyPoupar")
                .putString("agent_objective",rr.optString("objective",""))
                .putString("active_script",sc)
                .putString("agent_opening",rr.optString("opening",""))
                .apply();
        Toast.makeText(this,x.optString("name","Agente")+" selecionado",Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this,SofiaAiCallingActivity.class));
    }

    private void editAgent(JSONObject x){
        editingId=x.optString("id","");name.setText(x.optString("name",""));personality.setText(x.optString("personality",""));
        JSONObject rr=x.optJSONObject("response_rules");if(rr==null)rr=new JSONObject();JSONObject ks=x.optJSONObject("knowledge_scope");if(ks==null)ks=new JSONObject();
        objective.setText(rr.optString("objective",""));opening.setText(rr.optString("opening",""));script.setText(rr.optString("script",x.optString("system_prompt","")));knowledge.setText(ks.optString("description",""));
        editorTitle.setText("Editar · "+x.optString("name","Agente"));saveButton.setText("Guardar alterações");editor.setVisibility(View.VISIBLE);editor.requestFocus();
    }

    private void toggleAgent(JSONObject x,boolean active){
        try{JSONObject body=new JSONObject().put("id",x.optString("id")).put("active",active);state.setText(active?"A ativar agente…":"A pausar agente…");
            RebornAdminClient.call(this,"update_agent",body,new RebornAdminClient.Callback(){@Override public void onSuccess(JSONObject data){loadAgents();}@Override public void onError(String message){state.setText("Erro: "+message);}});
        }catch(Exception e){state.setText("Erro ao alterar agente.");}
    }

    private void saveAgent(){
        String n=val(name);if(n.isEmpty()){name.setError("Dá um nome ao agente");return;}
        try{
            JSONObject rules=new JSONObject().put("objective",val(objective)).put("opening",val(opening)).put("script",val(script));
            JSONObject scope=new JSONObject().put("description",val(knowledge)).put("sd_dialer",true).put("mypoupar",true).put("crm",true);
            JSONObject body=new JSONObject().put("name",n).put("language","pt-PT").put("personality",val(personality)).put("identify_as_ai",true).put("active",true)
                    .put("system_prompt","Função: "+val(objective)+"\nAbertura: "+val(opening)+"\nRegras: "+val(script)).put("knowledge_scope",scope).put("response_rules",rules);
            boolean editing=!editingId.isEmpty();if(editing)body.put("id",editingId);state.setText(editing?"A guardar alterações…":"A criar assistente…");
            RebornAdminClient.call(this,editing?"update_agent":"create_agent",body,new RebornAdminClient.Callback(){
                @Override public void onSuccess(JSONObject data){Toast.makeText(AgentBuilderActivity.this,editing?"Assistente atualizado":"Assistente criado",Toast.LENGTH_SHORT).show();clear();editor.setVisibility(View.GONE);loadAgents();}
                @Override public void onError(String message){state.setText("Erro: "+message);}
            });
        }catch(Exception e){state.setText("Erro ao preparar agente.");}
    }

    private void clear(){editingId="";if(name!=null){name.setText("");personality.setText("");objective.setText("");opening.setText("");script.setText("");knowledge.setText("");}}
    private String val(EditText e){return e==null||e.getText()==null?"":e.getText().toString().trim();}
}
