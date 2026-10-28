package io.github.yy7511.locallutbox.core;

import android.content.Context;

/** Holds the application context for utilities that are called without one. */
public final class AppRef {

    private static Context app;

    private AppRef() {
    }

    public static void init(Context c) {
        if (app == null && c != null) {
            app = c.getApplicationContext();
        }
    }

    public static Context get() {
        return app;
    }
}
