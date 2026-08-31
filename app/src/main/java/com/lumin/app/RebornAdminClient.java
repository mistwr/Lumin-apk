package com.lumin.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Secure app client for the JWT-protected reborn-admin Edge Function. */
public final class RebornAdminClient {
    public interface Callback {
        void onSuccess(JSONObject data);
        void onError(String message);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private RebornAdminClient() {}

    public static void call(Context context, String action, JSONObject body, Callback callback) {
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                if (BuildConfig.SUPABASE_URL.isEmpty() || BuildConfig.SUPABASE_ANON_KEY.isEmpty() || BuildConfig.SUPABASE_ACCESS_TOKEN.isEmpty()) {
                    fail(callback, "Supabase ainda não está configurado nesta build.");
                    return;
                }
                JSONObject payload = body == null ? new JSONObject() : body;
                payload.put("action", action);
                URL url = new URL(BuildConfig.SUPABASE_URL + "/functions/v1/reborn-admin");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(9000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
                c.setRequestProperty("Authorization", "Bearer " + BuildConfig.SUPABASE_ACCESS_TOKEN);
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject result = sb.length() == 0 ? new JSONObject() : new JSONObject(sb.toString());
                if (code >= 200 && code < 300 && !result.has("error")) {
                    if (callback != null) UI.post(() -> callback.onSuccess(result));
                } else {
                    String message = result.optString("detail", result.optString("error", "Erro " + code));
                    fail(callback, message);
                }
            } catch (Exception e) {
                fail(callback, e.getMessage() == null ? "Falha de ligação ao REBORN Admin." : e.getMessage());
            }
        });
    }

    private static void fail(Callback callback, String message) {
        if (callback != null) UI.post(() -> callback.onError(message));
    }
}
