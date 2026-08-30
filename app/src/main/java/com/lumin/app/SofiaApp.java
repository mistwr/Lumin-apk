package com.lumin.app;

import android.app.Application;
import android.content.Context;

public class SofiaApp extends Application {
    private static Context appContext;

    @Override public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    public static Context context() {
        if (appContext == null) throw new IllegalStateException("SOFIA app context not ready");
        return appContext;
    }
}
