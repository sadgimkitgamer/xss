package com.ember.music;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.webkit.WebSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class EmberApp extends Application {
    private static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashRecorder();
        // Load Android's WebView engine before any screen opens. Loading it for the first time
        // changes the app's resources, which otherwise makes Android rebuild the screen while
        // the player page is still loading – and that could leave the page frozen.
        try {
            WebSettings.getDefaultUserAgent(this);
        } catch (Exception ignored) {
        }
    }

    /** If Ember crashes, keep the details so the next launch can show them. */
    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                String report = "Ember " + BuildConfigVersion.name(this) + " on Android " + Build.VERSION.RELEASE
                        + " (" + Build.MANUFACTURER + " " + Build.MODEL + ")\n"
                        + "thread: " + thread.getName() + "\n" + sw;
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), CRASH_FILE))) {
                    out.write(report.getBytes(StandardCharsets.UTF_8));
                }
                Log.e(PlayerBridge.TAG, "CRASH " + report);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Returns (and forgets) the last crash report, or null if there wasn't one. */
    static String takeLastCrash(Context ctx) {
        File f = new File(ctx.getFilesDir(), CRASH_FILE);
        if (!f.exists()) return null;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            f.delete();
            return s;
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }

    /** App version without needing BuildConfig. */
    static final class BuildConfigVersion {
        static String name(Context ctx) {
            try {
                return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }
    }
}
