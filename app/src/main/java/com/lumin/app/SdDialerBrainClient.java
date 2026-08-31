package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Live commercial brain shared with SD Dialer / Indigo. */
public final class SdDialerBrainClient {
    private static final String PREFS = "reborn_sd_brain";
    private static final String KEY_ROTEIROS = "roteiros";
    private static final String KEY_OBJECOES = "objecoes";
    private static final String KEY_SYNC_AT = "sync_at";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final long TTL_MS = 15L * 60L * 1000L;
    public static final String DEFAULT_COMPANY_ID = "e27c8324-989a-4a68-b3a3-902cba74696c";

    private SdDialerBrainClient() {}

    public static void refreshAsync(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> { try { refresh(app, false); } catch (Throwable ignored) {} }, "reborn-sd-brain").start();
    }

    public static boolean refresh(Context context, boolean force) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong(KEY_SYNC_AT, 0L);
        if (!force && System.currentTimeMillis() - last < TTL_MS && p.contains(KEY_ROTEIROS)) return true;
        if (BuildConfig.SUPABASE_URL.isEmpty() || BuildConfig.SUPABASE_ANON_KEY.isEmpty()) return false;
        String token = RebornAuthClient.token(context);
        if (token.isEmpty()) { p.edit().putString(KEY_LAST_ERROR,"Sem sessão REBORN").apply(); return false; }
        try {
            String companyId = context.getSharedPreferences("sofia_control", Context.MODE_PRIVATE).getString("sd_company_id", DEFAULT_COMPANY_ID);
            JSONArray roteiros = getArray("roteiros_venda", "id,titulo,conteudo,segmento,ordem,ativo", companyId, 20, token);
            JSONArray objecoes = getArray("banco_objecoes", "id,objecao,resposta_sugerida,segmento,ordem,ativo", companyId, 30, token);
            p.edit().putString(KEY_ROTEIROS, roteiros.toString()).putString(KEY_OBJECOES, objecoes.toString())
                    .putLong(KEY_SYNC_AT, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply();
            return true;
        } catch (Throwable e) { p.edit().putString(KEY_LAST_ERROR,String.valueOf(e.getMessage())).apply(); return false; }
    }

    private static JSONArray getArray(String table, String select, String companyId, int limit, String token) throws Exception {
        String q = BuildConfig.SUPABASE_URL + "/rest/v1/" + table + "?select=" + URLEncoder.encode(select, "UTF-8")
                + "&company_id=eq." + URLEncoder.encode(companyId, "UTF-8") + "&ativo=eq.true&order=ordem.asc&limit=" + limit;
        HttpURLConnection c = (HttpURLConnection) new URL(q).openConnection();
        c.setConnectTimeout(1800); c.setReadTimeout(3000); c.setRequestMethod("GET"); c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
        c.setRequestProperty("Authorization", "Bearer " + token);
        int code=c.getResponseCode(); BufferedReader br=new BufferedReader(new InputStreamReader(code>=200&&code<300?c.getInputStream():c.getErrorStream()));
        StringBuilder sb=new StringBuilder(); String line; while((line=br.readLine())!=null)sb.append(line); br.close(); c.disconnect();
        if(code<200||code>=300) throw new IllegalStateException("SD Dialer HTTP "+code+" "+sb);
        return new JSONArray(sb.toString());
    }

    public static boolean isConnected(Context context) {
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        return p.contains(KEY_ROTEIROS) && p.getString(KEY_LAST_ERROR,"").isEmpty();
    }
    public static String lastError(Context context) { return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_LAST_ERROR,""); }

    public static String promptContext(Context context) {
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE); StringBuilder out=new StringBuilder("SD DIALER — GUIAO E OBJECOES ATIVOS. Usa apenas quando relevantes. ");
        try { JSONArray r=new JSONArray(p.getString(KEY_ROTEIROS,"[]")); int count=Math.min(r.length(),10); for(int i=0;i<count;i++){JSONObject o=r.optJSONObject(i);if(o==null)continue;out.append("ETAPA ").append(o.optInt("ordem",i+1)).append(": ").append(compact(o.optString("conteudo"))).append(" | ");}
            JSONArray a=new JSONArray(p.getString(KEY_OBJECOES,"[]")); count=Math.min(a.length(),12); for(int i=0;i<count;i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;out.append("OBJ: ").append(compact(o.optString("objecao"))).append(" => ").append(compact(o.optString("resposta_sugerida"))).append(" | ");}
        } catch(Throwable ignored){} String s=out.toString(); return s.length()>5200?s.substring(0,5200):s;
    }

    public static String matchObjection(Context context,String customer){String n=normalize(customer);if(n.length()<4)return null;try{JSONArray a=new JSONArray(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_OBJECOES,"[]"));Set<String> customerTokens=tokens(n);int bestScore=0;String best=null;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String objection=normalize(o.optString("objecao"));int score=overlap(customerTokens,tokens(objection));if(objection.contains(n)||n.contains(objection))score+=4;if(score>bestScore){bestScore=score;best=o.optString("resposta_sugerida","").trim();}}return bestScore>=3&&best!=null&&!best.isEmpty()?firstSentence(best):null;}catch(Throwable ignored){return null;}}
    private static int overlap(Set<String>a,Set<String>b){int s=0;for(String x:a)if(b.contains(x))s++;return s;}
    private static Set<String> tokens(String s){Set<String>set=new HashSet<>();String[]stop={"eu","o","a","os","as","de","do","da","e","um","uma","para","com","que","nao","me","lhe","agora"};Set<String>stops=new HashSet<>();for(String x:stop)stops.add(normalize(x));for(String x:s.split("\\s+"))if(x.length()>=3&&!stops.contains(x))set.add(x);return set;}
    private static String firstSentence(String s){String clean=compact(s);int cut=clean.indexOf(". ");if(cut>35&&cut<180)clean=clean.substring(0,cut+1);return clean.length()>180?clean.substring(0,180).trim():clean;}
    private static String compact(String s){return s==null?"":s.replace('\n',' ').replaceAll("\\s+"," ").trim();}
    private static String normalize(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replace('á','a').replace('à','a').replace('ã','a').replace('â','a').replace('é','e').replace('ê','e').replace('í','i').replace('ó','o').replace('ô','o').replace('õ','o').replace('ú','u').replace('ç','c').replaceAll("[^a-z0-9]+"," ").trim();}
}
