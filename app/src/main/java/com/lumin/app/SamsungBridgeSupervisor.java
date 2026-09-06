package com.lumin.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reliability supervisor for the Samsung Text Call transport.
 *
 * It deliberately knows nothing about Qwen or sales logic. Its only job is to keep
 * the Samsung transport alive across temporary UI loss, queue replies, and expose a
 * deterministic state machine to the REBORN overlay/diagnostics.
 */
public final class SamsungBridgeSupervisor {
    public enum State {
        NO_CALL,
        WAITING_SAMSUNG,
        VOICE_CALL,
        OPENING_TEXT_CALL,
        RECOVERING_TEXT_CALL,
        TEXT_CALL_READY,
        LISTENING,
        SENDING,
        VERIFYING,
        RETRY_QUEUED,
        STOPPED
    }

    public static final class PendingReply {
        public final long id;
        public final String text;
        public final long createdAt;
        int attempts;

        PendingReply(long id, String text) {
            this.id = id;
            this.text = text;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private static final long OPEN_RETRY_MS = 900L;
    private static final long SEND_RETRY_MS = 650L;
    private static final long STALE_IN_FLIGHT_MS = 3500L;
    private static final int MAX_QUEUE = 4;

    private final SharedPreferences prefs;
    private final Deque<PendingReply> queue = new ArrayDeque<>();
    private final AtomicLong ids = new AtomicLong(1L);

    private State state = State.WAITING_SAMSUNG;
    private boolean samsungVisible;
    private boolean textCallReady;
    private boolean inFlight;
    private long inFlightSince;
    private long lastOpenAttemptAt;
    private long lastSendAttemptAt;
    private long recoveryCount;
    private String lastReason = "";

    public SamsungBridgeSupervisor(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("sofia_control", Context.MODE_PRIVATE);
        publish();
    }

    public synchronized PendingReply enqueue(String text) {
        if (text == null) return null;
        String clean = text.trim();
        if (clean.isEmpty()) return null;

        // The central brain should normally produce one reply at a time. Keep a tiny queue
        // so a Samsung UI transition can never make a generated reply disappear.
        if (queue.size() >= MAX_QUEUE) queue.removeFirst();
        PendingReply p = new PendingReply(ids.getAndIncrement(), clean);
        queue.addLast(p);
        state = textCallReady ? State.RETRY_QUEUED : State.RECOVERING_TEXT_CALL;
        lastReason = "reply_queued";
        publish();
        return p;
    }

    public synchronized void onSamsungVisible(boolean visible) {
        samsungVisible = visible;
        if (!visible) {
            textCallReady = false;
            if (!queue.isEmpty()) enterRecovery("samsung_window_lost");
            else state = State.WAITING_SAMSUNG;
        } else if (!textCallReady && queue.isEmpty() && state == State.WAITING_SAMSUNG) {
            state = State.VOICE_CALL;
        }
        publish();
    }

    public synchronized void onTextCallReady(boolean ready) {
        textCallReady = ready;
        if (ready) {
            if (!inFlight) state = queue.isEmpty() ? State.LISTENING : State.TEXT_CALL_READY;
            lastReason = "text_call_ready";
        } else if (samsungVisible) {
            if (!queue.isEmpty() || inFlight) enterRecovery("editor_missing");
            else state = State.VOICE_CALL;
        }
        publish();
    }

    public synchronized void onVoiceModeDetected() {
        textCallReady = false;
        enterRecovery("voice_mode_detected");
    }

    public synchronized boolean shouldAttemptOpen(long now) {
        if (!samsungVisible || textCallReady) return false;
        if (now - lastOpenAttemptAt < OPEN_RETRY_MS) return false;
        lastOpenAttemptAt = now;
        state = queue.isEmpty() ? State.OPENING_TEXT_CALL : State.RECOVERING_TEXT_CALL;
        publish();
        return true;
    }

    public synchronized PendingReply nextForSend(long now) {
        watchdog(now);
        if (!samsungVisible || !textCallReady || inFlight || queue.isEmpty()) return null;
        if (now - lastSendAttemptAt < SEND_RETRY_MS) return null;
        PendingReply p = queue.peekFirst();
        if (p == null) return null;
        p.attempts++;
        lastSendAttemptAt = now;
        inFlight = true;
        inFlightSince = now;
        state = State.SENDING;
        lastReason = "send_attempt_" + p.attempts;
        publish();
        return p;
    }

    public synchronized void markVerifying(long replyId) {
        PendingReply p = queue.peekFirst();
        if (p == null || p.id != replyId) return;
        state = State.VERIFYING;
        lastReason = "verifying";
        publish();
    }

    public synchronized void confirmSent(long replyId) {
        PendingReply p = queue.peekFirst();
        if (p != null && p.id == replyId) queue.removeFirst();
        inFlight = false;
        inFlightSince = 0L;
        state = queue.isEmpty() ? State.LISTENING : State.TEXT_CALL_READY;
        lastReason = "send_confirmed";
        publish();
    }

    public synchronized void retry(long replyId, String reason) {
        PendingReply p = queue.peekFirst();
        if (p == null || p.id != replyId) return;
        inFlight = false;
        inFlightSince = 0L;
        lastReason = reason == null ? "retry" : reason;
        if (!textCallReady) enterRecovery(lastReason);
        else state = State.RETRY_QUEUED;
        publish();
    }

    public synchronized void watchdog(long now) {
        if (inFlight && inFlightSince > 0 && now - inFlightSince > STALE_IN_FLIGHT_MS) {
            inFlight = false;
            inFlightSince = 0L;
            enterRecovery("send_watchdog_timeout");
        }
    }

    public synchronized void resetSession() {
        queue.clear();
        samsungVisible = false;
        textCallReady = false;
        inFlight = false;
        inFlightSince = 0L;
        lastOpenAttemptAt = 0L;
        lastSendAttemptAt = 0L;
        state = State.NO_CALL;
        lastReason = "session_reset";
        publish();
    }

    public synchronized boolean hasPending() { return !queue.isEmpty(); }
    public synchronized int pendingCount() { return queue.size(); }
    public synchronized State state() { return state; }
    public synchronized String stateName() { return state.name(); }
    public synchronized long recoveryCount() { return recoveryCount; }

    private void enterRecovery(String reason) {
        state = State.RECOVERING_TEXT_CALL;
        lastReason = reason;
        recoveryCount++;
    }

    private void publish() {
        PendingReply p = queue.peekFirst();
        prefs.edit()
                .putString("bridge_state", state.name())
                .putString("turn_state", state.name())
                .putInt("bridge_pending_count", queue.size())
                .putLong("bridge_pending_id", p == null ? 0L : p.id)
                .putString("bridge_pending_text", p == null ? "" : p.text)
                .putBoolean("bridge_samsung_visible", samsungVisible)
                .putBoolean("bridge_text_call_ready", textCallReady)
                .putBoolean("bridge_send_in_flight", inFlight)
                .putLong("bridge_recovery_count", recoveryCount)
                .putString("bridge_reason", lastReason)
                .apply();
    }
}
