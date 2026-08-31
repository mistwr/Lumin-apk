package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** REBORN AI -> protected MyPoupar telecom campaign cache. */
public final class TelecomCampaignClient {
    private static final String PREFS = "reborn_telecom_campaigns";
    private static final long TTL_MS = 10 * 60 * 1000L;
    private TelecomCampaignClient() {}

    public static void refreshAsync(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> refresh(app), "reborn-mypoupar-telecom").start();
    }

    public static boolean refresh(Context context) {
        String base = BuildConfig.MYPOUPAR_SUPABASE_URL == null ? "" : BuildConfig.MYPOUPAR_SUPABASE_URL.trim();
        String indigoAnon = BuildConfig.SUPABASE_ANON_KEY == null ? "" : BuildConfig.SUPABASE_ANON_KEY.trim();
        String accessToken = BuildConfig.SUPABASE_ACCESS_TOKEN == null ? "" : BuildConfig.SUPABASE_ACCESS_TOKEN.trim();
        if (base.isEmpty() || indigoAnon.isEmpty() || accessToken.isEmpty()) return false;

        try {
            String endpoint = base.replaceAll("/$", "") + "/functions/v1/reborn-campaign-feed";
            HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(25000);
            c.setRequestProperty("Authorization", "Bearer " + accessToken);
            c.setRequestProperty("x-indigo-apikey", indigoAnon);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return false;

            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) body.append(line);
            r.close();

            JSONObject root = new JSONObject(body.toString());
            JSONArray arr = root.optJSONArray("campaigns");
            if (arr == null) arr = new JSONArray();

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("campaigns_json", arr.toString())
                    .putLong("updated_at", System.currentTimeMillis())
                    .putInt("count", arr.length())
                    .putInt("processed_pdfs", root.optInt("processed_pdfs", 0))
                    .apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String promptContext(Context context, String operator) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long age = System.currentTimeMillis() - p.getLong("updated_at", 0L);
        if (age > TTL_MS) refreshAsync(context);
        String raw = p.getString("campaigns_json", "[]");
        if (raw == null || raw.equals("[]")) return "";

        try {
            JSONArray arr = new JSONArray(raw);
            String wanted = operator == null ? "" : normalize(operator);
            StringBuilder out = new StringBuilder(" CAMPANHAS TELECOM MYPOUPAR ATIVAS E INTERNAS: ");
            int used = 0;
            for (int i = 0; i < arr.length() && used < 5; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String op = o.optString("operator", "");
                if (!wanted.isEmpty() && !normalize(op).equals(wanted)) continue;

                String title = clean(o.optString("title", ""));
                String desc = clean(o.optString("description", ""));
                String discount = clean(o.optString("discount", ""));
                String start = clean(o.optString("start_date", ""));
                String end = clean(o.optString("end_date", ""));
                JSONArray materials = o.optJSONArray("materials");

                out.append("[").append(op).append(" | ").append(title);
                if (!desc.isEmpty()) out.append(" | ").append(limit(desc, 420));
                if (!discount.isEmpty()) out.append(" | desconto: ").append(limit(discount, 160));
                if (!start.isEmpty()) out.append(" | início: ").append(start);
                if (!end.isEmpty()) out.append(" | fim: ").append(end);

                if (materials != null) {
                    int materialUsed = 0;
                    for (int j = 0; j < materials.length() && materialUsed < 2; j++) {
                        JSONObject m = materials.optJSONObject(j);
                        if (m == null) continue;
                        String text = clean(m.optString("text_excerpt", ""));
                        if (text.isEmpty()) continue;
                        out.append(" | PDF: ").append(limit(text, wanted.isEmpty() ? 260 : 900));
                        materialUsed++;
                    }
                }
                out.append("] ");
                used++;
            }
            return used == 0 ? "" : out.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static int cachedCount(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("count", 0);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\n',' ').replace('\r',' ').replaceAll("\\s+", " ").trim();
    }

    private static String limit(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replace("á","a").replace("à","a").replace("ã","a").replace("â","a")
                .replace("é","e").replace("ê","e").replace("í","i")
                .replace("ó","o").replace("ô","o").replace("õ","o")
                .replace("ú","u").replace("ç","c").trim();
    }
}
