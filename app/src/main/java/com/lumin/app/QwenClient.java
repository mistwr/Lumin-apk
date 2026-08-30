package com.lumin.app;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class QwenClient {
    public static String generate(String prompt) throws Exception {
        URL url = new URL(BuildConfig.QWEN_ENDPOINT);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(1200);
        c.setReadTimeout(4500);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("model", "qwen2.5:1.5b");
        body.put("prompt", prompt);
        body.put("stream", false);
        JSONObject opts = new JSONObject();
        opts.put("temperature", 0.65);
        opts.put("top_p", 0.9);
        opts.put("repeat_penalty", 1.08);
        opts.put("num_predict", 45);
        body.put("options", opts);

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }

        int code = c.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        if (code < 200 || code >= 300) throw new IllegalStateException("Qwen HTTP " + code + ": " + sb);
        JSONObject out = new JSONObject(sb.toString());
        return out.optString("response", "").trim();
    }
}
