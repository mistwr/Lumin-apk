package com.lumin.app;

import android.content.Context;
import android.telecom.Call;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Packages the finished call for SD Dialer/Supabase sync. */
public final class RebornCrmController {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private RebornCrmController() {}

    public static void finish(Context context, Call call) {
        final Context app = context.getApplicationContext();
        final String phone = phoneOf(call);
        final String transcript = RebornCentral.transcript();
        final String lastCustomer = RebornCentral.lastCustomer();
        final String lastReply = RebornCentral.lastReply();
        final long latency = RebornCentral.lastLatencyMs();

        WORKER.submit(() -> {
            try {
                JSONObject facts = new JSONObject();
                facts.put("last_customer", lastCustomer == null ? "" : lastCustomer);
                facts.put("last_reply", lastReply == null ? "" : lastReply);
                facts.put("qwen_backend", LocalRebornEngine.backendName());
                facts.put("last_latency_ms", latency);
                facts.put("stt_state", RebornTranscriptionService.state());
                facts.put("voice_route", RebornVoiceController.route());

                JSONObject feedback = new JSONObject();
                feedback.put("summary", buildSummary(lastCustomer, transcript));
                feedback.put("intent", inferIntent(transcript));
                feedback.put("stage", RebornCentral.stage());
                feedback.put("transcript", transcript == null ? "" : transcript);
                feedback.put("next_action", inferNextAction(transcript));

                JSONObject payload = new JSONObject();
                payload.put("phone_number", phone.isEmpty() ? "unknown" : phone);
                payload.put("client_name", "Cliente REBORN");
                payload.put("result", inferResult(transcript));
                payload.put("facts", facts);
                payload.put("feedback", feedback);
                payload.put("handoff_requested", isHot(transcript));
                payload.put("requested_keyword", isHot(transcript) ? "poupar" : JSONObject.NULL);

                boolean synced = SupabaseSyncClient.sync(app, payload);
                app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                        .putString("crm_state", synced ? "SYNCED" : "QUEUED")
                        .putString("crm_phone", phone)
                        .putString("crm_summary", feedback.optString("summary"))
                        .apply();
            } catch (Throwable t) {
                app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                        .putString("crm_state", "ERROR")
                        .putString("crm_error", t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage()))
                        .apply();
            }
        });
    }

    private static String phoneOf(Call call) {
        try {
            if (call != null && call.getDetails() != null && call.getDetails().getHandle() != null) {
                String p = call.getDetails().getHandle().getSchemeSpecificPart();
                return p == null ? "" : p;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String buildSummary(String lastCustomer, String transcript) {
        String base = lastCustomer == null || lastCustomer.trim().isEmpty()
                ? "Chamada REBORN concluída."
                : "Última indicação do cliente: " + lastCustomer.trim();
        if (transcript != null && transcript.toLowerCase().contains("energia")) base += " Interesse/tema de energia detetado.";
        if (transcript != null && (transcript.toLowerCase().contains("meo") || transcript.toLowerCase().contains("vodafone") || transcript.toLowerCase().contains("nos") || transcript.toLowerCase().contains("digi"))) base += " Tema de telecomunicações detetado.";
        return base;
    }

    private static boolean isHot(String transcript) {
        if (transcript == null) return false;
        String t = transcript.toLowerCase();
        return t.contains("quero aderir") || t.contains("quero avançar") || t.contains("tenho interesse") || t.contains("pode enviar") || t.contains("pode ligar");
    }

    private static String inferIntent(String transcript) {
        if (isHot(transcript)) return "INTERESTED";
        if (transcript == null || transcript.trim().isEmpty()) return "NO_TRANSCRIPT";
        String t = transcript.toLowerCase();
        if (t.contains("não quero") || t.contains("nao quero") || t.contains("não estou interessado") || t.contains("nao estou interessado")) return "NOT_INTERESTED";
        return "CONTINUE";
    }

    private static String inferResult(String transcript) {
        if (isHot(transcript)) return "interested";
        String i = inferIntent(transcript);
        if ("NOT_INTERESTED".equals(i)) return "not_interested";
        return "qualified";
    }

    private static String inferNextAction(String transcript) {
        if (isHot(transcript)) return "commercial_handoff";
        if (transcript != null && (transcript.toLowerCase().contains("mais tarde") || transcript.toLowerCase().contains("amanhã") || transcript.toLowerCase().contains("amanha"))) return "callback";
        return "follow_up";
    }
}
