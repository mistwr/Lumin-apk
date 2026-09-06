package com.lumin.app;

import android.accessibilityservice.AccessibilityButtonController;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.TelecomManager;

/**
 * Registered accessibility service used while Samsung Phone remains the default dialer.
 *
 * When Samsung is the default phone app, RebornInCallService may not be bound, so the
 * VOICE_CALL PCM/STT listener must be started independently. Samsung Text Call remains
 * the mouth; REBORN PCM/STT + Qwen are the ears and brain.
 */
public class SofiaOverlayAccessibilityService extends SofiaAccessibilityService {
    private SofiaCallOverlay overlay;
    private SharedPreferences control;
    private final Handler callWatchHandler = new Handler(Looper.getMainLooper());
    private boolean rebornListeningForSamsungCall;

    private final Runnable callWatch = new Runnable() {
        @Override public void run() {
            try {
                TelecomManager telecom = (TelecomManager) getSystemService(TELECOM_SERVICE);
                boolean inCall = telecom != null && telecom.isInCall();

                if (inCall && !rebornListeningForSamsungCall) {
                    rebornListeningForSamsungCall = true;

                    RebornCentral.init(SofiaOverlayAccessibilityService.this);
                    RebornCentral.startSession(SofiaOverlayAccessibilityService.this);

                    RebornVoiceController.setRoute(
                            SofiaOverlayAccessibilityService.this,
                            RebornVoiceController.ROUTE_SAMSUNG);

                    RebornCallAudioController.start(SofiaOverlayAccessibilityService.this);

                    getSharedPreferences("sofia_diag", MODE_PRIVATE).edit()
                            .putString("listener_owner", "ACCESSIBILITY_CALL_WATCH")
                            .putString("listener_state", "STARTED_WITH_SAMSUNG_DEFAULT")
                            .apply();
                } else if (!inCall && rebornListeningForSamsungCall) {
                    rebornListeningForSamsungCall = false;
                    RebornCallAudioController.stop();
                    RebornCentral.onCallState(
                            SofiaOverlayAccessibilityService.this,
                            Call.STATE_DISCONNECTED);

                    getSharedPreferences("sofia_diag", MODE_PRIVATE).edit()
                            .putString("listener_state", "STOPPED_CALL_ENDED")
                            .apply();
                }
            } catch (SecurityException denied) {
                getSharedPreferences("sofia_diag", MODE_PRIVATE).edit()
                        .putString("listener_state", "CALL_WATCH_PERMISSION_DENIED")
                        .putString("listener_error", String.valueOf(denied.getMessage()))
                        .apply();
            } catch (Throwable t) {
                getSharedPreferences("sofia_diag", MODE_PRIVATE).edit()
                        .putString("listener_state", "CALL_WATCH_ERROR")
                        .putString("listener_error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()))
                        .apply();
            }

            callWatchHandler.postDelayed(this, 500L);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (!control.contains("shortcut_overlay_enabled")) {
            control.edit().putBoolean("shortcut_overlay_enabled", true).apply();
        }

        control.edit().putString("voice_route_mode", RebornVoiceController.ROUTE_SAMSUNG).apply();

        overlay = new SofiaCallOverlay(this);
        overlay.start();

        callWatchHandler.removeCallbacks(callWatch);
        callWatchHandler.post(callWatch);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                AccessibilityButtonController controller = getAccessibilityButtonController();
                controller.registerAccessibilityButtonCallback(
                        new AccessibilityButtonController.AccessibilityButtonCallback() {
                            @Override public void onClicked(AccessibilityButtonController controller) {
                                boolean enabled = SofiaShortcutController.toggle(control);
                                if (overlay != null) overlay.onShortcutChanged(enabled);
                            }
                        }
                );
            } catch (Exception ignored) {}
        }
    }

    @Override public void onDestroy() {
        callWatchHandler.removeCallbacksAndMessages(null);
        if (rebornListeningForSamsungCall) {
            rebornListeningForSamsungCall = false;
            RebornCallAudioController.stop();
        }
        if (overlay != null) overlay.stop();
        super.onDestroy();
    }
}
