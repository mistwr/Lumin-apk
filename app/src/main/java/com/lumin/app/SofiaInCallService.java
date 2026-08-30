package com.lumin.app;

import android.content.Intent;
import android.os.Build;
import android.telecom.Call;
import android.telecom.InCallService;

public class SofiaInCallService extends InCallService {
    public static volatile Call activeCall;

    @Override public void onCallAdded(Call call) {
        super.onCallAdded(call);
        activeCall = call;
        call.registerCallback(new Call.Callback() {
            @Override public void onStateChanged(Call c, int state) {
                super.onStateChanged(c, state);
                if (state == Call.STATE_DISCONNECTED) {
                    activeCall = null;
                    sendBroadcast(new Intent("com.lumin.app.CALL_ENDED").setPackage(getPackageName()));
                }
            }
        });
        Intent i = new Intent(this, SofiaNativeCallActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("call_state", call.getState());
        if (Build.VERSION.SDK_INT >= 23 && call.getDetails() != null && call.getDetails().getHandle() != null) {
            i.putExtra("number", call.getDetails().getHandle().getSchemeSpecificPart());
        }
        startActivity(i);
    }

    @Override public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        if (activeCall == call) activeCall = null;
        sendBroadcast(new Intent("com.lumin.app.CALL_ENDED").setPackage(getPackageName()));
    }
}
