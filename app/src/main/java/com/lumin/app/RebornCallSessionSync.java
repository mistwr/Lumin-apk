package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Finalizes every AI call into the same Supabase/SD Dialer sync path. */
public final class RebornCallSessionSync {
    private RebornCallSessionSync() {}

    public static void finalizeCallAsync(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> finalizeCall(app), "reborn-call-finalize").start();
    }

    public static boolean finalizeCall(Context context) {
        SharedPreferences c = context.getSharedPreferences("sofia_control", Context.MODE_PRIVATE);
        if (c.getBoolean("call_final_synced", false)) return true;

        long startedAt = c.getLong("call_started_at", 0L);
        if (startedAt <= 0L) return false;
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        String transcript = c.getString("live_transcript", "").trim();
        String phone = c.getString("dial_phone", "unknown");
        String agent = c.getString("agent_name", "SOFIA");
        String profileId = c.getString("agent_profile_id", "sofia_sales");
        String stage = c.getString("live_stage", "CALL_COMPLETED");
        boolean handoff = c.getBoolean("live_handoff", false);

        if (durationMs < 2500L && transcript.isEmpty()) return false;

        String interest;
        String result;
        String nextAction;
        if (handoff) {
            interest = "high";
            result = "interested";
            nextAction = "Contactar/continuar no SD Dialer e concluir a oportunidade.";
        } else if (transcript.length() >= 140) {
            interest = "medium";
            result = "qualified";
            nextAction = "Rever resumo e agendar follow-up se existir oportunidade.";
        } else {
            interest = "low";
            result = "completed";
            nextAction = "Rever chamada; sem handoff comercial explícito.";
        }

        String summary = buildSummary(agent, stage, durationMs, handoff, transcript);

        try {
            JSONObject payload = new JSONObject();
            payload.put("phone_number", phone);
            payload.put("client_name", "Cliente " + agent);
            payload.put("result", result);
            payload.put("handoff_requested", handoff);

            JSONObject facts = new JSONObject();
            facts.put("agent", agent);
            facts.put("agent_profile_id", profileId);
            facts.put("duration_ms", durationMs);
            payload.put("facts", facts);

            JSONObject feedback = new JSONObject();
            feedback.put("summary", summary);
            feedback.put("interest_level", interest);
            feedback.put("stage", stage);
            feedback.put("next_action", nextAction);
            feedback.put("transcript", transcript);
            feedback.put("source", "REBORN_AI_CALLING");
            payload.put("feedback", feedback);

            boolean ok = SupabaseSyncClient.sync(context, payload);
            if (ok) {
                c.edit()
                        .putBoolean("call_final_synced", true)
                        .putLong("call_finished_at", System.currentTimeMillis())
                        .putString("last_call_summary", summary)
                        .putString("last_call_next_action", nextAction)
                        .apply();
            }
            return ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String buildSummary(String agent, String stage, long durationMs, boolean handoff, String transcript) {
        long sec = durationMs / 1000L;
        StringBuilder s = new StringBuilder();
        s.append("Chamada REBORN AI com ").append(agent)
                .append(" · ").append(sec).append("s")
                .append(" · etapa ").append(stage == null || stage.isEmpty() ? "não definida" : stage).append(". ");
        if (handoff) s.append("Cliente demonstrou intenção comercial explícita e foi marcado para handoff.");
        else if (transcript != null && transcript.length() >= 140) s.append("Conversa com qualificação registada; rever dados e próxima ação no SD Dialer.");
        else s.append("Chamada concluída sem handoff explícito.");
        return s.toString();
    }
}
