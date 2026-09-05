package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lightweight bridge to the same electricity data source used by the MyPoupar simulator. */
public final class RebornEnergyDataClient {
    private static final String PREFS = "reborn_energy_data";
    private static final String KEY_MANIFEST = "manifest_hash";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_SYNC_AT = "sync_at";
    private static final long TTL_MS = 6L * 60L * 60L * 1000L;

    private static final String BASE_PRIMARY = "https://dados.tiagofelicia.pt/data/simuladores/simulador-tarifarios-eletricidade/csv/";
    private static final String BASE_FALLBACK = "https://raw.githubusercontent.com/tiagofelicia/dados-energia/main/data/simuladores/simulador-tarifarios-eletricidade/csv/";

    private static final Set<String> PRIORITY = new HashSet<>();
    static {
        String[] names = {"meo", "endesa", "iberdrola", "galp", "repsol", "goldenergy", "gold energy", "edp", "yes energy"};
        for (String n : names) PRIORITY.add(n);
    }

    private RebornEnergyDataClient() {}

    public static void refreshAsync(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> { try { refresh(app, false); } catch (Throwable ignored) {} }, "reborn-energy-refresh").start();
    }

    public static boolean refresh(Context context, boolean force) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong(KEY_SYNC_AT, 0L);
        if (!force && System.currentTimeMillis() - last < TTL_MS && p.contains(KEY_CONTEXT)) return true;
        try {
            String manifestRaw = getText("manifest.json", 250_000);
            JSONObject manifest = new JSONObject(manifestRaw);
            String hash = manifest.optString("Tarifarios_fixos", "");
            if (!force && !hash.isEmpty() && hash.equals(p.getString(KEY_MANIFEST, "")) && p.contains(KEY_CONTEXT)) {
                p.edit().putLong(KEY_SYNC_AT, System.currentTimeMillis()).apply();
                return true;
            }

            String csv = getText("Tarifarios_fixos.csv", 1_700_000);
            String contextText = extractCommercialContext(csv, hash);
            if (contextText.isEmpty()) return false;
            p.edit().putString(KEY_MANIFEST, hash)
                    .putString(KEY_CONTEXT, contextText)
                    .putLong(KEY_SYNC_AT, System.currentTimeMillis()).apply();
            return true;
        } catch (Throwable ignored) { return false; }
    }

    public static String promptContext(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString(KEY_CONTEXT, "").trim();
        if (s.isEmpty()) return "";
        return "DADOS ENERGIA MYPOUPAR (fonte do simulador; usa apenas como referência e só quando os dados do cliente forem suficientes): " + s + " ";
    }

    private static String extractCommercialContext(String csv, String hash) {
        if (csv == null || csv.isEmpty()) return "";
        String[] lines = csv.replace("\r", "").split("\n");
        if (lines.length < 2) return "";
        String[] header = parseCsvLine(lines[0]);
        int iCom = indexOf(header, "comercializador");
        int iNome = indexOf(header, "nome");
        int iOp = indexOf(header, "opcao_horaria_e_ciclo");
        int iPot = indexOf(header, "potencia_kva");
        int iPotDia = indexOf(header, "preco_potencia_dia");
        int iSimples = indexOf(header, "preco_energia_simples");
        int iFv = indexOf(header, "preco_energia_fora_vazio");
        int iVazio = indexOf(header, "preco_energia_vazio_bi");
        if (iCom < 0 || iNome < 0) return "";

        List<String> out = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        for (int i=1; i<lines.length && out.size()<36; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            String[] c = parseCsvLine(line);
            String com = val(c, iCom);
            String lc = normalize(com);
            boolean priority = false;
            for (String n : PRIORITY) if (lc.contains(n)) { priority = true; break; }
            if (!priority) continue;
            String op = val(c, iOp);
            String pot = val(c, iPot);
            String key = lc + "|" + normalize(op) + "|" + pot;
            if (!dedup.add(key)) continue;
            StringBuilder r = new StringBuilder();
            r.append(com).append(" · ").append(val(c, iNome));
            if (!op.isEmpty()) r.append(" · ").append(op);
            if (!pot.isEmpty()) r.append(" · ").append(pot).append(" kVA");
            String simples = val(c, iSimples);
            String fora = val(c, iFv);
            String vazio = val(c, iVazio);
            String potDia = val(c, iPotDia);
            if (!simples.isEmpty()) r.append(" · energia ").append(simples).append(" €/kWh");
            else if (!fora.isEmpty() || !vazio.isEmpty()) r.append(" · fora-vazio ").append(fora).append(" · vazio ").append(vazio).append(" €/kWh");
            if (!potDia.isEmpty()) r.append(" · potência ").append(potDia).append(" €/dia");
            out.add(r.toString());
        }
        StringBuilder s = new StringBuilder();
        if (hash != null && !hash.isEmpty()) s.append("versão ").append(hash).append(". ");
        for (String row : out) s.append(row).append(" | ");
        String result = s.toString();
        return result.length() > 6200 ? result.substring(0, 6200) : result;
    }

    private static String getText(String path, int maxChars) throws Exception {
        Exception last = null;
        for (String base : new String[]{BASE_PRIMARY, BASE_FALLBACK}) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
                c.setConnectTimeout(2500); c.setReadTimeout(7000); c.setRequestMethod("GET");
                c.setRequestProperty("Accept", "text/plain,application/json,*/*");
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) { c.disconnect(); continue; }
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > maxChars) break;
                }
                br.close(); c.disconnect();
                return sb.toString();
            } catch (Exception e) { last = e; }
        }
        if (last != null) throw last;
        throw new IllegalStateException("Energy source unavailable");
    }

    private static int indexOf(String[] a, String name) {
        for (int i=0;i<a.length;i++) if (name.equalsIgnoreCase(a[i].replace("\uFEFF", "").trim())) return i;
        return -1;
    }
    private static String val(String[] a, int i) { return i >= 0 && i < a.length ? a[i].trim() : ""; }

    private static String[] parseCsvLine(String line) {
        List<String> cells = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q=false;
        for (int i=0;i<line.length();i++) {
            char ch=line.charAt(i);
            if (ch=='\"') {
                if (q && i+1<line.length() && line.charAt(i+1)=='\"') { cur.append('\"'); i++; }
                else q=!q;
            } else if (ch==',' && !q) { cells.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        cells.add(cur.toString());
        return cells.toArray(new String[0]);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replace('á','a').replace('à','a').replace('ã','a').replace('â','a')
                .replace('é','e').replace('ê','e').replace('í','i').replace('ó','o').replace('ô','o').replace('õ','o')
                .replace('ú','u').replace('ç','c').replaceAll("[^a-z0-9]+", " ").trim();
    }
}
