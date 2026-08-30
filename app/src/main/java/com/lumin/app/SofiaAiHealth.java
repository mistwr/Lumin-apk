package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class SofiaAiHealth {
    public static final class Result {
        public final boolean online;
        public final long latencyMs;
        public final String message;
        Result(boolean online, long latencyMs, String message) {
            this.online = online;
            this.latencyMs = latencyMs;
            this.message = message;
        }
    }

    private SofiaAiHealth() {}

    public static String endpoint(Context context) {
        SharedPreferences p = context.getSharedPreferences("sofia_ai", Context.MODE_PRIVATE);
        return p.getString("endpoint", BuildConfig.QWEN_ENDPOINT);
    }

    public static void saveEndpoint(Context context, String endpoint) {
        context.getSharedPreferences("sofia_ai", Context.MODE_PRIVATE)
                .edit().putString("endpoint", endpoint == null ? "" : endpoint.trim()).apply();
    }

    public static Result check(Context context) {
        if (LocalQwenManager.isInstalled(context)) {
            long start = System.currentTimeMillis();
            try {
                String health = LocalQwenManager.healthBlocking(context);
                long ms = System.currentTimeMillis() - start;
                float speed = LocalQwenManager.getLastTokensPerSecond();
                return new Result(true, ms, "IA LOCAL · " + LocalQwenManager.MODEL_LABEL + " · " + String.format(java.util.Locale.US, "%.1f tok/s", speed));
            } catch (Throwable ex) {
                return new Result(false, System.currentTimeMillis() - start, "Modelo local: " + ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "erro" : ex.getMessage()));
            }
        }

        long start = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            String e = endpoint(context);
            URL generate = new URL(e);
            String base = generate.getProtocol() + "://" + generate.getHost() + (generate.getPort() > 0 ? ":" + generate.getPort() : "");
            URL tags = new URL(base + "/api/tags");
            c = (HttpURLConnection) tags.openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1800);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            long ms = System.currentTimeMillis() - start;
            if (code < 200 || code >= 300) return new Result(false, ms, "HTTP " + code);
            JSONObject out = new JSONObject(sb.toString());
            boolean hasModel = out.optJSONArray("models") != null && out.optJSONArray("models").length() > 0;
            return new Result(true, ms, hasModel ? "Endpoint remoto e modelo disponíveis" : "Endpoint remoto online; falta modelo");
        } catch (Exception ex) {
            return new Result(false, System.currentTimeMillis() - start, "Cérebro local não instalado · remoto: " + ex.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
