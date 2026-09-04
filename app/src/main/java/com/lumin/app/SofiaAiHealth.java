package com.lumin.app;

import android.content.Context;

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

    public static Result check(Context context) {
        long start = System.currentTimeMillis();
        try {
            if (!LocalRebornEngine.isInstalled(context)) {
                return new Result(false, System.currentTimeMillis() - start,
                        "Modelo Qwen3 local não instalado");
            }

            LocalRebornEngine.ensureReady(context);

            // Real inference smoke-test: READY only means the runtime loaded.
            // We generate a tiny answer so the UI proves the model can actually infer on-device.
            String reply = LocalRebornEngine.generate(
                    context,
                    "Responde apenas com a palavra OK."
            );
            long latency = System.currentTimeMillis() - start;

            if (reply == null || reply.trim().isEmpty()) {
                return new Result(false, latency,
                        "Qwen3 carregou mas não gerou resposta");
            }

            String sample = reply.trim().replace('\n', ' ');
            if (sample.length() > 80) sample = sample.substring(0, 80) + "…";
            return new Result(true, latency,
                    "Qwen3 inferência OK · resposta: " + sample + " · 0€ por resposta");
        } catch (Throwable ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new Result(false, System.currentTimeMillis() - start, msg);
        }
    }
}
