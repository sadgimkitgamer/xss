package com.ember.music;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String HOME = "https://music.youtube.com/";
    static final int ACCENT = 0xFFFF0033;

    /** Hosts / paths that only serve ads or ad tracking. */
    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagservices.com", "imasdk.googleapis.com",
            "adservice.google.com"
    };
    private static final String[] AD_PATHS = {
            "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "/youtubei/v1/log_event",
            "/api/stats/qoe?adformat", "/pcs/activeview"
    };

    /**
     * The player page lives here, outside any one Activity instance, so Android
     * recreating the screen (rotation, theme/font change, memory) never stops the music.
     */
    private static PlayerWebView shared;
    private static boolean docStartScript;
    private static String injectJs;

    private PlayerWebView web;
    private ProgressBar progress;
    private View splash;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (injectJs == null) injectJs = buildInjectJs();
        PlayerBridge.closing = false;

        boolean reused = shared != null;
        Log.i(PlayerBridge.TAG, "activity created (reusing player: " + reused + ")");
        if (reused) {
            web = shared;
            web.context.setBaseContext(this);
            if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
        } else {
            web = shared = new PlayerWebView(new MutableContextWrapper(this));
            configurePlayer(web);
        }
        setContentView(buildLayout(reused));
        attachClients();
        PlayerBridge.attach(this, web);

        String deepLink = linkFrom(getIntent());
        if (reused && deepLink == null) {
            // already playing / loaded – nothing to do
        } else if (deepLink != null) {
            web.loadUrl(deepLink);
            if (getIntent().getBooleanExtra("autoplay", false)) {
                // Used by the automated test: press play a few times while the page loads
                for (int i = 1; i <= 6; i++) web.postDelayed(() -> PlayerBridge.control("play"), i * 5000L);
            }
        } else if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(HOME);
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        startForegroundService(new Intent(this, MusicService.class));
    }

    /** Opens youtube / music.youtube links shared to or opened with Ember. */
    private static String linkFrom(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return null;
        Uri u = i.getData();
        if (!isYouTube(u.toString())) return null;
        return u.buildUpon().authority("music.youtube.com").build().toString();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String link = linkFrom(intent);
        if (link != null) web.loadUrl(link);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(PlayerBridge.TAG, "activity stopped (app in background)");
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(PlayerBridge.TAG, "activity started (app in foreground)");
    }

    /** WebView that never tells the page it went to the background. */
    static final class PlayerWebView extends WebView {
        final MutableContextWrapper context;

        PlayerWebView(MutableContextWrapper ctx) {
            super(ctx);
            context = ctx;
        }

        // WebView pauses media as soon as its window is hidden (app in background,
        // screen off). Always report "visible" so the music keeps going.
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            Log.i(PlayerBridge.TAG, "window visibility " + visibility + " -> forcing VISIBLE");
            super.onWindowVisibilityChanged(View.VISIBLE);
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, View.VISIBLE);
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(true);
        }
    }

    /** Fully tears down the player (used by the notification's Close button). */
    static void destroyPlayer() {
        if (shared == null) return;
        Log.i(PlayerBridge.TAG, "destroying player");
        if (shared.getParent() != null) ((ViewGroup) shared.getParent()).removeView(shared);
        shared.stopLoading();
        shared.destroy();
        shared = null;
    }

    private View buildLayout(boolean reused) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        ClipDrawable bar = new ClipDrawable(new ColorDrawable(ACCENT), Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                new ColorDrawable(Color.TRANSPARENT), bar});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        progress.setProgressDrawable(layers);
        progress.setMax(100);
        root.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));

        // Splash shown until the first page load finishes
        LinearLayout sp = new LinearLayout(this);
        sp.setOrientation(LinearLayout.VERTICAL);
        sp.setGravity(Gravity.CENTER);
        sp.setBackgroundColor(Color.BLACK);
        sp.setClickable(true);
        TextView logo = new TextView(this);
        logo.setText("EMBER");
        logo.setTextColor(ACCENT);
        logo.setTextSize(44);
        logo.setLetterSpacing(0.3f);
        logo.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        TextView sub = new TextView(this);
        sub.setText("music · no ads · always on");
        sub.setTextColor(0xFF8A8A8A);
        sub.setTextSize(13);
        sub.setPadding(0, dp(8), 0, 0);
        sp.addView(logo);
        sp.addView(sub);
        splash = sp;
        if (reused) {
            splash = null;
        } else {
            root.addView(sp, new FrameLayout.LayoutParams(-1, -1));
            root.postDelayed(this::hideSplash, 6000);
        }
        return root;
    }

    /** One-time setup of the shared player. */
    private void configurePlayer(WebView web) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        // Look like regular Chrome so YouTube Music and Google sign-in treat us as a browser
        s.setUserAgentString(s.getUserAgentString()
                .replace("; wv", "")
                .replaceAll(" Build/[^;)]*", "")
                .replace("Version/4.0 ", ""));

        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.emptySet());
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        // Keep the page's renderer process at high priority even when not on screen
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        PackageInfo wv = WebView.getCurrentWebViewPackage();
        Log.i(PlayerBridge.TAG, "WebView " + (wv == null ? "?" : wv.packageName + " " + wv.versionName));

        web.addJavascriptInterface(new PlayerBridge.JsInterface(), "EmberNative");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> origins = new HashSet<>();
            origins.add("https://music.youtube.com");
            origins.add("https://*.youtube.com");
            WebViewCompat.addDocumentStartJavaScript(web, injectJs, origins);
            docStartScript = true;
        }
    }

    /** Hooks the player up to this screen's progress bar / splash. */
    private void attachClients() {
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                // Swallow "open in YouTube Music app" intents so we stay in Ember
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (isAd(req.getUrl())) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    Log.w(PlayerBridge.TAG, "page error " + err.getErrorCode() + " " + err.getDescription() + " " + req.getUrl());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
                if (req.isForMainFrame()) {
                    Log.w(PlayerBridge.TAG, "page http " + resp.getStatusCode() + " " + req.getUrl());
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.i(PlayerBridge.TAG, "page started " + url);
                if (!docStartScript && isYouTube(url)) view.evaluateJavascript(injectJs, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(PlayerBridge.TAG, "page finished " + url);
                if (isYouTube(url)) view.evaluateJavascript(injectJs, null);
                hideSplash();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(PlayerBridge.TAG, "console error: " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                }
                return true;
            }
        });
    }

    private static boolean isYouTube(String url) {
        if (url == null) return false;
        String host = Uri.parse(url).getHost();
        return host != null && (host.equals("youtube.com") || host.endsWith(".youtube.com"));
    }

    private static boolean isAd(Uri u) {
        String host = u.getHost();
        if (host == null) return false;
        for (String h : AD_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        if (host.endsWith("youtube.com") || host.endsWith("google.com")) {
            String path = u.getPath() == null ? "" : u.getPath();
            String full = path + (u.getQuery() == null ? "" : "?" + u.getQuery());
            for (String p : AD_PATHS) {
                if (full.startsWith(p) || full.contains(p)) return true;
            }
        }
        return false;
    }

    private String buildInjectJs() {
        String css = readAsset("theme.css");
        String js = readAsset("inject.js");
        return "window.__emberCss=" + JSONObject.quote(css) + ";\n" + js;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            byte[] buf = new byte[in.available()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void hideSplash() {
        if (splash == null || splash.getVisibility() == View.GONE) return;
        splash.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            splash.setVisibility(View.GONE);
            ((ViewGroup) splash.getParent()).removeView(splash);
            splash = null;
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            // Keep the player alive: just send the app to the background
            moveTaskToBack(true);
        }
    }

    // NOTE: we intentionally do NOT call web.onPause()/pauseTimers() in onPause,
    // so the music keeps playing when you leave the app or turn off the screen.

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        Log.i(PlayerBridge.TAG, "activity destroyed (finishing=" + isFinishing()
                + ", config change=" + isChangingConfigurations()
                + ", closing=" + PlayerBridge.closing + ", playing=" + PlayerBridge.last.playing + ")");
        PlayerBridge.detach();
        boolean stopEverything = PlayerBridge.closing
                || (isFinishing() && !isChangingConfigurations() && !PlayerBridge.last.playing);
        if (stopEverything) {
            stopService(new Intent(this, MusicService.class));
            destroyPlayer();
        } else if (web != null) {
            // Keep the music going: park the player until the screen comes back
            if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
            web.context.setBaseContext(getApplicationContext());
        }
        super.onDestroy();
    }
}
