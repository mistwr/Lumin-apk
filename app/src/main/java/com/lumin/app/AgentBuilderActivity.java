package com.lumin.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
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
        ProductUi.applyWindow(this);
        setContentView(buildUi());
        loadAgents();
    }

    private View buildUi(){
        ScrollView s=new ScrollView(this);s.setBackgroundColor(ProductUi.BG);s.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(ProductUi.dp(this,20),ProductUi.dp(this,24),ProductUi.dp(this,20),ProductUi.dp(this,42));s.addView(root);

        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hText=new LinearLayout(this);hText.setOrientation(LinearLayout.VERTICAL);hText.addView(ProductUi.eyebrow(this,"REBORN AI"));hText.addView(ProductUi.text(this,"Agent Studio",30,ProductUi.TEXT,true));header.addView(hText,new LinearLayout.LayoutParams(0,-2,1f));header.addView(ProductUi.badge(this,"CREATE",true));root.addView(header);
        TextView intro=ProductUi.text(this,"Desenha agentes especializados, define comportamento e coloca-os diretamente em produção.",14,ProductUi.MUTED,false);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);ip.topMargin=ProductUi.dp(this,8);ip.bottomMargin=ProductUi.dp(this,20);intro.setLayoutParams(ip);root.addView(intro);

        LinearLayout hero=ProductUi.heroCard(this);hero.addView(ProductUi.text(this,"Agentes como colaboradores digitais",17,ProductUi.TEXT,true));TextView hsub=ProductUi.text(this,"Cada agente pode ter função, tom, abertura, regras e conhecimento próprios.",13,ProductUi.SOFT,false);LinearLayout.LayoutParams hsp=new LinearLayout.LayoutParams(-1,-2);hsp.topMargin=ProductUi.dp(this,6);hsub.setLayoutParams(hsp);hero.addView(hsub);Button newAgent=ProductUi.primary(this,"＋ Criar novo agente");newAgent.setOnClickListener(v->showNewEditor());hero.addView(newAgent,ProductUi.buttonParams(this));root.addView(hero);

        root.addView(ProductUi.section(this,"Agentes da empresa"));
        state=ProductUi.text(this,"A carregar…",13,ProductUi.MUTED,false);root.addView(state);
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=ProductUi.dp(this,8);list.setLayoutParams(lp);root.addView(list);

        editor=new LinearLayout(this);editor.setOrientation(LinearLayout.VERTICAL);editor.setVisibility(View.GONE);editor.addView(ProductUi.section(this,"Editor do agente"));
        LinearLayout editorCard=ProductUi.heroCard(this);editorTitle=ProductUi.text(this,"Novo assistente",23,ProductUi.TEXT,true);editorCard.addView(editorTitle);
        TextView hint=ProductUi.text(this,"Define o agente como definirias um novo membro da equipa.",13,ProductUi.MUTED,false);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=ProductUi.dp(this,5);hp.bottomMargin=ProductUi.dp(this,12);hint.setLayoutParams(hp);editorCard.addView(hint);

        LinearLayout presets=new LinearLayout(this);presets.setOrientation(LinearLayout.HORIZONTAL);presets.setWeightSum(3f);presets.addView(preset("Vendas","consultivo, natural e direto","qualificar, descobrir necessidade e conduzir a próxima ação"),weight());presets.addView(preset("Follow-up","próximo, breve e útil","retomar leads e descobrir se existe interesse ou próximo passo"),weight());presets.addView(preset("Suporte","calmo, claro e paciente","perceber a dúvida e orientar o cliente sem pressão comercial"),weight());editorCard.addView(presets);

        name=ProductUi.field(this,"Nome do agente · ex: SOFIA Empresas");personality=ProductUi.field(this,"Personalidade / tom");objective=ProductUi.field(this,"Função / objetivo");opening=ProductUi.field(this,"Abertura · primeira frase");script=ProductUi.field(this,"Regras e método comercial");script.setSingleLine(false);script.setMinLines(4);knowledge=ProductUi.field(this,"Conhecimento · produtos, campanhas, objeções");knowledge.setSingleLine(false);knowledge.setMinLines(3);
        editorCard.addView(name);editorCard.addView(personality);editorCard.addView(objective);editorCard.addView(opening);editorCard.addView(script);editorCard.addView(knowledge);
        saveButton=ProductUi.primary(this,"Criar assistente IA  →");saveButton.setOnClickListener(v->saveAgent());editorCard.addView(saveButton,ProductUi.buttonParams(this));Button cancel=ProductUi.secondary(this,"Cancelar edição");cancel.setOnClickListener(v->{clear();editor.setVisibility(View.GONE);});editorCard.addView(cancel,ProductUi.buttonParams(this));editor.addView(editorCard);root.addView(editor);

        TextView footer=ProductUi.text(this,"Os agentes ativos aparecem automaticamente no ecrã Nova chamada.",11,ProductUi.MUTED,false);footer.setGravity(Gravity.CENTER);LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.topMargin=ProductUi.dp(this,26);footer.setLayoutParams(fp);root.addView(footer);
        return s;
    }

    private Button preset(String label,String tone,String goal){Button b=ProductUi.secondary(this,label);b.setTextSize(11);b.setGravity(Gravity.CENTER);b.setOnClickListener(v->{personality.setText(tone);objective.setText(goal);});return b;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ProductUi.dp(this,46),1f);p.setMargins(ProductUi.dp(this,2),0,ProductUi.dp(this,2),0);return p;}
    private void showNewEditor(){clear();editorTitle.setText("Novo assistente");saveButton.setText("Criar assistente IA  →");editor.setVisibility(View.VISIBLE);editor.requestFocus();}

    private void loadAgents(){state.setText("A carregar agentes…");RebornAdminClient.call(this,"list_agents",new JSONObject(),new RebornAdminClient.Callback(){@Override public void onSuccess(JSONObject data){list.removeAllViews();JSONArray a=data.optJSONArray("agents");if(a==null||a.length()==0){state.setText("Ainda não existem agentes personalizados.");return;}state.setText(a.length()+" agente(s) no workspace");for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null)addAgentCard(x);}}@Override public void onError(String message){state.setText("Não foi possível carregar agentes: "+message);}});}

    private void addAgentCard(JSONObject x){boolean active=x.optBoolean("active",true);LinearLayout c=ProductUi.card(this);LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);TextView title=ProductUi.text(this,x.optString("name","Agente"),18,ProductUi.TEXT,true);head.addView(title,new LinearLayout.LayoutParams(0,-2,1f));head.addView(ProductUi.badge(this,active?"● ATIVO":"○ PAUSADO",active));c.addView(head);String tone=x.optString("personality","consultivo, natural e profissional");JSONObject rr=x.optJSONObject("response_rules");String goal=rr==null?"":rr.optString("objective","");TextView goalV=ProductUi.text(this,goal.isEmpty()?"Assistente REBORN":goal,13,ProductUi.SOFT,false);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);gp.topMargin=ProductUi.dp(this,8);goalV.setLayoutParams(gp);c.addView(goalV);TextView toneV=ProductUi.text(this,"Tom · "+tone,11,ProductUi.MUTED,false);LinearLayout.LayoutParams tvp=new LinearLayout.LayoutParams(-1,-2);tvp.topMargin=ProductUi.dp(this,5);toneV.setLayoutParams(tvp);c.addView(toneV);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setWeightSum(3f);Button use=ProductUi.secondary(this,"Usar");use.setGravity(Gravity.CENTER);use.setEnabled(active);use.setOnClickListener(v->useAgent(x));actions.addView(use,weight());Button edit=ProductUi.secondary(this,"Editar");edit.setGravity(Gravity.CENTER);edit.setOnClickListener(v->editAgent(x));actions.addView(edit,weight());Button toggle=ProductUi.secondary(this,active?"Pausar":"Ativar");toggle.setGravity(Gravity.CENTER);toggle.setOnClickListener(v->toggleAgent(x,!active));actions.addView(toggle,weight());LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=ProductUi.dp(this,12);actions.setLayoutParams(ap);c.addView(actions);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=ProductUi.dp(this,10);list.addView(c,cp);}

    private void useAgent(JSONObject x){JSONObject rr=x.optJSONObject("response_rules");if(rr==null)rr=new JSONObject();SharedPreferences p=getSharedPreferences("sofia_control",MODE_PRIVATE);String sc=rr.optString("script",x.optString("system_prompt",""));p.edit().putString("agent_profile_id","remote:"+x.optString("id")).putString("agent_name",x.optString("name","Agente")).putString("agent_brand","MyPoupar").putString("agent_objective",rr.optString("objective","")).putString("active_script",sc).putString("agent_opening",rr.optString("opening","")).apply();Toast.makeText(this,x.optString("name","Agente")+" selecionado",Toast.LENGTH_SHORT).show();startActivity(new Intent(this,SofiaAiCallingActivity.class));}

    private void editAgent(JSONObject x){editingId=x.optString("id","");name.setText(x.optString("name",""));personality.setText(x.optString("personality",""));JSONObject rr=x.optJSONObject("response_rules");if(rr==null)rr=new JSONObject();JSONObject ks=x.optJSONObject("knowledge_scope");if(ks==null)ks=new JSONObject();objective.setText(rr.optString("objective",""));opening.setText(rr.optString("opening",""));script.setText(rr.optString("script",x.optString("system_prompt","")));knowledge.setText(ks.optString("description",""));editorTitle.setText("Editar · "+x.optString("name","Agente"));saveButton.setText("Guardar alterações  →");editor.setVisibility(View.VISIBLE);editor.requestFocus();}

    private void toggleAgent(JSONObject x,boolean active){try{JSONObject body=new JSONObject().put("id",x.optString("id")).put("active",active);state.setText(active?"A ativar agente…":"A pausar agente…");RebornAdminClient.call(this,"update_agent",body,new RebornAdminClient.Callback(){@Override public void onSuccess(JSONObject data){loadAgents();}@Override public void onError(String message){state.setText("Erro: "+message);}});}catch(Exception e){state.setText("Erro ao alterar agente.");}}

    private void saveAgent(){String n=val(name);if(n.isEmpty()){name.setError("Dá um nome ao agente");return;}try{JSONObject rules=new JSONObject().put("objective",val(objective)).put("opening",val(opening)).put("script",val(script));JSONObject scope=new JSONObject().put("description",val(knowledge)).put("sd_dialer",true).put("mypoupar",true).put("crm",true);JSONObject body=new JSONObject().put("name",n).put("language","pt-PT").put("personality",val(personality)).put("identify_as_ai",true).put("active",true).put("system_prompt","Função: "+val(objective)+"\nAbertura: "+val(opening)+"\nRegras: "+val(script)).put("knowledge_scope",scope).put("response_rules",rules);boolean editing=!editingId.isEmpty();if(editing)body.put("id",editingId);state.setText(editing?"A guardar alterações…":"A criar assistente…");RebornAdminClient.call(this,editing?"update_agent":"create_agent",body,new RebornAdminClient.Callback(){@Override public void onSuccess(JSONObject data){Toast.makeText(AgentBuilderActivity.this,editing?"Assistente atualizado":"Assistente criado",Toast.LENGTH_SHORT).show();clear();editor.setVisibility(View.GONE);loadAgents();}@Override public void onError(String message){state.setText("Erro: "+message);}});}catch(Exception e){state.setText("Erro ao preparar agente.");}}

    private void clear(){editingId="";if(name!=null){name.setText("");personality.setText("");objective.setText("");opening.setText("");script.setText("");knowledge.setText("");}}
    private String val(EditText e){return e==null||e.getText()==null?"":e.getText().toString().trim();}
}
