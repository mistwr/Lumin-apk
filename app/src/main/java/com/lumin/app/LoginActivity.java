package com.lumin.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
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
        if (RebornAuthClient.isSignedIn(this)) { openHome(); return; }
        setContentView(buildUi());
    }

    private ScrollView buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(ProductUi.BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ProductUi.dp(this,24),ProductUi.dp(this,56),ProductUi.dp(this,24),ProductUi.dp(this,40)); scroll.addView(root);
        root.addView(ProductUi.text(this,"REBORN AI",13,ProductUi.ACCENT,true));
        root.addView(ProductUi.text(this,"Entrar",36,Color.WHITE,true));
        TextView sub=ProductUi.text(this,"Uma sessão para REBORN AI, SD Dialer e gestão comercial.",15,ProductUi.MUTED,false); sub.setPadding(0,8,0,24); root.addView(sub);
        email=field("Email"); email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); root.addView(email);
        password=field("Palavra-passe"); password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(password);
        login=ProductUi.primary(this,"Entrar"); login.setOnClickListener(v->signIn()); root.addView(login,ProductUi.buttonParams(this));
        status=ProductUi.text(this,"",13,ProductUi.MUTED,false); LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=ProductUi.dp(this,14);status.setLayoutParams(sp);root.addView(status);
        TextView note=ProductUi.text(this,"A sessão é renovada automaticamente. A app deixa de depender de um token fixo dentro do APK.",12,ProductUi.MUTED,false); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=ProductUi.dp(this,30);note.setLayoutParams(np);root.addView(note);
        return scroll;
    }

    private EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(ProductUi.MUTED);e.setTextColor(Color.WHITE);e.setTextSize(16);e.setPadding(ProductUi.dp(this,16),ProductUi.dp(this,14),ProductUi.dp(this,16),ProductUi.dp(this,14));e.setBackground(ProductUi.round(ProductUi.CARD,16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=ProductUi.dp(this,10);e.setLayoutParams(p);return e;}

    private void signIn(){String em=email.getText().toString().trim();String pw=password.getText().toString();if(em.isEmpty()){email.setError("Introduz o email");return;}if(pw.isEmpty()){password.setError("Introduz a palavra-passe");return;}login.setEnabled(false);status.setText("A ligar ao REBORN…");Executors.newSingleThreadExecutor().submit(()->{RebornAuthClient.Result r=RebornAuthClient.signIn(this,em,pw);runOnUiThread(()->{login.setEnabled(true);if(r.ok){status.setText("● Ligado");SdDialerBrainClient.refreshAsync(this);openHome();}else status.setText("○ "+r.message);});});}
    private void openHome(){Intent i=new Intent(this,SetupActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);finish();}
}
