package com.lumin.app;

import android.content.Intent;
import android.service.voice.VoiceInteractionService;

/**
 * Lets REBORN AI be selected as Android's default digital assistant.
 * This does not grant cellular call audio access by itself; it only gives
 * REBORN the system assistant role and an entry point the Galaxy can invoke.
 */
public class RebornVoiceInteractionService extends VoiceInteractionService {
    @Override public void onReady() {
        super.onReady();
    }

    @Override public void onLaunchVoiceAssistFromKeyguard() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }
}
