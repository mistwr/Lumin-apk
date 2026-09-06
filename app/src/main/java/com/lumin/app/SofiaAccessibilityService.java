package com.lumin.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Samsung Text Call transport bridge. The REBORN brain lives elsewhere. */
public class SofiaAccessibilityService extends AccessibilityService {
    public static final String ACTION_SEND_REPLY = "com.lumin.app.SEND_REPLY";
    public static final String EXTRA_REPLY = "reply";

    private static final String SAMSUNG_INCALL = "com.samsung.android.incallui";
    private static final String AUTO_INTRO = "Olá, boa tarde. Sou a assistente virtual da MY POUPar+. É uma chamada rápida para ajudar a perceber se os seus serviços de energia ou telecomunicações continuam competitivos. Posso explicar em vinte segundos?";
    private static final long POLL_MS = 300L;
    private static final long STABLE_MS = 1050L;
    private static final long DUPLICATE_WINDOW_MS = 12000L;
    private static final long SESSION_GONE_MS = 5000L;
    private static final long OPEN_STEP_COOLDOWN_MS = 1500L;

    private enum TurnState { IDLE, LISTENING, STABILIZING, THINKING, SENDING, WAITING_REMOTE, MANUAL }

    private static final class TextCandidate {
        final String text;
        final Rect bounds;
        final String id;
        final String desc;
        TextCandidate(String text, Rect bounds, String id, String desc) {
            this.text = text;
            this.bounds = bounds;
            this.id = id;
            this.desc = desc;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SofiaMemory memory = new SofiaMemory();

    private SharedPreferences diag;
    private SharedPreferences control;
    private RebornConversationOrchestrator brain;

    private volatile TurnState turnState = TurnState.IDLE;
    private boolean destroyed;
    private boolean autoIntroSent;
    private long lastSamsungSeenAt;
    private long lastTextCallReadyAt;
    private long lastAutoOpenAttemptAt;
    private long lastMenuExpandAttemptAt;
    private String observedCandidate = "";
    private long observedChangedAt;
    private String lastProcessedCanonical = "";
    private long lastProcessedAt;
    private String lastCustomer = "";
    private String customerBaselineCanonical = "";
    private String transcript = "";

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_SEND_REPLY.equals(intent.getAction())) return;
            String reply = intent.getStringExtra(EXTRA_REPLY);
            if (reply == null || reply.trim().isEmpty()) return;
            if (brain != null) brain.setAssistantMessage(reply.trim());
            setTurnState(TurnState.SENDING);
            sendReply(reply.trim(), true);
        }
    };

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try { inspectSamsungSurface("POLL"); }
            catch (Throwable t) { log("poll_error", t.getClass().getSimpleName() + ": " + safe(t.getMessage())); }
            main.postDelayed(this, POLL_MS);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (!control.contains("mode")) control.edit().putString("mode", "AUTO").apply();
        brain = new RebornConversationOrchestrator(this, memory);

        IntentFilter filter = new IntentFilter(ACTION_SEND_REPLY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(commandReceiver, filter);

        destroyed = false;
        setTurnState(TurnState.IDLE);
        log("service", "ATIVO · BRIDGE V3 · POSITION-AWARE · POLLING 300ms");
        main.removeCallbacks(poller);
        main.post(poller);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!SAMSUNG_INCALL.contentEquals(event.getPackageName())) return;
        inspectSamsungSurface("EVENT");
    }

    private void inspectSamsungSurface(String source) {
        AccessibilityNodeInfo root = findSamsungRoot();
        long now = System.currentTimeMillis();
        if (root == null) {
            if (lastSamsungSeenAt > 0 && now - lastSamsungSeenAt > SESSION_GONE_MS) resetSessionSurface();
            return;
        }
        lastSamsungSeenAt = now;

        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) {
            tryOpenTextCall(root);
            log("surface", "Samsung aberto · auto-open Text Call · " + source);
            return;
        }

        lastTextCallReadyAt = now;
        log("surface", "TEXT_CALL_READY · " + source);
        String currentMode = mode();

        if ("MANUAL".equals(currentMode)) {
            setTurnState(TurnState.MANUAL);
            captureManualCustomer(root, edit);
            return;
        }
        if (turnState == TurnState.IDLE || turnState == TurnState.MANUAL) setTurnState(TurnState.LISTENING);

        if ("AUTO".equals(currentMode) && !autoIntroSent) {
            autoIntroSent = true;
            if (brain != null) brain.setAssistantMessage(AUTO_INTRO);
            appendTranscript("REBORN", AUTO_INTRO);
            control.edit().putString("suggested_reply", AUTO_INTRO).apply();
            log("brain_path", "SCRIPTED_INTRO");
            setTurnState(TurnState.SENDING);
            sendReply(AUTO_INTRO, false);
            return;
        }

        if (turnState == TurnState.THINKING || turnState == TurnState.SENDING || turnState == TurnState.WAITING_REMOTE) return;
        if (brain != null && brain.isBusy()) {
            setTurnState(TurnState.THINKING);
            return;
        }

        String candidate = clean(findCustomerCandidate(root, edit));
        if (candidate.isEmpty()) {
            if (turnState == TurnState.STABILIZING) setTurnState(TurnState.LISTENING);
            observedCandidate = "";
            observedChangedAt = 0L;
            return;
        }

        String canon = canonical(candidate);
        String lastAssistant = brain == null ? memory.getLastAssistant() : brain.lastReply();
        if (!lastAssistant.isEmpty() && canon.equals(canonical(lastAssistant))) return;
        if (!customerBaselineCanonical.isEmpty() && canon.equals(customerBaselineCanonical)) return;
        if (canon.equals(lastProcessedCanonical) && now - lastProcessedAt < DUPLICATE_WINDOW_MS) return;

        if (!canon.equals(canonical(observedCandidate))) {
            observedCandidate = candidate;
            observedChangedAt = now;
            control.edit().putString("live_customer_partial", candidate).apply();
            setTurnState(TurnState.STABILIZING);
            log("stable", "NOVO: " + candidate);
            return;
        }

        if (observedChangedAt > 0 && now - observedChangedAt >= STABLE_MS) {
            String stable = observedCandidate;
            observedCandidate = "";
            observedChangedAt = 0L;
            processCustomerTurn(stable);
        }
    }

    private void processCustomerTurn(String customer) {
        String canon = canonical(customer);
        if (canon.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (canon.equals(lastProcessedCanonical) && now - lastProcessedAt < DUPLICATE_WINDOW_MS) return;

        customerBaselineCanonical = "";
        lastProcessedCanonical = canon;
        lastProcessedAt = now;
        lastCustomer = customer;
        control.edit().putString("live_customer", customer).putString("live_customer_partial", "").apply();
        log("last_customer", customer);
        appendTranscript("Cliente", customer);

        final String currentMode = mode();
        if ("MANUAL".equals(currentMode)) {
            setTurnState(TurnState.MANUAL);
            return;
        }
        if (brain == null) {
            failSend("REBORN brain não inicializado");
            return;
        }

        boolean accepted = brain.acceptStableTurn(customer, currentMode, new RebornConversationOrchestrator.Callback() {
            @Override public void onThinking() {
                setTurnState(TurnState.THINKING);
                control.edit().putString("suggested_reply", "").apply();
                log("brain", "THINKING");
            }

            @Override public void onReply(String reply, boolean assisted) {
                String cleanReply = clean(reply);
                if (cleanReply.isEmpty()) {
                    log("brain", "IGNORED_AS_AMBIENT_OR_UNRELATED");
                    setTurnState(TurnState.LISTENING);
                    return;
                }
                control.edit().putString("suggested_reply", cleanReply).apply();
                log("brain", "REPLY_READY");
                if (assisted) {
                    setTurnState(TurnState.LISTENING);
                    log("send", "WAITING_USER_APPROVAL");
                    return;
                }
                appendTranscript("REBORN", cleanReply);
                setTurnState(TurnState.SENDING);
                sendReply(cleanReply, false);
            }

            @Override public void onError(String message) { log("brain_error", safe(message)); }
        });

        if (!accepted) {
            if (brain.isBusy()) setTurnState(TurnState.THINKING);
            else setTurnState(TurnState.LISTENING);
            log("brain", "TURN_IGNORED_OR_DUPLICATE");
        }
    }

    private void captureManualCustomer(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        String c = clean(findCustomerCandidate(root, edit));
        if (c.isEmpty()) return;
        String canon = canonical(c);
        if (canon.equals(canonical(lastCustomer))) return;
        lastCustomer = c;
        control.edit().putString("live_customer", c).apply();
    }

    /** Two-stage auto-open: direct Text Call first, then Call Assistant/More menu. */
    private boolean tryOpenTextCall(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        AccessibilityNodeInfo direct = findTextCallEntry(root);
        if (direct != null && now - lastAutoOpenAttemptAt >= OPEN_STEP_COOLDOWN_MS) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(direct);
            if (clickable != null) {
                lastAutoOpenAttemptAt = now;
                boolean clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                log("auto_open", clicked ? "TEXT_CALL_CLICKED" : "TEXT_CALL_CLICK_FAILED");
                if (clicked) return true;
            }
        }

        if (now - lastMenuExpandAttemptAt < 2200L) return false;
        AccessibilityNodeInfo launcher = findCallAssistantLauncher(root);
        if (launcher == null) launcher = findMoreLauncher(root);
        if (launcher == null) return false;
        AccessibilityNodeInfo clickable = clickableSelfOrParent(launcher);
        if (clickable == null) return false;
        lastMenuExpandAttemptAt = now;
        boolean clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        log("auto_open", clicked ? "ASSIST_MENU_EXPANDED" : "ASSIST_MENU_EXPAND_FAILED");
        return clicked;
    }

    private AccessibilityNodeInfo findTextCallEntry(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = canonical(value(node.getText()));
        String desc = canonical(value(node.getContentDescription()));
        String id = lower(node.getViewIdResourceName());
        boolean match = text.contains("chamada de texto") || text.contains("text call") || text.contains("bixby text call") ||
                desc.contains("chamada de texto") || desc.contains("text call") || desc.contains("bixby text call") ||
                id.contains("text_call") || id.contains("bixby_text_call");
        if (match) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findTextCallEntry(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findCallAssistantLauncher(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String t = canonical(value(node.getText()));
        String d = canonical(value(node.getContentDescription()));
        String id = lower(node.getViewIdResourceName());
        if (t.contains("assistente de chamada") || d.contains("assistente de chamada") ||
                t.contains("call assistant") || d.contains("call assistant") ||
                id.contains("call_assist") || id.contains("callassistant")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findCallAssistantLauncher(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findMoreLauncher(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String t = canonical(value(node.getText()));
        String d = canonical(value(node.getContentDescription()));
        String id = lower(node.getViewIdResourceName());
        boolean match = t.equals("mais") || t.equals("more") || d.equals("mais") || d.equals("more") ||
                id.endsWith("/more") || id.contains("more_button") || id.contains("overflow");
        if (match && clickableSelfOrParent(node) != null) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findMoreLauncher(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** Samsung renders remote/customer transcript bubbles on the left and our Text Call replies on the right. */
    private String findCustomerCandidate(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        List<TextCandidate> items = new ArrayList<>();
        collectTexts(root, items, edit);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        Rect editor = new Rect();
        edit.getBoundsInScreen(editor);
        String lastAssistant = brain == null ? memory.getLastAssistant() : brain.lastReply();

        for (int i = items.size() - 1; i >= 0; i--) {
            TextCandidate c = items.get(i);
            String s = clean(c.text);
            if (s.length() < 2 || s.length() > 300) continue;
            String l = canonical(s);
            if (isSamsungChrome(l)) continue;
            if (!lastAssistant.isEmpty() && l.equals(canonical(lastAssistant))) continue;
            if (l.equals(canonical(AUTO_INTRO))) continue;
            if (!c.bounds.isEmpty()) {
                if (c.bounds.centerX() > (int) (screenWidth * 0.62f)) continue; // our/right bubble
                if (!editor.isEmpty() && c.bounds.top >= editor.top - dp(30)) continue; // chips/editor chrome
            }
            String meta = canonical(c.id + " " + c.desc);
            if (meta.contains("outgoing") || meta.contains("my message") || meta.contains("sender") || meta.contains("assistant reply")) continue;
            log("candidate", "REMOTE_LEFT · " + s);
            return s;
        }
        return "";
    }

    private void collectTexts(AccessibilityNodeInfo node, List<TextCandidate> out, AccessibilityNodeInfo edit) {
        if (node == null) return;
        if (node != edit && !node.isEditable() && node.getText() != null) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            out.add(new TextCandidate(node.getText().toString(), r,
                    value(node.getViewIdResourceName()), value(node.getContentDescription())));
        }
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), out, edit);
    }

    private boolean isSamsungChrome(String l) {
        return l.isEmpty() || l.equals("escrever resposta") || l.equals("escrever") || l.equals("enviar") || l.equals("send") ||
                l.equals("repetir") || l.equals("urgente") || l.equals("quem fala") || l.equals("mais") ||
                l.contains("ligar lhe mais tarde") || l.contains("chamada de texto") || l.contains("text call") ||
                l.contains("mudar para chamada") || l.contains("assistente de chamada") || l.contains("assistente de voz") ||
                l.contains("converter a sua voz em texto") || l.contains("mantenha se em linha") || l.contains("se quiser continuar") ||
                l.contains("desligar") || l.contains("teclado") || l.contains("altifalante") || l.contains("bluetooth") ||
                l.contains("adicionar chamada") || l.contains("mensagem sugerida") || l.contains("sugestao") ||
                l.matches("\\d{1,2} \\d{2}") || l.matches("\\d{1,2} \\d{2} \\d{2}");
    }

    private void sendReply(String reply, boolean userApproved) {
        main.post(() -> {
            AccessibilityNodeInfo root = findSamsungRoot();
            AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
            if (root == null || edit == null) { failSend("Samsung Text Call/editor não acessível"); return; }

            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
            boolean set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            log("last_reply", reply);
            log("set_text", String.valueOf(set));
            if (!set) { failSend("SET_TEXT_FAILED"); return; }

            if (userApproved) {
                if (brain != null) brain.setAssistantMessage(reply);
                appendTranscript("REBORN", reply);
            }
            main.postDelayed(() -> pressSend(reply, 0), 260L);
        });
    }

    private void pressSend(String expectedReply, int attempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        if (root == null || edit == null) { failSend("pressSend sem Samsung/editor"); return; }

        AccessibilityNodeInfo send = findSendButton(root);
        if (send == null) send = findSendButtonNearEditor(root, edit);
        if (send != null) {
            AccessibilityNodeInfo clickable = clickableSelfOrParent(send);
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("send", "CLICK_SENT");
                main.postDelayed(() -> verifySent(expectedReply, attempt + 1), 500L);
                return;
            }
            Rect r = new Rect();
            send.getBoundsInScreen(r);
            if (!r.isEmpty()) {
                tapBounds(r, "GESTURE_SEND");
                main.postDelayed(() -> verifySent(expectedReply, attempt + 1), 550L);
                return;
            }
        }
        if (attempt == 0 && Build.VERSION.SDK_INT >= 30) {
            boolean enter = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            log("send", enter ? "IME_ENTER" : "IME_ENTER_FAILED");
            main.postDelayed(() -> verifySent(expectedReply, 1), 500L);
            return;
        }
        tapSendRightOfEditor(edit);
        main.postDelayed(() -> verifySent(expectedReply, attempt + 1), 600L);
    }

    private void verifySent(String expectedReply, int nextAttempt) {
        AccessibilityNodeInfo root = findSamsungRoot();
        AccessibilityNodeInfo edit = root == null ? null : findEditable(root);
        String current = edit == null || edit.getText() == null ? "" : edit.getText().toString().trim();
        if (current.isEmpty() || !current.equals(expectedReply.trim())) {
            log("send", "SEND_CONFIRMED");
            customerBaselineCanonical = canonical(lastCustomer);
            observedCandidate = "";
            observedChangedAt = 0L;
            control.edit().putString("suggested_reply", "").apply();
            setTurnState(TurnState.WAITING_REMOTE);
            main.postDelayed(() -> {
                if (!"MANUAL".equals(mode())) setTurnState(TurnState.LISTENING);
            }, 650L);
            return;
        }
        if (nextAttempt <= 2) {
            log("send", "NOT_SENT_ATTEMPT_" + nextAttempt);
            pressSend(expectedReply, nextAttempt);
        } else {
            if (AUTO_INTRO.equals(expectedReply)) autoIntroSent = false;
            failSend("Mensagem ficou no editor Samsung; envio não confirmado");
        }
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = lower(node.getText());
        String desc = lower(node.getContentDescription());
        String id = lower(node.getViewIdResourceName());
        if (text.contains("enviar") || text.equals("send") || desc.contains("enviar") || desc.contains("send") ||
                id.contains("send") || id.contains("enter") || id.contains("text_call_send") || id.contains("message_send")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findSendButton(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findSendButtonNearEditor(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        Rect editor = new Rect();
        edit.getBoundsInScreen(editor);
        if (editor.isEmpty()) return null;
        AccessibilityNodeInfo[] best = new AccessibilityNodeInfo[1];
        int[] score = new int[]{Integer.MAX_VALUE};
        findSendCandidateRecursive(root, editor, best, score);
        return best[0];
    }

    private void findSendCandidateRecursive(AccessibilityNodeInfo node, Rect editor, AccessibilityNodeInfo[] best, int[] bestScore) {
        if (node == null) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        AccessibilityNodeInfo clickable = clickableSelfOrParent(node);
        if (!r.isEmpty() && clickable != null) {
            int cx = r.centerX();
            int cy = r.centerY();
            boolean right = cx >= editor.centerX();
            boolean row = cy >= editor.top - dp(70) && cy <= editor.bottom + dp(70);
            boolean size = r.width() >= dp(20) && r.height() >= dp(20) && r.width() <= dp(180) && r.height() <= dp(180);
            if (right && row && size) {
                int s = Math.abs(cx - editor.right) + Math.abs(cy - editor.centerY()) * 2;
                if (s < bestScore[0]) { bestScore[0] = s; best[0] = node; }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) findSendCandidateRecursive(node.getChild(i), editor, best, bestScore);
    }

    private void tapBounds(Rect r, String label) {
        if (Build.VERSION.SDK_INT < 24) return;
        Path path = new Path();
        path.moveTo(r.centerX(), r.centerY());
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 80);
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        log("send", ok ? label : label + "_FAILED");
    }

    private void tapSendRightOfEditor(AccessibilityNodeInfo edit) {
        Rect r = new Rect();
        edit.getBoundsInScreen(r);
        if (r.isEmpty()) { failSend("EDITOR_BOUNDS_EMPTY"); return; }
        int width = getResources().getDisplayMetrics().widthPixels;
        float x = Math.min(width - dp(26), r.right + dp(34));
        float y = r.centerY();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 90);
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        log("send", ok ? "EDITOR_RIGHT_FALLBACK" : "EDITOR_RIGHT_FALLBACK_FAILED");
    }

    private AccessibilityNodeInfo findSamsungRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (isSamsungRoot(active)) return active;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo root = w == null ? null : w.getRoot();
                    if (isSamsungRoot(root)) return root;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isSamsungRoot(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null && SAMSUNG_INCALL.contentEquals(root.getPackageName());
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private void appendTranscript(String who, String text) {
        transcript += who + ": " + text + "\n";
        if (transcript.length() > 12000) transcript = transcript.substring(transcript.length() - 12000);
        control.edit().putString("live_transcript", transcript).apply();
    }

    private String mode() {
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        return control.getString("mode", "AUTO");
    }

    private void setTurnState(TurnState state) {
        if (turnState == state) return;
        turnState = state;
        if (control == null) control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        control.edit().putString("turn_state", state.name()).apply();
        log("turn_state", state.name());
    }

    private void failSend(String reason) {
        log("last_error", reason);
        log("send", "FAILED");
        setTurnState("MANUAL".equals(mode()) ? TurnState.MANUAL : TurnState.LISTENING);
    }

    private void resetSessionSurface() {
        if (turnState == TurnState.IDLE && transcript.isEmpty()) return;
        autoIntroSent = false;
        observedCandidate = "";
        observedChangedAt = 0L;
        lastProcessedCanonical = "";
        lastProcessedAt = 0L;
        lastCustomer = "";
        customerBaselineCanonical = "";
        transcript = "";
        if (brain != null) brain.reset();
        control.edit().putString("live_customer", "").putString("live_customer_partial", "")
                .putString("suggested_reply", "").putString("live_transcript", "").apply();
        setTurnState(TurnState.IDLE);
        lastSamsungSeenAt = 0L;
        lastTextCallReadyAt = 0L;
    }

    private String canonical(String s) {
        if (s == null) return "";
        String x = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return x.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9€]+", " ").trim();
    }
    private String clean(String s) { return s == null ? "" : s.replace('\n', ' ').replaceAll("\\s+", " ").trim(); }
    private String lower(CharSequence s) { return s == null ? "" : s.toString().toLowerCase(Locale.ROOT); }
    private String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
    private String safe(String s) { return s == null ? "" : s.replace('\n', ' '); }
    private String value(CharSequence s) { return s == null ? "" : s.toString(); }
    private String value(String s) { return s == null ? "" : s; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private void log(String key, String value) {
        if (diag == null) diag = getSharedPreferences("sofia_diag", MODE_PRIVATE);
        diag.edit().putString(key, value == null ? "" : value).putLong("updated", System.currentTimeMillis()).apply();
    }

    @Override public void onInterrupt() { log("service", "INTERRUPTED"); }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(commandReceiver); } catch (Throwable ignored) {}
        if (brain != null) brain.shutdown();
        super.onDestroy();
    }
}
