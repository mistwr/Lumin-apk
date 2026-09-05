package com.lumin.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.Executors;

public class ProductSettingsActivity extends AppCompatActivity {
    private SharedPreferences control;
    private TextView driver, brain, mode;
    private Button install;

    @Override protected void onCreate(Bundle savedInstanceState){ super.onCreate(savedInstanceState); control=getSharedPreferences("sofia_control",MODE_PRIVATE); setContentView(buildUi()); refresh(); }
    @Override protected void onResume(){ super.onResume(); refresh(); }

    private View buildUi(){
        ScrollView s=new ScrollView(this); s.setFillViewport(true); s.setBackgroundColor(ProductUi.BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(ProductUi.dp(this,20),ProductUi.dp(this,28),ProductUi.dp(this,20),ProductUi.dp(this,40)); s.addView(root);
        root.addView(ProductUi.text(this,"SISTEMA",12,ProductUi.ACCENT,true));
        root.addView(ProductUi.text(this,"Definições",32,Color.WHITE,true));
        TextView intro=ProductUi.text(this,"Configuração técnica do REBORN AI Calling. Esta área fica separada da operação diária.",14,ProductUi.SOFT,false); intro.setPadding(0,ProductUi.dp(this,5),0,ProductUi.dp(this,14)); root.addView(intro);

        LinearLayout status=ProductUi.card(this); driver=ProductUi.text(this,"Driver Samsung",14,ProductUi.MUTED,true); brain=ProductUi.text(this,"Cérebro local",14,ProductUi.MUTED,true); mode=ProductUi.text(this,"Modo AUTO",14,ProductUi.ACCENT,true); status.addView(driver); status.addView(brain); status.addView(mode); root.addView(status);

        root.addView(ProductUi.section(this,"MODO DE CHAMADA"));
        LinearLayout modes=new LinearLayout(this); modes.setOrientation(LinearLayout.HORIZONTAL); modes.setWeightSum(3f);
        modes.addView(modeButton("AUTO","AUTO"),weight()); modes.addView(modeButton("ASSISTIDO","ASSISTED"),weight()); modes.addView(modeButton("MANUAL","MANUAL"),weight()); root.addView(modes);

        root.addView(ProductUi.section(this,"SAMSUNG BRIDGE"));
        Button access=ProductUi.secondary(this,"Abrir Acessibilidade Android"); access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); root.addView(access,ProductUi.buttonParams(this));

        root.addView(ProductUi.section(this,"CÉREBRO LOCAL"));
        install=ProductUi.secondary(this,"Instalar / reinstalar modelo local"); install.setOnClickListener(v->installBrain()); root.addView(install,ProductUi.buttonParams(this));
        Button test=ProductUi.secondary(this,"Testar IA local"); test.setOnClickListener(v->testBrain()); root.addView(test,ProductUi.buttonParams(this));

        root.addView(ProductUi.section(this,"ADMIN TÉCNICO"));
        Button console=ProductUi.secondary(this,"Consola e diagnóstico ao vivo"); console.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class))); root.addView(console,ProductUi.buttonParams(this));
        Button sync=ProductUi.secondary(this,"Sincronizar SD Dialer + MyPoupar"); sync.setOnClickListener(v->{SdDialerBrainClient.refreshAsync(this);RebornEnergyDataClient.refreshAsync(this);TelecomCampaignClient.refreshAsync(this);Toast.makeText(this,"Sincronização iniciada",Toast.LENGTH_SHORT).show();}); root.addView(sync,ProductUi.buttonParams(this));
        return s;
    }

    private Button modeButton(String label,String value){Button b=ProductUi.secondary(this,label);b.setTextSize(11);b.setOnClickListener(v->{control.edit().putString("mode",value).apply();refresh();});return b;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ProductUi.dp(this,48),1f);p.setMargins(ProductUi.dp(this,2),0,ProductUi.dp(this,2),0);return p;}

    private void refresh(){
        if(driver==null)return; boolean enabled=isAccessibilityEnabled(); driver.setText(enabled?"● Samsung Bridge · ATIVO":"○ Samsung Bridge · DESLIGADO"); driver.setTextColor(enabled?ProductUi.ACCENT:Color.rgb(255,190,110));
        boolean installed=LocalQwenManager.isInstalled(this); brain.setText(installed?"● "+LocalQwenManager.MODEL_LABEL+" · "+LocalQwenManager.installedSizeMb(this)+" MB":"○ Modelo local não instalado"); brain.setTextColor(installed?ProductUi.ACCENT:Color.rgb(255,190,110));
        String m=control.getString("mode","AUTO"); mode.setText("Modo · "+("ASSISTED".equals(m)?"ASSISTIDO":m));
    }

    private void installBrain(){install.setEnabled(false);brain.setText("A instalar modelo…");LocalQwenManager.installAsync(this,new LocalQwenManager.DownloadCallback(){public void onProgress(int percent,long downloadedMb,long totalMb){runOnUiThread(()->brain.setText("Modelo · "+(percent>=0?percent+"%":downloadedMb+" MB")));}public void onComplete(String path){runOnUiThread(()->{install.setEnabled(true);refresh();});}public void onError(String message){runOnUiThread(()->{install.setEnabled(true);brain.setText("Erro · "+message);});}});}
    private void testBrain(){brain.setText("A testar IA local…");Executors.newSingleThreadExecutor().submit(()->{SofiaAiHealth.Result r=SofiaAiHealth.check(this);runOnUiThread(()->{brain.setText(r.online?"● IA LOCAL · ONLINE · "+r.latencyMs+" ms":"○ IA LOCAL · OFFLINE");brain.setTextColor(r.online?ProductUi.ACCENT:Color.rgb(255,120,120));});});}
    private boolean isAccessibilityEnabled(){String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);return enabled!=null&&enabled.toLowerCase().contains(getPackageName().toLowerCase());}
}
