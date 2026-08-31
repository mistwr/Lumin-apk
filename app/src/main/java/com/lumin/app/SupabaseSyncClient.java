package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SupabaseSyncClient {
    private static final String PREFS = "sofia_sync_queue";
    private static final String KEY = "queue";

    public static boolean sync(Context context, JSONObject payload) {
        String token = RebornAuthClient.token(context);
        if (BuildConfig.SUPABASE_URL.isEmpty() || BuildConfig.SUPABASE_ANON_KEY.isEmpty() || token.isEmpty()) {
            enqueue(context, payload); return false;
        }
        return syncWithToken(context, payload, token, true);
    }

    public static void flush(Context context) {
        String token = RebornAuthClient.token(context);
        if (token.isEmpty()) return;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString(KEY, "[]")); JSONArray keep = new JSONArray();
            for (int i=0;i<a.length();i++) { JSONObject item=a.optJSONObject(i); if(item==null || !syncWithToken(context,item,token,false)) keep.put(item); }
            p.edit().putString(KEY, keep.toString()).apply();
        } catch(Exception ignored) {}
    }

    private static boolean syncWithToken(Context context, JSONObject payload, String token, boolean queueOnFail) {
        try {
            URL url = new URL(BuildConfig.SUPABASE_URL + "/functions/v1/sofia-lead-sync");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setConnectTimeout(2500); c.setReadTimeout(5000); c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("apikey",BuildConfig.SUPABASE_ANON_KEY);
            c.setRequestProperty("Authorization","Bearer "+token); c.setDoOutput(true);
            try(OutputStream os=c.getOutputStream()){os.write(payload.toString().getBytes("UTF-8"));}
            int code=c.getResponseCode(); BufferedReader br=new BufferedReader(new InputStreamReader(code>=200&&code<300?c.getInputStream():c.getErrorStream())); while(br.readLine()!=null){} br.close();
            if(code>=200&&code<300) return true;
        } catch(Exception ignored) {}
        if(queueOnFail) enqueue(context,payload); return false;
    }

    private static void enqueue(Context context, JSONObject payload) {
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        try { JSONArray a=new JSONArray(p.getString(KEY,"[]")); a.put(payload); while(a.length()>100)a.remove(0); p.edit().putString(KEY,a.toString()).apply(); } catch(Exception ignored) {}
    }
}
