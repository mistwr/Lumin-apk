package com.lumin.app;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;

public class RecordsActivity extends AppCompatActivity {
    private LinearLayout list;
    private TextView state;
    private String mode;

    @Override protected void onCreate(Bundle savedInstanceState){ super.onCreate(savedInstanceState); mode=getIntent().getStringExtra("mode"); if(mode==null)mode="leads"; setContentView(buildUi()); load(); }

    private View buildUi(){
        ScrollView s=new ScrollView(this); s.setFillViewport(true); s.setBackgroundColor(ProductUi.BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(ProductUi.dp(this,20),ProductUi.dp(this,28),ProductUi.dp(this,20),ProductUi.dp(this,40)); s.addView(root);
        boolean history="history".equals(mode);
        root.addView(ProductUi.text(this,history?"HISTÓRICO":"SD DIALER",12,ProductUi.ACCENT,true));
        root.addView(ProductUi.text(this,history?"Chamadas":"Leads",32,Color.WHITE,true));
        TextView intro=ProductUi.text(this,history?"Resultados, resumo da IA e próxima ação recomendada.":"Leads mais recentes da empresa, já ligados ao mesmo backend da SOFIA.",14,ProductUi.SOFT,false); intro.setPadding(0,ProductUi.dp(this,5),0,ProductUi.dp(this,14)); root.addView(intro);
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list);
        state=ProductUi.text(this,"A carregar…",13,ProductUi.MUTED,false); root.addView(state);
        return s;
    }

    private void load(){
        String action="history".equals(mode)?"list_history":"list_leads";
        RebornAdminClient.call(this,action,new JSONObject(),new RebornAdminClient.Callback(){
            @Override public void onSuccess(JSONObject data){
                list.removeAllViews(); JSONArray a=data.optJSONArray("history".equals(mode)?"history":"leads");
                if(a==null||a.length()==0){state.setText("Sem registos.");return;}
                state.setText(a.length()+" registo(s)");
                for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i); if(x==null)continue; list.addView("history".equals(mode)?historyCard(x):leadCard(x),rowParams());}
            }
            @Override public void onError(String message){state.setText("Não foi possível carregar: "+message);}
        });
    }

    private View leadCard(JSONObject x){
        LinearLayout c=ProductUi.card(this);
        c.addView(ProductUi.text(this,x.optString("nome","Lead")+" · "+x.optString("telefone",""),16,Color.WHITE,true));
        c.addView(ProductUi.text(this,x.optString("status","novo")+" · "+x.optString("operador","sem operador")+" · "+x.optString("origem",""),12,ProductUi.MUTED,false));
        String note=x.optString("observacoes",""); if(!note.isEmpty())c.addView(ProductUi.text(this,note,13,ProductUi.SOFT,false));
        return c;
    }

    private View historyCard(JSONObject x){
        LinearLayout c=ProductUi.card(this);
        c.addView(ProductUi.text(this,"Resultado · "+x.optString("result","outro"),16,Color.WHITE,true));
        String summary=x.optString("ai_summary",x.optString("notes","")); if(!summary.isEmpty())c.addView(ProductUi.text(this,summary,13,ProductUi.SOFT,false));
        String next=x.optString("ai_next_best_action",""); if(!next.isEmpty())c.addView(ProductUi.text(this,"Próxima ação: "+next,12,ProductUi.ACCENT,true));
        c.addView(ProductUi.text(this,"Duração: "+x.optInt("duration_sec",0)+"s · "+x.optString("created_at",""),11,ProductUi.MUTED,false));
        return c;
    }

    private LinearLayout.LayoutParams rowParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=ProductUi.dp(this,8);return p;}
}
