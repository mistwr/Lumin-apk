package com.lumin.app;

import android.accessibilityservice.AccessibilityButtonController;
import android.content.SharedPreferences;
import android.os.Build;

public class SofiaOverlayAccessibilityService extends SofiaAccessibilityService {
    private SofiaCallOverlay overlay;
    private SharedPreferences control;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        control = getSharedPreferences("sofia_control", MODE_PRIVATE);
        if (!control.contains("shortcut_overlay_enabled")) {
            control.edit().putBoolean("shortcut_overlay_enabled", true).apply();
        }
        overlay = new SofiaCallOverlay(this);
        overlay.start();

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
        if (overlay != null) overlay.stop();
        super.onDestroy();
    }
}
