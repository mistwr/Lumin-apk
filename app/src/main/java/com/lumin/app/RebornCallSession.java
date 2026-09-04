package com.lumin.app;

import java.util.UUID;

/**
 * Central session state for a REBORN AI call.
 * Keeps the call lifecycle separated from UI and audio engines.
 */
public class RebornCallSession {
    private final String id = UUID.randomUUID().toString();
    private final long startedAt = System.currentTimeMillis();
    private String customer = "";
    private String transcript = "";
    private String mode = "AUTO";
    private String stage = "START";

    public String id() { return id; }
    public long startedAt() { return startedAt; }

    public void setCustomer(String value) { customer = value == null ? "" : value; }
    public String customer() { return customer; }

    public void appendTranscript(String value) {
        if (value != null && !value.isEmpty()) transcript += value + "\n";
    }
    public String transcript() { return transcript; }

    public void setMode(String value) { mode = value == null ? "AUTO" : value; }
    public String mode() { return mode; }

    public void setStage(String value) { stage = value == null ? "START" : value; }
    public String stage() { return stage; }
}
