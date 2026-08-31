package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Real Supabase Auth session for REBORN AI. Never relies on a baked user access token. */
public final class RebornAuthClient {
    private static final String PREFS = "reborn_auth";
    private static final String ACCESS = "access_token";
    private static final String REFRESH = "refresh_token";
    private static final String EMAIL = "email";
    private static final String EXPIRES_AT = "expires_at";

    private RebornAuthClient() {}

    public static final class Result {
        public final boolean ok;
        public final String message;
        public Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    public static Result signIn(Context context, String email, String password) {
        if (BuildConfig.SUPABASE_URL.isEmpty() || BuildConfig.SUPABASE_ANON_KEY.isEmpty()) return new Result(false, "Supabase não configurado nesta build.");
        try {
            JSONObject body = new JSONObject().put("email", email.trim()).put("password", password);
            JSONObject out = post(BuildConfig.SUPABASE_URL + "/auth/v1/token?grant_type=password", body);
            saveSession(context, email.trim(), out);
            return new Result(true, "Ligado");
        } catch (Exception e) { return new Result(false, clean(e)); }
    }

    public static String token(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String access = p.getString(ACCESS, "");
        long expiresAt = p.getLong(EXPIRES_AT, 0L);
        if (!access.isEmpty() && System.currentTimeMillis() < expiresAt - 60_000L) return access;
        String refresh = p.getString(REFRESH, "");
        if (refresh.isEmpty()) return "";
        try {
            JSONObject body = new JSONObject().put("refresh_token", refresh);
            JSONObject out = post(BuildConfig.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token", body);
            saveSession(context, p.getString(EMAIL, ""), out);
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACCESS, "");
        } catch (Exception ignored) { return ""; }
    }

    public static boolean isSignedIn(Context context) { return !token(context).isEmpty(); }
    public static String email(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EMAIL, ""); }
    public static void signOut(Context context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply(); }

    private static void saveSession(Context context, String email, JSONObject out) throws Exception {
        String access = out.optString("access_token", "");
        String refresh = out.optString("refresh_token", "");
        long expiresIn = out.optLong("expires_in", 3600L);
        if (access.isEmpty()) throw new IllegalStateException(out.optString("msg", out.optString("error_description", "Login recusado")));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ACCESS, access).putString(REFRESH, refresh).putString(EMAIL, email)
                .putLong(EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L).apply();
    }

    private static JSONObject post(String endpoint, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(8000); c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line); br.close();
        JSONObject out = sb.length() == 0 ? new JSONObject() : new JSONObject(sb.toString());
        if (code < 200 || code >= 300) throw new IllegalStateException(out.optString("msg", out.optString("error_description", "HTTP " + code)));
        return out;
    }

    private static String clean(Exception e) { String m=e.getMessage(); return m==null||m.trim().isEmpty()?"Falha de autenticação.":m; }
}
