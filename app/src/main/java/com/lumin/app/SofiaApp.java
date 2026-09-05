package com.lumin.app;

import android.app.Application;
import android.content.Context;

public class SofiaApp extends Application {
    private static Context appContext;

    @Override public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();

        // Preload the local GGUF model in the background so the first real
        // customer turn does not pay the model-load latency.
        new Thread(() -> {
            try {
                if (LocalQwenManager.isInstalled(appContext)) {
                    LocalQwenManager.healthBlocking(appContext);
                }
            } catch (Throwable ignored) {}
        }, "sofia-ai-prewarm").start();
    }

    public static Context context() {
        if (appContext == null) throw new IllegalStateException("SOFIA app context not ready");
        return appContext;
    }
}
