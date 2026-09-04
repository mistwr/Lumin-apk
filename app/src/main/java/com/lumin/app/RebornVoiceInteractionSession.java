package com.lumin.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/** Minimal visible assistant session: invoke REBORN's Calling Intelligence UI. */
public class RebornVoiceInteractionSession extends VoiceInteractionSession {
    public RebornVoiceInteractionSession(Context context) {
        super(context);
    }

    @Override public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Intent i = new Intent(getContext(), MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        getContext().startActivity(i);
        hide();
    }
}
