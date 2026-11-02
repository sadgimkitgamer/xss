package com.ember.music;

import android.app.Application;
import android.webkit.WebSettings;

/**
 * Loads Android's WebView engine before any screen opens. Loading it for the first time
 * changes the app's resources, which otherwise makes Android rebuild the screen while the
 * player page is still loading – and that could leave the page frozen.
 */
public class EmberApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            WebSettings.getDefaultUserAgent(this);
        } catch (Exception ignored) {
        }
    }
}
