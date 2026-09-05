package com.lumin.app;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.VideoProfile;

public class RebornInCallService extends InCallService {
    private static volatile RebornInCallService instance;
    private static volatile Call activeCall;

    public static RebornInCallService get() { return instance; }
    public static Call activeCall() { return activeCall; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        RebornCentral.init(this);
    }

    @Override public void onDestroy() {
        RebornCallAudioController.stop();
        if (instance == this) instance = null;
        activeCall = null;
        super.onDestroy();
    }

    @Override public void onCallAdded(Call call) {
        super.onCallAdded(call);
        activeCall = call;
        RebornCentral.startSession(this);
        handleState(call.getState());

        call.registerCallback(new Call.Callback() {
            @Override public void onStateChanged(Call c, int state) {
                activeCall = c;
                handleState(state);
                RebornCallActivity.refreshFromService();
                if (state == Call.STATE_DISCONNECTED) activeCall = null;
            }

            @Override public void onDetailsChanged(Call c, Call.Details details) {
                activeCall = c;
                RebornCallActivity.refreshFromService();
            }
        });

        Intent i = new Intent(this, RebornCallActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    private void handleState(int state) {
        RebornCentral.onCallState(this, state);
        if (state == Call.STATE_ACTIVE) {
            RebornCallAudioController.start(this);
        } else if (state == Call.STATE_DISCONNECTED) {
            RebornCallAudioController.stop();
        }
    }

    @Override public void onCallRemoved(Call call) {
        if (activeCall == call) activeCall = null;
        RebornCallAudioController.stop();
        RebornCentral.onCallState(this, Call.STATE_DISCONNECTED);
        RebornCallActivity.refreshFromService();
        super.onCallRemoved(call);
    }

    public void answer() {
        Call c = activeCall;
        if (c != null) c.answer(VideoProfile.STATE_AUDIO_ONLY);
    }

    public void reject() {
        Call c = activeCall;
        if (c != null) c.reject(false, null);
    }

    public void hangup() {
        Call c = activeCall;
        if (c != null) c.disconnect();
    }

    public void toggleHold() {
        Call c = activeCall;
        if (c == null) return;
        if (c.getState() == Call.STATE_HOLDING) c.unhold(); else c.hold();
    }

    public void setMutedCompat(boolean muted) { setMuted(muted); }

    public void setSpeaker(boolean enabled) {
        setAudioRoute(enabled ? android.telecom.CallAudioState.ROUTE_SPEAKER : android.telecom.CallAudioState.ROUTE_EARPIECE);
    }
}
