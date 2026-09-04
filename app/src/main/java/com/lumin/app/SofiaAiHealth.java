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
                        "Modelo local não instalado");
            }
            LocalRebornEngine.ensureReady(context);
            return new Result(true, System.currentTimeMillis() - start,
                    "Gemma local pronto · 0€ por resposta");
        } catch (Throwable ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new Result(false, System.currentTimeMillis() - start, msg);
        }
    }
}
