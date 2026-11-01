/*
 * Ember – injected into music.youtube.com at document start.
 *  1. Keeps playback alive when the app is in the background.
 *  2. Strips ads from player responses and auto-skips any that slip through.
 *  3. Applies the red/black theme.
 *  4. Bridges playback state + controls to the native media notification.
 */
(function () {
  'use strict';
  if (window.__ember) return;
  window.__ember = true;

  const log = (m) => { try { window.EmberNative && window.EmberNative.log(String(m)); } catch (e) {} };
  log('script running at ' + location.href + ' (bridge ' + (window.EmberNative ? 'ok' : 'missing') + ')');

  /* ---------- 1. Background playback ---------- */
  // When the app is in the background Android stops drawing the page, so
  // requestAnimationFrame never fires and the player can stall. Fall back to a timer.
  try {
    const nRAF = window.requestAnimationFrame.bind(window);
    const nCAF = window.cancelAnimationFrame.bind(window);
    const pending = new Map();
    let nextId = 1;
    window.requestAnimationFrame = function (cb) {
      const id = nextId++;
      const entry = { raf: 0, timer: 0 };
      const run = (t) => {
        if (!pending.has(id)) return;
        pending.delete(id);
        nCAF(entry.raf);
        clearTimeout(entry.timer);
        cb(typeof t === 'number' ? t : performance.now());
      };
      entry.raf = nRAF(run);
      entry.timer = setTimeout(() => run(performance.now()), 100);
      pending.set(id, entry);
      return id;
    };
    window.cancelAnimationFrame = function (id) {
      const e = pending.get(id);
      if (!e) return;
      pending.delete(id);
      nCAF(e.raf);
      clearTimeout(e.timer);
    };
  } catch (e) {}

  try {
    const def = (obj, prop, val) =>
      Object.defineProperty(obj, prop, { get: () => val, configurable: true });
    def(Document.prototype, 'hidden', false);
    def(Document.prototype, 'webkitHidden', false);
    def(Document.prototype, 'visibilityState', 'visible');
    def(Document.prototype, 'webkitVisibilityState', 'visible');
    Document.prototype.hasFocus = () => true;
  } catch (e) {}
  const swallow = (e) => {
    if (e.target === window || e.target === document) e.stopImmediatePropagation();
  };
  ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze', 'blur'].forEach((t) => {
    window.addEventListener(t, swallow, true);
    document.addEventListener(t, swallow, true);
  });

  /* ---------- 2a. Strip ads from player data ---------- */
  const AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];
  function prune(o) {
    if (!o || typeof o !== 'object') return o;
    try {
      if (Array.isArray(o)) { o.forEach(prune); return o; }
      for (const k of AD_KEYS) if (k in o) delete o[k];
      if (o.playerResponse) prune(o.playerResponse);
      if (o.response) prune(o.response);
    } catch (e) {}
    return o;
  }
  const origParse = JSON.parse;
  JSON.parse = function () { return prune(origParse.apply(this, arguments)); };
  const origJson = Response.prototype.json;
  Response.prototype.json = function () { return origJson.apply(this, arguments).then(prune); };
  let initialPR;
  try {
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: () => initialPR,
      set: (v) => { initialPR = prune(v); },
    });
  } catch (e) {}

  /* ---------- 2b. Skip anything that slips through ---------- */
  const q = (s) => document.querySelector(s);
  const click = (s) => { const el = q(s); if (el) { el.click(); return true; } return false; };
  let mutedForAd = false;

  function skipAds() {
    const player = q('#movie_player, .html5-video-player');
    const video = q('video');
    const adOn = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
    if (adOn && video) {
      if (!mutedForAd) { mutedForAd = true; video.muted = true; }
      video.playbackRate = 16;
      if (isFinite(video.duration) && video.duration > 0) video.currentTime = video.duration;
      click('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .videoAdUiSkipButton');
    } else if (mutedForAd && video) {
      mutedForAd = false;
      video.muted = false;
      video.playbackRate = 1;
    }
    // Promo banners, upsells and "are you still there?" prompts
    document.querySelectorAll(
      'ytmusic-mealbar-promo-renderer, ytmusic-statement-banner-renderer, ytmusic-upsell-dialog-renderer, ' +
      'ytd-ad-slot-renderer, #player-ads, .ytp-ad-overlay-container'
    ).forEach((el) => el.remove());
    click('ytmusic-you-there-renderer button, ytmusic-you-there-renderer tp-yt-paper-button');
  }

  /* ---------- 3. Theme ---------- */
  function addTheme() {
    if (document.getElementById('ember-theme') || !window.__emberCss) return;
    const st = document.createElement('style');
    st.id = 'ember-theme';
    st.textContent = window.__emberCss;
    (document.head || document.documentElement).appendChild(st);
  }

  /* ---------- 4. Native bridge ---------- */
  const handlers = {};
  if (navigator.mediaSession) {
    const ms = navigator.mediaSession;
    const orig = ms.setActionHandler.bind(ms);
    ms.setActionHandler = function (action, fn) {
      handlers[action] = fn;
      try { return orig(action, fn); } catch (e) {}
    };
  }
  const call = (action, extra) => {
    const h = handlers[action];
    if (typeof h === 'function') { try { h(Object.assign({ action }, extra || {})); return true; } catch (e) {} }
    return false;
  };

  /* ---------- 5. Auto-resume ----------
   * If the music pauses without you asking (Android backgrounding, screen off),
   * start it again. Pauses you make yourself – tapping pause in the app,
   * notification, lock screen, headphones – are respected. */
  let wantPlay = false, lastGesture = 0;
  ['pointerdown', 'touchstart', 'keydown', 'click'].forEach((t) =>
    document.addEventListener(t, () => { lastGesture = Date.now(); }, true));
  document.addEventListener('play', () => { wantPlay = true; }, true);
  document.addEventListener('pause', (e) => {
    const v = e.target;
    log('pause event (tapped ' + (Date.now() - lastGesture) + 'ms ago, wantPlay=' + wantPlay + ', ad=' + mutedForAd + ')');
    if (Date.now() - lastGesture < 1500 || mutedForAd || (v && v.ended)) {
      if (!mutedForAd && !(v && v.ended)) wantPlay = false;
      return;
    }
    if (wantPlay) setTimeout(resume, 400);
  }, true);
  function resume() {
    const v = q('video');
    if (wantPlay && v && v.paused && !v.ended && Date.now() - lastGesture > 1500) {
      log('auto-resuming playback');
      const p = v.play();
      if (p && p.catch) p.catch((err) => log('resume failed: ' + err));
    }
  }


  /* ---------- 6. Like & quick save ---------- */
  const cfg = (k) => {
    try {
      const y = window.ytcfg;
      if (!y) return undefined;
      return typeof y.get === 'function' ? y.get(k) : (y.data_ || {})[k];
    } catch (e) { return undefined; }
  };
  function currentVideoId() {
    try {
      const p = q('#movie_player');
      const d = p && p.getVideoData && p.getVideoData();
      if (d && d.video_id) return d.video_id;
    } catch (e) {}
    const m = location.search.match(/[?&]v=([\w-]{11})/);
    return m ? m[1] : '';
  }
  function getCookie(name) {
    const hit = document.cookie.split('; ').find((c) => c.startsWith(name + '='));
    return hit ? decodeURIComponent(hit.slice(name.length + 1)) : '';
  }
  async function authHeader() {
    const sid = getCookie('SAPISID') || getCookie('__Secure-3PAPISID');
    if (!sid) return null;
    const ts = Math.floor(Date.now() / 1000);
    const digest = await crypto.subtle.digest('SHA-1',
      new TextEncoder().encode(ts + ' ' + sid + ' ' + location.origin));
    const hex = Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
    return 'SAPISIDHASH ' + ts + '_' + hex;
  }
  // Calls YouTube Music's own API as the signed-in user.
  async function api(endpoint, body) {
    const auth = await authHeader();
    const context = cfg('INNERTUBE_CONTEXT');
    if (!auth || cfg('LOGGED_IN') === false) throw new Error('signin');
    if (!context) throw new Error('page not ready');
    const key = cfg('INNERTUBE_API_KEY');
    const res = await fetch('/youtubei/v1/' + endpoint + '?prettyPrint=false' + (key ? '&key=' + key : ''), {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': auth,
        'X-Origin': location.origin,
        'X-Goog-AuthUser': String(cfg('SESSION_INDEX') || 0),
        'X-Youtube-Client-Name': String(cfg('INNERTUBE_CONTEXT_CLIENT_NAME') || 67),
        'X-Youtube-Client-Version': String(cfg('INNERTUBE_CLIENT_VERSION') || ''),
      },
      body: JSON.stringify(Object.assign({ context }, body)),
    });
    if (res.status === 401 || res.status === 403) throw new Error('signin');
    if (!res.ok) throw new Error('error ' + res.status);
    return res.json();
  }
  const errText = (e, what) =>
    e && e.message === 'signin' ? 'Sign in to YouTube Music to ' + what : 'Couldn\'t ' + what + ' (' + (e && e.message) + ')';
  function done(kind, ok, msg) {
    log(kind + ': ' + msg);
    try { window.EmberNative && window.EmberNative.onAction(kind, ok, msg); } catch (e) {}
    lastKey = '';
    report();
  }

  const likeOverride = {};   // videoId -> liked, for changes made from the notification
  function likeRenderer() {
    return q('ytmusic-player-bar ytmusic-like-button-renderer') ||
      q('ytmusic-player-page ytmusic-like-button-renderer') || q('ytmusic-like-button-renderer');
  }
  function isLiked(id) {
    const el = likeRenderer();
    const status = el ? el.getAttribute('like-status') : null;
    if (status === 'LIKE') return true;
    return !!likeOverride[id];
  }
  async function toggleLike() {
    const id = currentVideoId();
    if (!id) return done('like', false, 'Nothing is playing');
    const want = !isLiked(id);
    try {
      // Tap the page's own like button first so the app UI stays in sync…
      const el = likeRenderer();
      const btn = el && el.querySelector(want
        ? '#button-shape-like button, .like button, [aria-label="Like"]'
        : '#button-shape-like button, .like button, [aria-label="Unlike"], [aria-label="Like"]');
      if (btn) {
        btn.click();
        await new Promise((r) => setTimeout(r, 800));
      }
      const status = el ? el.getAttribute('like-status') : null;
      // …and make sure it really happened via the API (safe to repeat).
      if (status !== (want ? 'LIKE' : 'INDIFFERENT')) {
        await api(want ? 'like/like' : 'like/removelike', { target: { videoId: id } });
      }
      likeOverride[id] = want;
      done('like', true, want ? '👍 Liked – added to Liked music' : 'Like removed');
    } catch (e) {
      done('like', false, errText(e, 'like songs'));
    }
  }

  const SAVE_KEY = 'ember.savePlaylistId';
  const SAVE_NAME = 'Ember Saves';
  const savedIds = new Set();
  async function quickSave() {
    const id = currentVideoId();
    if (!id) return done('save', false, 'Nothing is playing');
    try {
      let playlistId = null;
      try { playlistId = localStorage.getItem(SAVE_KEY); } catch (e) {}
      let added = false;
      if (playlistId) {
        try {
          const r = await api('browse/edit_playlist', {
            playlistId,
            actions: [{ action: 'ACTION_ADD_VIDEO', addedVideoId: id, dedupeOption: 'DEDUPE_OPTION_SKIP' }],
          });
          added = !r || !r.status || r.status === 'STATUS_SUCCEEDED';
        } catch (e) {
          if (e.message === 'signin') throw e;   // otherwise the playlist was probably deleted
        }
      }
      if (!added) {
        const r = await api('playlist/create', {
          title: SAVE_NAME,
          description: 'Songs quick-saved from Ember',
          privacyStatus: 'PRIVATE',
          videoIds: [id],
        });
        if (!r || !r.playlistId) throw new Error('could not create playlist');
        try { localStorage.setItem(SAVE_KEY, r.playlistId); } catch (e) {}
      }
      savedIds.add(id);
      done('save', true, 'Saved to "' + SAVE_NAME + '"');
    } catch (e) {
      done('save', false, errText(e, 'save songs'));
    }
  }

  window.__emberControl = function (cmd, arg) {
    const v = q('video');
    switch (cmd) {
      case 'play':
        wantPlay = true;
        if (!call('play') && v) v.play();
        break;
      case 'pause':
        wantPlay = false;
        lastGesture = Date.now();
        if (!call('pause') && v) v.pause();
        break;
      case 'toggle':
        if (v && v.paused) window.__emberControl('play'); else window.__emberControl('pause');
        break;
      case 'next':
        wantPlay = true;
        call('nexttrack') || click('.next-button, [aria-label="Next"], [aria-label="Next song"]');
        break;
      case 'prev':
        wantPlay = true;
        call('previoustrack') || click('.previous-button, [aria-label="Previous"], [aria-label="Previous song"]');
        break;
      case 'like':
        toggleLike();
        return;
      case 'save':
        quickSave();
        return;
      case 'seek':
        if (!call('seekto', { seekTime: arg / 1000 }) && v) v.currentTime = arg / 1000;
        break;
    }
    setTimeout(report, 150);
  };

  let lastKey = '', lastSent = 0;
  function report() {
    if (!window.EmberNative) return;
    const v = q('video');
    const md = navigator.mediaSession && navigator.mediaSession.metadata;
    let art = '';
    if (md && md.artwork && md.artwork.length) art = md.artwork[md.artwork.length - 1].src;
    const s = {
      title: md ? md.title || '' : '',
      artist: md ? md.artist || '' : '',
      art,
      playing: !!(v && !v.paused && !v.ended && !mutedForAd),
      pos: v ? Math.floor(v.currentTime * 1000) : 0,
      dur: v && isFinite(v.duration) ? Math.floor(v.duration * 1000) : 0,
    };
    s.videoId = currentVideoId();
    s.liked = s.videoId ? isLiked(s.videoId) : false;
    s.saved = savedIds.has(s.videoId);
    const key = [s.title, s.artist, s.art, s.playing, s.dur, s.videoId, s.liked, s.saved].join('|');
    const now = Date.now();
    if (key !== lastKey || now - lastSent > 3000) {
      lastKey = key; lastSent = now;
      try { window.EmberNative.onState(JSON.stringify(s)); } catch (e) {}
    }
  }
  ['play', 'pause', 'ended', 'loadedmetadata', 'seeked'].forEach((t) =>
    document.addEventListener(t, () => { skipAds(); report(); }, true));

  ['waiting', 'stalled', 'error', 'ended', 'emptied'].forEach((t) =>
    document.addEventListener(t, () => log('media ' + t), true));
  window.addEventListener('load', () => log('loaded ' + location.href + ' "' + document.title + '"'));

  let tickCount = 0;
  function tick() {
    addTheme(); skipAds(); report();
    if (++tickCount % 4 === 0) resume(); // every ~2s, as a backup
    if (tickCount % 20 === 0) {
      const v = q('video');
      log('heartbeat video=' + !!v + (v ? ' paused=' + v.paused + ' t=' + v.currentTime.toFixed(1) +
        ' ready=' + v.readyState : '') + ' url=' + location.pathname + location.search);
    }
  }
  setInterval(tick, 500);
  document.addEventListener('DOMContentLoaded', tick);
})();
