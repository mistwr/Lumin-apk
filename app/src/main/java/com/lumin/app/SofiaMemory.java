package com.lumin.app;

import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

public class SofiaMemory {
    private final Map<String, Object> facts = new LinkedHashMap<>();
    private String lastAssistant = "";

    public synchronized void put(String key, Object value) {
        if (value != null) facts.put(key, value);
    }

    public synchronized Object get(String key) { return facts.get(key); }
    public synchronized boolean has(String key) { return facts.containsKey(key) && facts.get(key) != null; }

    public synchronized JSONObject toJson() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String,Object> e : facts.entrySet()) {
            try { o.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
        }
        return o;
    }

    public synchronized String summary() { return toJson().toString(); }
    public synchronized void setLastAssistant(String text) { lastAssistant = text == null ? "" : text; }
    public synchronized String getLastAssistant() { return lastAssistant; }
}
