package com.ember.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that keeps the app (and its WebView player) alive in the background,
 * and exposes playback through a media notification, lock screen and Bluetooth/headset buttons.
 */
public class MusicService extends Service implements PlayerBridge.Listener {

    static final String ACTION_TOGGLE = "com.ember.music.TOGGLE";
    static final String ACTION_NEXT = "com.ember.music.NEXT";
    static final String ACTION_PREV = "com.ember.music.PREV";
    static final String ACTION_STOP = "com.ember.music.STOP";
    static final String ACTION_DISMISS = "com.ember.music.DISMISS";
    static final String ACTION_LIKE = "com.ember.music.LIKE";
    static final String ACTION_SAVE = "com.ember.music.SAVE";

    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 7;

    private MediaSession session;
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private PlayerBridge.State state = new PlayerBridge.State();
    private String shownKey = null;
    private String artUrl = null;
    private Bitmap artBitmap = null;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // Headphones unplugged / Bluetooth disconnected -> pause
            PlayerBridge.control("pause");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "Ember");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { PlayerBridge.control("play"); }
            @Override public void onPause() { PlayerBridge.control("pause"); }
            @Override public void onSkipToNext() { PlayerBridge.control("next"); }
            @Override public void onSkipToPrevious() { PlayerBridge.control("prev"); }
            @Override public void onSeekTo(long pos) { PlayerBridge.control("seek", pos); }
            @Override public void onStop() { shutdown(); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                if (ACTION_LIKE.equals(action)) PlayerBridge.control("like");
                else if (ACTION_SAVE.equals(action)) PlayerBridge.control("save");
            }
        });
        session.setSessionActivity(openAppIntent());
        session.setActive(true);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ember::playback");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Ember::wifi");
            wifiLock.setReferenceCounted(false);
        }

        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        PlayerBridge.setListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(a)) PlayerBridge.control(state.playing ? "pause" : "play");
        else if (ACTION_NEXT.equals(a)) PlayerBridge.control("next");
        else if (ACTION_PREV.equals(a)) PlayerBridge.control("prev");
        else if (ACTION_STOP.equals(a)) shutdown();
        else if (ACTION_DISMISS.equals(a)) {
            // Notification swiped away: stop if paused, otherwise bring it back
            if (state.playing) nm.notify(NOTIF_ID, buildNotification()); else shutdown();
        }
        else if (ACTION_LIKE.equals(a)) PlayerBridge.control("like");
        else if (ACTION_SAVE.equals(a)) PlayerBridge.control("save");
        return START_NOT_STICKY;
    }

    @Override
    public void onState(PlayerBridge.State s) {
        state = s;

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        // Like + Save show up as extra buttons in the lock-screen / quick-settings player
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_LIKE,
                        s.liked ? "Unlike" : "Like",
                        s.liked ? R.drawable.ic_thumb_up_filled : R.drawable.ic_thumb_up).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_SAVE,
                        s.saved ? "Saved" : "Quick save",
                        s.saved ? R.drawable.ic_saved : R.drawable.ic_save).build())
                .setState(s.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        s.pos, s.playing ? 1f : 0f)
                .build());

        if (!Objects.equals(s.art, artUrl)) {
            artUrl = s.art;
            artBitmap = null;
            loadArt(s.art);
        }

        String key = s.title + "|" + s.artist + "|" + s.dur + "|" + s.playing + "|" + (artBitmap != null)
                + "|" + s.liked + "|" + s.saved;
        if (!key.equals(shownKey)) {
            shownKey = key;
            updateMetadata();
            nm.notify(NOTIF_ID, buildNotification());
        }

        if (s.playing) {
            if (!wakeLock.isHeld()) wakeLock.acquire();
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } else {
            releaseLocks();
        }
    }

    private void updateMetadata() {
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.dur);
        if (artBitmap != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBitmap);
        session.setMetadata(b.build());
    }

    private String title() {
        return state.title == null || state.title.isEmpty() ? "Ember" : state.title;
    }

    private Notification buildNotification() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_note)
                .setContentTitle(title())
                .setContentText(state.artist == null || state.artist.isEmpty() ? "Ready to play" : state.artist)
                .setContentIntent(openAppIntent())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setShowWhen(false)
                .setColor(MainActivity.ACCENT)
                .setColorized(true)
                .setDeleteIntent(serviceIntent(ACTION_DISMISS))
                // Older Android shows these buttons; Android 13+ builds its player from the
                // media session (incl. the Like / Save custom actions above).
                .addAction(state.liked
                        ? action(R.drawable.ic_thumb_up_filled, "Unlike", ACTION_LIKE)
                        : action(R.drawable.ic_thumb_up, "Like", ACTION_LIKE))
                .addAction(action(R.drawable.ic_prev, "Previous", ACTION_PREV))
                .addAction(state.playing
                        ? action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE)
                        : action(R.drawable.ic_play, "Play", ACTION_TOGGLE))
                .addAction(action(R.drawable.ic_next, "Next", ACTION_NEXT))
                .addAction(state.saved
                        ? action(R.drawable.ic_saved, "Saved", ACTION_SAVE)
                        : action(R.drawable.ic_save, "Quick save", ACTION_SAVE))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3));
        if (artBitmap != null) b.setLargeIcon(artBitmap);
        return b.build();
    }

    private Notification.Action action(int icon, String label, String act) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label,
                serviceIntent(act)).build();
    }

    private PendingIntent serviceIntent(String act) {
        Intent i = new Intent(this, MusicService.class).setAction(act);
        return PendingIntent.getService(this, act.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onAction(String kind, boolean ok, String message) {
        if (message != null && !message.isEmpty()) {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void loadArt(String url) {
        if (url == null || url.isEmpty()) return;
        io.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            Bitmap result = bmp;
            main.post(() -> {
                if (result != null && url.equals(artUrl)) {
                    artBitmap = result;
                    onState(state);
                }
            });
        });
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    private void shutdown() {
        PlayerBridge.control("pause");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        PlayerBridge.closeApp();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped away from recents: keep going if music is playing, otherwise clean up
        android.util.Log.i(PlayerBridge.TAG, "task removed (playing=" + state.playing + ")");
        if (!state.playing) shutdown();
    }

    @Override
    public void onDestroy() {
        PlayerBridge.setListener(null);
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        releaseLocks();
        session.setActive(false);
        session.release();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
