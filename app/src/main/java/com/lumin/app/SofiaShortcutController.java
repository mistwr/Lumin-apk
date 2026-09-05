package com.lumin.app;

import android.content.SharedPreferences;

/** Small shared state used by the accessibility shortcut to show/hide SOFIA UI. */
public final class SofiaShortcutController {
    private SofiaShortcutController() {}

    public static boolean toggle(SharedPreferences control) {
        boolean next = !control.getBoolean("shortcut_overlay_enabled", true);
        control.edit().putBoolean("shortcut_overlay_enabled", next).apply();
        return next;
    }

    public static void setEnabled(SharedPreferences control, boolean enabled) {
        control.edit().putBoolean("shortcut_overlay_enabled", enabled).apply();
    }

    public static boolean isEnabled(SharedPreferences control) {
        return control.getBoolean("shortcut_overlay_enabled", true);
    }
}
