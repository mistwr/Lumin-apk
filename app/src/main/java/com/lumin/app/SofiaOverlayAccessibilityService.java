package com.lumin.app;

public class SofiaOverlayAccessibilityService extends SofiaAccessibilityService {
    private SofiaCallOverlay overlay;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        overlay = new SofiaCallOverlay(this);
        overlay.start();
    }

    @Override public void onDestroy() {
        if (overlay != null) overlay.stop();
        super.onDestroy();
    }
}
