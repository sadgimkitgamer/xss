package com.ember.music;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/** Glue between the YouTube Music page (WebView) and the native media service. */
final class PlayerBridge {

    static final String TAG = "Ember";

    /** Snapshot of what the page is playing. */
    static final class State {
        String title = "";
        String artist = "";
        String art = "";
        boolean playing;
        long pos;
        long dur;
        String videoId = "";
        boolean liked;
        boolean saved;
        int repeat;   // 0 off, 1 playlist, 2 song
    }

    interface Listener {
        void onState(State s);

        /** Result of a like / save request, to show to the user. */
        void onAction(String kind, boolean ok, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<WebView> webRef = new WeakReference<>(null);
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static Listener listener;
    static State last = new State();
    /** Set when the user pressed Close, so everything shuts down. */
    static volatile boolean closing;

    private PlayerBridge() {}

    private static android.media.AudioManager audio;

    static void attach(Activity activity, WebView web) {
        audio = activity.getApplicationContext().getSystemService(android.media.AudioManager.class);
        activityRef = new WeakReference<>(activity);
        webRef = new WeakReference<>(web);
    }

    static void detach() {
        activityRef = new WeakReference<>(null);
        webRef = new WeakReference<>(null);
    }

    static void setListener(Listener l) {
        listener = l;
        if (l != null) l.onState(last);
    }

    /** Send a playback command to the page: play, pause, toggle, next, prev, seek. */
    static void control(String cmd, long arg) {
        MAIN.post(() -> {
            WebView w = webRef.get();
            if (w == null) return;
            w.evaluateJavascript(
                    "window.__emberControl && window.__emberControl('" + cmd + "'," + arg + ")", null);
        });
    }

    /** Calls window.__emberControl(cmd, "text") with a string argument. */
    /** Lets the page know whether the app is on screen (it only needs some workarounds off-screen). */
    static void setBackground(boolean bg) {
        background = bg;
        MAIN.post(() -> {
            WebView w = webRef.get();
            if (w != null) w.evaluateJavascript("window.__emberBg=" + bg, null);
        });
    }

    static volatile boolean background;

    static void controlText(String cmd, String text) {
        MAIN.post(() -> {
            WebView w = webRef.get();
            if (w == null) return;
            w.evaluateJavascript("window.__emberControl && window.__emberControl('" + cmd + "',"
                    + JSONObject.quote(text == null ? "" : text) + ")", null);
        });
    }

    /** Receives the user's playlists (JSON) for the quick-save picker. */
    static java.util.function.Consumer<String> playlistCallback;

    static void control(String cmd) {
        control(cmd, 0);
    }

    static void closeApp() {
        closing = true;
        MAIN.post(() -> {
            Activity a = activityRef.get();
            if (a != null) a.finishAndRemoveTask();
            else { MainActivity.destroyPlayer(); closing = false; }
        });
    }

    /** Exposed to JavaScript as window.EmberNative. */
    static final class JsInterface {
        @JavascriptInterface
        public void log(String msg) {
            Log.i(TAG, "page: " + msg);
        }

        /** True while some app (not us – we're paused when this is asked) is playing audio. */
        @JavascriptInterface
        public boolean otherAudioPlaying() {
            return audio != null && audio.isMusicActive();
        }

        @JavascriptInterface
        public boolean inBackground() {
            return background;
        }

        /** 0 normal, 1 ringing, 2 phone call, 3 voice chat (Discord etc.). */
        @JavascriptInterface
        public int audioMode() {
            return audio == null ? 0 : audio.getMode();
        }

        @JavascriptInterface
        public void onPlaylists(String json) {
            MAIN.post(() -> {
                if (playlistCallback != null) playlistCallback.accept(json);
            });
        }

        @JavascriptInterface
        public void onAction(String kind, boolean ok, String message) {
            Log.i(TAG, "action " + kind + " ok=" + ok + " " + message);
            MAIN.post(() -> {
                if (listener != null) listener.onAction(kind, ok, message);
            });
        }

        @JavascriptInterface
        public void onState(String json) {
            Log.i(TAG, "state " + json);
            try {
                JSONObject o = new JSONObject(json);
                State s = new State();
                s.title = o.optString("title");
                s.artist = o.optString("artist");
                s.art = o.optString("art");
                s.playing = o.optBoolean("playing");
                s.pos = o.optLong("pos");
                s.dur = o.optLong("dur");
                s.videoId = o.optString("videoId");
                s.liked = o.optBoolean("liked");
                s.saved = o.optBoolean("saved");
                s.repeat = o.optInt("repeat");
                MAIN.post(() -> {
                    last = s;
                    if (listener != null) listener.onState(s);
                });
            } catch (Exception ignored) {
            }
        }
    }
}
