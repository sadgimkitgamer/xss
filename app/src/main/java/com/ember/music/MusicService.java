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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
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
import android.util.Log;

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
    static final String ACTION_REPEAT = "com.ember.music.REPEAT";
    static final String ACTION_REFRESH = "com.ember.music.REFRESH";

    /** Which two extra buttons the lock-screen / notification player shows. */
    static final String PREFS = "ember";
    static final String PREF_EXTRAS = "extraButtons";
    static final String DEFAULT_EXTRAS = "like,repeat,save";

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

    // ---- Audio focus: pause when another app starts playing ----
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean pausedByFocus;

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
                else if (ACTION_REPEAT.equals(action)) PlayerBridge.control("repeat");
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

        audio = getSystemService(AudioManager.class);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setWillPauseWhenDucked(false)   // let Android just lower us for short sounds
                .setOnAudioFocusChangeListener(this::onFocusChange, main)
                .build();

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
        else if (ACTION_REPEAT.equals(a)) PlayerBridge.control("repeat");
        else if (ACTION_REFRESH.equals(a)) { shownKey = null; onState(state); }
        return START_NOT_STICKY;
    }

    @Override
    public void onState(PlayerBridge.State s) {
        state = s;

        String[] extras = extras();
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        // With three extras, Previous gives up its spot so all three fit
        if (extras.length < 3) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        // The chosen extras (Like / Save / Loop) show up as extra buttons in the
        // lock-screen / quick-settings player.
        PlaybackState.Builder pb = new PlaybackState.Builder().setActions(actions);
        for (String extra : extras) {
            Extra e = extra(extra);
            pb.addCustomAction(new PlaybackState.CustomAction.Builder(e.action, e.label, e.icon).build());
        }
        session.setPlaybackState(pb
                .setState(s.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        s.pos, s.playing ? 1f : 0f)
                .build());

        if (!Objects.equals(s.art, artUrl)) {
            artUrl = s.art;
            artBitmap = null;
            loadArt(s.art);
        }

        String key = s.title + "|" + s.artist + "|" + s.dur + "|" + s.playing + "|" + (artBitmap != null)
                + "|" + s.liked + "|" + s.saved + "|" + s.repeat;
        if (!key.equals(shownKey)) {
            shownKey = key;
            updateMetadata();
            nm.notify(NOTIF_ID, buildNotification());
        }

        // Claim audio focus whenever we start playing (unless a voice chat is going on),
        // so other apps pause for us and we pause for them.
        if (s.playing && !hasFocus && audio.getMode() == AudioManager.MODE_NORMAL) {
            int r = audio.requestAudioFocus(focusRequest);
            hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            Log.i(PlayerBridge.TAG, "audio focus request -> " + (hasFocus ? "granted" : "denied"));
            if (!hasFocus) PlayerBridge.control("pause");
        }

        if (s.playing) {
            if (!wakeLock.isHeld()) wakeLock.acquire();
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } else {
            releaseLocks();
        }
    }

    private void onFocusChange(int change) {
        Log.i(PlayerBridge.TAG, "audio focus change " + change + " (mode " + audio.getMode() + ")");
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (pausedByFocus) {
                    pausedByFocus = false;
                    Log.i(PlayerBridge.TAG, "interruption over -> resuming");
                    PlayerBridge.control("play");
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                hasFocus = false;
                boolean transientLoss = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                // Give the other app a moment to switch into call mode, then decide
                main.postDelayed(() -> {
                    if (audio.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                        Log.i(PlayerBridge.TAG, "voice chat started -> keep playing");
                        return;
                    }
                    if (!state.playing) return;
                    pausedByFocus = transientLoss;
                    Log.i(PlayerBridge.TAG, "other audio started -> pausing"
                            + (transientLoss ? " (will resume)" : ""));
                    PlayerBridge.control("pause");
                }, 600);
                break;
            default:
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Android lowers our volume automatically
                break;
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
                .setDeleteIntent(serviceIntent(ACTION_DISMISS));
        // Older Android shows these buttons; Android 13+ builds its player from the
        // media session (incl. the custom actions above).
        // [extra1] [prev] [play] [next] [extra2]   or with three extras:
        // [extra1] [play] [next] [extra2] [extra3]
        String[] extras = extras();
        b.addAction(extraAction(extras[0]));
        if (extras.length < 3) b.addAction(action(R.drawable.ic_prev, "Previous", ACTION_PREV));
        b.addAction(state.playing
                ? action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE)
                : action(R.drawable.ic_play, "Play", ACTION_TOGGLE));
        b.addAction(action(R.drawable.ic_next, "Next", ACTION_NEXT));
        for (int i = 1; i < extras.length; i++) b.addAction(extraAction(extras[i]));
        b.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(extras.length < 3 ? new int[]{1, 2, 3} : new int[]{0, 1, 2}));
        if (artBitmap != null) b.setLargeIcon(artBitmap);
        return b.build();
    }

    private static final class Extra {
        final String action, label;
        final int icon;
        Extra(String action, String label, int icon) { this.action = action; this.label = label; this.icon = icon; }
    }

    private Extra extra(String name) {
        switch (name) {
            case "save":
                return state.saved ? new Extra(ACTION_SAVE, "Saved", R.drawable.ic_saved)
                        : new Extra(ACTION_SAVE, "Save to playlist", R.drawable.ic_save);
            case "repeat":
                return state.repeat ? new Extra(ACTION_REPEAT, "Looping song (tap to stop)", R.drawable.ic_repeat_one)
                        : new Extra(ACTION_REPEAT, "Loop song", R.drawable.ic_repeat);
            default:
                return state.liked ? new Extra(ACTION_LIKE, "Unlike", R.drawable.ic_thumb_up_filled)
                        : new Extra(ACTION_LIKE, "Like", R.drawable.ic_thumb_up);
        }
    }

    /** The user's chosen extra buttons (2 or 3), e.g. ["like", "repeat", "save"]. */
    private String[] extras() {
        String v = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_EXTRAS, DEFAULT_EXTRAS);
        String[] parts = v.split(",");
        if (parts.length >= 2 && parts.length <= 3) return parts;
        return DEFAULT_EXTRAS.split(",");
    }

    private Notification.Action extraAction(String name) {
        Extra e = extra(name);
        return action(e.icon, e.label, e.action);
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
        if (audio != null && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
