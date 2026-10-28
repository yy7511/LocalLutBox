package io.github.yy7511.locallutbox;

import android.app.Application;

import io.github.yy7511.locallutbox.core.AppRef;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppRef.init(this);
    }
}
