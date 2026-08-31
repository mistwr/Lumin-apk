package com.lumin.app;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;

public class UsersAdminActivity extends AppCompatActivity {
    private LinearLayout list;
    private TextView state;
    private EditText fullName,email,phone,password,equipa,meta;
    private Spinner role;

    @Override protected void onCreate(Bundle savedInstanceState){ super.onCreate(savedInstanceState); setContentView(buildUi()); loadUsers(); }

    private View buildUi(){
        ScrollView s=new ScrollView(this); s.setFillViewport(true); s.setBackgroundColor(ProductUi.BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(ProductUi.dp(this,20),ProductUi.dp(this,28),ProductUi.dp(this,20),ProductUi.dp(this,40)); s.addView(root);
        root.addView(ProductUi.text(this,"ADMIN",12,ProductUi.ACCENT,true));
        root.addView(ProductUi.text(this,"Utilizadores",32,Color.WHITE,true));
        TextView intro=ProductUi.text(this,"Cria acessos para vendedores, supervisores e administradores. Cada conta fica associada à tua empresa no SD Dialer.",14,ProductUi.SOFT,false); intro.setPadding(0,ProductUi.dp(this,5),0,ProductUi.dp(this,14)); root.addView(intro);

        root.addView(ProductUi.section(this,"EQUIPA"));
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list);
        state=ProductUi.text(this,"A carregar…",13,ProductUi.MUTED,false); root.addView(state);

        root.addView(ProductUi.section(this,"CRIAR UTILIZADOR"));
        fullName=ProductUi.field(this,"Nome completo"); email=ProductUi.field(this,"Email"); phone=ProductUi.field(this,"Telefone"); password=ProductUi.field(this,"Password temporária · mínimo 8 caracteres"); equipa=ProductUi.field(this,"Equipa / unidade"); meta=ProductUi.field(this,"Meta de ligações por dia");
        root.addView(fullName); root.addView(email); root.addView(phone); root.addView(password);
        role=new Spinner(this); role.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"parceiro","supervisor","admin"})); role.setBackground(ProductUi.round(this,ProductUi.CARD,14)); LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,ProductUi.dp(this,56)); rp.topMargin=ProductUi.dp(this,7); root.addView(role,rp);
        root.addView(equipa); root.addView(meta);
        Button create=ProductUi.primary(this,"Criar utilizador"); create.setOnClickListener(v->createUser()); root.addView(create,ProductUi.buttonParams(this));
        TextView note=ProductUi.text(this,"Por segurança, apenas um super-admin pode criar novas contas de acesso.",12,ProductUi.MUTED,false); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2); np.topMargin=ProductUi.dp(this,10); note.setLayoutParams(np); root.addView(note);
        return s;
    }

    private void loadUsers(){
        state.setText("A carregar utilizadores…");
        RebornAdminClient.call(this,"list_users",new JSONObject(),new RebornAdminClient.Callback(){
            @Override public void onSuccess(JSONObject data){
                list.removeAllViews(); JSONArray a=data.optJSONArray("users");
                if(a==null||a.length()==0){state.setText("Sem utilizadores.");return;}
                state.setText(a.length()+" utilizador(es)");
                for(int i=0;i<a.length();i++){
                    JSONObject x=a.optJSONObject(i); if(x==null)continue;
                    LinearLayout c=ProductUi.card(UsersAdminActivity.this);
                    String n=x.optString("full_name",x.optString("email","Utilizador"));
                    c.addView(ProductUi.text(UsersAdminActivity.this,("active".equals(x.optString("status"))?"● ":"○ ")+n,16,Color.WHITE,true));
                    String meta=x.optInt("meta_ligacoes_dia",0)>0?" · meta "+x.optInt("meta_ligacoes_dia")+"/dia":"";
                    c.addView(ProductUi.text(UsersAdminActivity.this,x.optString("role","parceiro")+" · "+x.optString("email","")+meta,12,ProductUi.MUTED,false));
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=ProductUi.dp(UsersAdminActivity.this,8); list.addView(c,p);
                }
            }
            @Override public void onError(String message){state.setText("Não foi possível carregar: "+message);}
        });
    }

    private void createUser(){
        String em=val(email), pw=val(password); if(em.isEmpty()){email.setError("Email obrigatório");return;} if(pw.length()<8){password.setError("Mínimo 8 caracteres");return;}
        try{
            JSONObject body=new JSONObject(); body.put("full_name",val(fullName)); body.put("email",em); body.put("phone",val(phone)); body.put("password",pw); body.put("role",String.valueOf(role.getSelectedItem())); body.put("equipa",val(equipa));
            int goal=0; try{goal=Integer.parseInt(val(meta));}catch(Exception ignored){} body.put("meta_ligacoes_dia",goal);
            state.setText("A criar conta…");
            RebornAdminClient.call(this,"create_user",body,new RebornAdminClient.Callback(){
                @Override public void onSuccess(JSONObject data){Toast.makeText(UsersAdminActivity.this,"Utilizador criado",Toast.LENGTH_SHORT).show(); clear(); loadUsers();}
                @Override public void onError(String message){state.setText("Erro: "+message);}
            });
        }catch(Exception e){state.setText("Erro ao preparar utilizador.");}
    }

    private void clear(){fullName.setText("");email.setText("");phone.setText("");password.setText("");equipa.setText("");meta.setText("");}
    private String val(EditText e){return e.getText()==null?"":e.getText().toString().trim();}
}
