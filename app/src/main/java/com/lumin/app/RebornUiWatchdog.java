package com.lumin.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.Map;

/** Lightweight watchdog used only to diagnose call-screen stalls without blocking the UI. */
public final class RebornUiWatchdog {
    private static volatile boolean running;
    private static volatile long lastBeat;
    private static Handler main;
    private static Context app;
    private static Thread worker;

    private RebornUiWatchdog() {}

    public static synchronized void start(Context context) {
        if (running) return;
        app = context.getApplicationContext();
        main = new Handler(Looper.getMainLooper());
        running = true;
        lastBeat = System.currentTimeMillis();
        main.post(beat);
        worker = new Thread(RebornUiWatchdog::loop, "reborn-ui-watchdog");
        worker.setDaemon(true);
        worker.start();
    }

    public static synchronized void stop() {
        running = false;
        if (main != null) main.removeCallbacks(beat);
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private static final Runnable beat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            lastBeat = System.currentTimeMillis();
            main.postDelayed(this, 500L);
        }
    };

    private static void loop() {
        while (running) {
            try { Thread.sleep(1000L); } catch (InterruptedException ignored) { }
            if (!running) break;
            long lag = System.currentTimeMillis() - lastBeat;
            if (lag < 2500L) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("UI_BLOCKED_").append(lag).append("ms\n");
            try {
                Thread mainThread = Looper.getMainLooper().getThread();
                StackTraceElement[] stack = mainThread.getStackTrace();
                int limit = Math.min(stack.length, 18);
                for (int i = 0; i < limit; i++) sb.append(stack[i]).append('\n');
            } catch (Throwable t) {
                sb.append("watchdog_error=").append(t.getClass().getSimpleName());
            }
            save(sb.toString());
            try { Thread.sleep(3000L); } catch (InterruptedException ignored) { }
        }
    }

    private static void save(String text) {
        Context c = app;
        if (c == null) return;
        c.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                .putString("ui_watchdog_state", "BLOCKED")
                .putString("ui_watchdog_trace", text)
                .putLong("ui_watchdog_at", System.currentTimeMillis())
                .apply();
    }
}
