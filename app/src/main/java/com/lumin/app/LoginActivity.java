package com.lumin.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {
    private EditText email, password;
    private TextView status;
    private Button login;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProductUi.applyWindow(this);
        if (RebornAuthClient.isSignedIn(this)) { openHome(); return; }
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(ProductUi.BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(ProductUi.dp(this,24),ProductUi.dp(this,34),ProductUi.dp(this,24),ProductUi.dp(this,34)); scroll.addView(root);

        TextView brand=ProductUi.eyebrow(this,"REBORN AI"); brand.setGravity(Gravity.CENTER); root.addView(brand);
        TextView title=ProductUi.title(this,"Bem-vindo de volta"); title.setGravity(Gravity.CENTER); LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=ProductUi.dp(this,10);title.setLayoutParams(tp);root.addView(title);
        TextView sub=ProductUi.text(this,"Acede ao teu workspace de agentes, chamadas e inteligência comercial.",14,ProductUi.MUTED,false);sub.setGravity(Gravity.CENTER);LinearLayout.LayoutParams sp0=new LinearLayout.LayoutParams(-1,-2);sp0.topMargin=ProductUi.dp(this,10);sp0.bottomMargin=ProductUi.dp(this,28);sub.setLayoutParams(sp0);root.addView(sub);

        LinearLayout card=ProductUi.heroCard(this);
        TextView access=ProductUi.text(this,"ACESSO SEGURO",10,ProductUi.MUTED,true);access.setLetterSpacing(0.10f);card.addView(access);
        TextView accessTitle=ProductUi.text(this,"Entrar no REBORN",20,ProductUi.TEXT,true);LinearLayout.LayoutParams atp=new LinearLayout.LayoutParams(-1,-2);atp.topMargin=ProductUi.dp(this,7);accessTitle.setLayoutParams(atp);card.addView(accessTitle);

        email=ProductUi.field(this,"Email"); email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); card.addView(email);
        password=ProductUi.field(this,"Palavra-passe"); password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); card.addView(password);

        login=ProductUi.primary(this,"Entrar no workspace  →"); login.setOnClickListener(v->signIn()); card.addView(login,ProductUi.buttonParams(this));
        status=ProductUi.text(this,"",13,ProductUi.MUTED,false); LinearLayout.LayoutParams stp=new LinearLayout.LayoutParams(-1,-2);stp.topMargin=ProductUi.dp(this,12);status.setLayoutParams(stp);card.addView(status);
        root.addView(card);

        LinearLayout trust=ProductUi.card(this); LinearLayout.LayoutParams trp=new LinearLayout.LayoutParams(-1,-2);trp.topMargin=ProductUi.dp(this,14);trust.setLayoutParams(trp);
        trust.addView(ProductUi.text(this,"● Sessão autenticada e renovada automaticamente",12,ProductUi.SOFT,false));
        TextView sync=ProductUi.text(this,"REBORN AI · SD Dialer · MyPoupar",11,ProductUi.MUTED,false);LinearLayout.LayoutParams sy=new LinearLayout.LayoutParams(-1,-2);sy.topMargin=ProductUi.dp(this,6);sync.setLayoutParams(sy);trust.addView(sync);root.addView(trust);

        TextView footer=ProductUi.text(this,"Feito por REBORN AI",11,ProductUi.MUTED,false);footer.setGravity(Gravity.CENTER);LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.topMargin=ProductUi.dp(this,24);footer.setLayoutParams(fp);root.addView(footer);
        return scroll;
    }

    private void signIn(){
        String em=email.getText().toString().trim();String pw=password.getText().toString();
        if(em.isEmpty()){email.setError("Introduz o email");return;}if(pw.isEmpty()){password.setError("Introduz a palavra-passe");return;}
        login.setEnabled(false);login.setAlpha(.65f);status.setText("● A autenticar e ligar o workspace…");status.setTextColor(ProductUi.ACCENT);
        Executors.newSingleThreadExecutor().submit(()->{RebornAuthClient.Result r=RebornAuthClient.signIn(this,em,pw);runOnUiThread(()->{login.setEnabled(true);login.setAlpha(1f);if(r.ok){status.setText("● Ligado com sucesso");status.setTextColor(ProductUi.ACCENT);SdDialerBrainClient.refreshAsync(this);openHome();}else{status.setText("○ "+r.message);status.setTextColor(ProductUi.DANGER);}});});
    }
    private void openHome(){Intent i=new Intent(this,SetupActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);finish();}
}
