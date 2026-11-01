# Ember 🔥 — YouTube Music for Android, in red & black

Ember is a lightweight Android app that signs into **your own YouTube Music account** and:

- **Blocks ads** – strips ad data from the player, blocks ad/tracking requests, and auto-skips anything that slips through.
- **Keeps playing when you leave the app** or lock your screen (foreground media service + wake lock).
- **👍 Like** and **Quick save** buttons on the lock-screen / notification player, so you can keep songs you hear on autoplay without opening the app. Likes go to *Liked music*; quick saves go to **your own playlist** — tap the red playlist button in the app to choose which one (until you pick, they go to an automatically created private **Ember Saves** playlist).
- Gives you a **media notification / lock-screen player** with previous · play/pause · next, and works with Bluetooth & headset buttons. Pauses automatically when headphones disconnect.
- Re-skins YouTube Music in an **red & black** theme.

## Install on your phone

1. Open the repo's **Releases** page on your phone → **Ember (latest build)** → tap **Ember.apk**.
2. Open the download. If Android asks, allow *Install unknown apps* for your browser.
3. Launch **Ember**, tap **Sign in** (top right) and log in with your Google account.
4. Allow notifications when asked — that's the player you control from the lock screen.

Every push to the repo rebuilds the APK automatically (GitHub Actions → *Build APK*). New builds install over the old one and keep your login.

> Tip: if your phone is aggressive about killing background apps (Samsung, Xiaomi, etc.),
> go to *Settings → Apps → Ember → Battery* and set it to **Unrestricted**.

## Build it yourself

```
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (API 35).

## How it works

| File | What it does |
|---|---|
| `MainActivity.java` | Hosts music.youtube.com in a WebView, blocks ad hosts, injects the scripts below |
| `assets/inject.js` | Background-playback fix, ad pruning/skipping, playback bridge to Android |
| `assets/theme.css` | Red & black theme |
| `MusicService.java` | Foreground service, media session and notification controls |

Ember is an unofficial personal-use client, not affiliated with Google or YouTube.
