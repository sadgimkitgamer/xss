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
  if (window.__emberNoAdBlock) log('ad blocking OFF (test)');
  const origParse = JSON.parse;
  if (!window.__emberNoAdBlock) {
  JSON.parse = function () { return prune(origParse.apply(this, arguments)); };
  const origJson = Response.prototype.json;
  Response.prototype.json = function () { return origJson.apply(this, arguments).then(prune); };
  }
  let initialPR;
  if (!window.__emberNoAdBlock) try {
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

  /* ---------- 5. Pauses caused by other audio ----------
   * YouTube Music's player pauses by itself when another app takes the audio.
   * That's respected, except: during voice chats (keep the music going), and
   * for phone calls (resume once the call is over). */
  let wantPlay = false, lastGesture = 0, interrupted = false, skipping = false;
  const audioMode = () => {   // 0 normal, 1 ringing, 2 phone call, 3 voice chat
    try { return window.EmberNative ? window.EmberNative.audioMode() : 0; } catch (e) { return 0; }
  };
  ['pointerdown', 'touchstart', 'keydown', 'click'].forEach((t) =>
    document.addEventListener(t, () => { lastGesture = Date.now(); }, true));
  document.addEventListener('play', () => { wantPlay = true; interrupted = false; }, true);
  document.addEventListener('pause', (e) => {
    const v = e.target;
    const byUser = Date.now() - lastGesture < 1500;
    if (byUser) { wantPlay = false; interrupted = false; return; }
    if (mutedForAd || (v && v.ended) || skipping || !wantPlay) return;
    const mode = audioMode();
    log('paused by the system (audio mode ' + mode + ')');
    if (mode === 3) setTimeout(resume, 400);          // voice chat: keep playing
    else if (mode === 1 || mode === 2) interrupted = true;   // phone call: resume afterwards
    else wantPlay = false;                            // another app is playing: stay paused
  }, true);
  function resume() {
    const v = q('video');
    if (v && v.paused && !v.ended) {
      log('resuming playback');
      wantPlay = true;
      const p = v.play();
      if (p && p.catch) p.catch((err) => log('resume failed: ' + err));
    }
  }
  function checkInterruption() {
    if (interrupted && audioMode() === 0) {
      interrupted = false;
      log('call over -> resuming');
      setTimeout(resume, 800);
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
  async function api(endpoint, body, opts) {
    const needAuth = !(opts && opts.anonymous);
    const auth = await authHeader();
    const context = cfg('INNERTUBE_CONTEXT');
    if (needAuth && (!auth || cfg('LOGGED_IN') === false)) throw new Error('signin');
    if (!context) throw new Error('page not ready');
    const key = cfg('INNERTUBE_API_KEY');
    const res = await fetch('/youtubei/v1/' + endpoint + '?prettyPrint=false' + (key ? '&key=' + key : ''), {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        ...(auth ? { 'Authorization': auth } : {}),
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

  // Quick save goes to the playlist you picked in Ember (long-press the fox), or to an
  // automatically created private "Ember Saves" playlist if you haven't picked one.
  const SAVE_KEY = 'ember.savePlaylistId';
  const SAVE_NAME_KEY = 'ember.savePlaylistName';
  const SAVE_CHOSEN_KEY = 'ember.savePlaylistChosen';
  const DEFAULT_NAME = 'Ember Saves';
  const store = {
    get: (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) {} },
  };
  const savedIds = new Set();

  async function addToPlaylist(playlistId, id) {
    const r = await api('browse/edit_playlist', {
      playlistId,
      actions: [{ action: 'ACTION_ADD_VIDEO', addedVideoId: id, dedupeOption: 'DEDUPE_OPTION_SKIP' }],
    });
    if (r && r.status && r.status !== 'STATUS_SUCCEEDED') throw new Error(r.status);
  }

  async function quickSave() {
    const id = currentVideoId();
    if (!id) return done('save', false, 'Nothing is playing');
    const chosen = store.get(SAVE_CHOSEN_KEY) === '1';
    let playlistId = store.get(SAVE_KEY);
    let name = store.get(SAVE_NAME_KEY) || DEFAULT_NAME;
    try {
      let added = false;
      if (playlistId) {
        try {
          await addToPlaylist(playlistId, id);
          added = true;
        } catch (e) {
          // Your own playlist failing is an error; the auto playlist is simply recreated
          if (e.message === 'signin' || chosen) throw e;
        }
      }
      if (!added) {
        const r = await api('playlist/create', {
          title: DEFAULT_NAME,
          description: 'Songs quick-saved from Ember',
          privacyStatus: 'PRIVATE',
          videoIds: [id],
        });
        if (!r || !r.playlistId) throw new Error('could not create playlist');
        store.set(SAVE_KEY, r.playlistId);
        store.set(SAVE_NAME_KEY, DEFAULT_NAME);
        name = DEFAULT_NAME;
      }
      savedIds.add(id);
      done('save', true, 'Saved to "' + name + '"' + (chosen ? '' : ' · long-press the fox in Ember to pick your own'));
    } catch (e) {
      done('save', false, errText(e, 'save to "' + name + '"'));
    }
  }

  // Lists the playlists you can add songs to (same list as YouTube Music's "Save to playlist").
  async function listPlaylists() {
    const reply = (o) => { try { window.EmberNative && window.EmberNative.onPlaylists(JSON.stringify(o)); } catch (e) {} };
    try {
      const r = await api('playlist/get_add_to_playlist', {
        videoIds: [currentVideoId() || 'dQw4w9WgXcQ'],
        excludeWatchLater: true,
      });
      const out = [];
      const seen = new Set();
      (function walk(o) {
        if (!o || typeof o !== 'object') return;
        const p = o.playlistAddToOptionRenderer;
        if (p && p.playlistId && !seen.has(p.playlistId)) {
          seen.add(p.playlistId);
          const t = p.title && (p.title.simpleText || (p.title.runs || []).map((x) => x.text).join(''));
          out.push({ id: p.playlistId, title: t || p.playlistId });
        }
        for (const k in o) walk(o[k]);
      })(r);
      reply({
        ok: true,
        playlists: out,
        current: store.get(SAVE_CHOSEN_KEY) === '1' ? store.get(SAVE_KEY) : '',
      });
    } catch (e) {
      reply({ ok: false, error: errText(e, 'load your playlists') });
    }
  }

  function setSavePlaylist(json) {
    try {
      const o = JSON.parse(json || '{}');
      if (o.id) {
        store.set(SAVE_KEY, o.id);
        store.set(SAVE_NAME_KEY, o.title || 'your playlist');
        store.set(SAVE_CHOSEN_KEY, '1');
        savedIds.clear();
        done('pick', true, 'Quick save → "' + (o.title || 'your playlist') + '"');
      } else {
        store.set(SAVE_CHOSEN_KEY, null);
        if (store.get(SAVE_NAME_KEY) !== DEFAULT_NAME) { store.set(SAVE_KEY, null); store.set(SAVE_NAME_KEY, null); }
        savedIds.clear();
        done('pick', true, 'Quick save → "' + DEFAULT_NAME + '"');
      }
    } catch (e) {}
  }

  /* ---------- 7. Skip that works from the notification ----------
   * The phone layout of YouTube Music often has no Next/Previous button in the
   * page until the full player is opened, so try several ways and check that
   * the song really changed before giving up. */
  async function skip(dir) {
    if (skipping) return;
    skipping = true;
    lastSkipAt = Date.now();
    try {
      const v = q('video');
      if (dir < 0 && v && v.currentTime > 4) {   // like every player: first tap restarts the song
        v.currentTime = 0;
        log('prev: restarted song');
        return;
      }
      const before = currentVideoId();
      const player = q('#movie_player');
      const sel = dir > 0
        ? '.next-button, ytmusic-player-bar .next-button button, [aria-label="Next"], [aria-label="Next song"], [title="Next"]'
        : '.previous-button, ytmusic-player-bar .previous-button button, [aria-label="Previous"], [aria-label="Previous song"], [title="Previous"]';
      const queueItem = () => {
        const cur = q('ytmusic-player-queue-item[selected], ytmusic-player-queue-item[play-button-state="playing"], ytmusic-player-queue-item[play-button-state="paused"]');
        if (!cur) return null;
        let el = dir > 0 ? cur.nextElementSibling : cur.previousElementSibling;
        while (el && el.tagName !== 'YTMUSIC-PLAYER-QUEUE-ITEM') el = dir > 0 ? el.nextElementSibling : el.previousElementSibling;
        return el && (el.querySelector('.song-title, yt-formatted-string') || el);
      };
      // YouTube Music's own controls keep its queue in sync. In the mini player the
      // Next/Previous buttons aren't on the page, so open the player briefly, press
      // the button, and minimize it again.
      const expandAndPress = () => {
        if (playerOpen()) return false;
        const bar = 'ytmusic-player-bar .middle-controls .content-info-wrapper, ytmusic-player-bar .middle-controls, ytmusic-player-bar';
        if (!(tapEl(bar) || click(bar))) {
          log('skip: no player bar to open');
          return false;
        }
        return new Promise(async (resolve) => {
          for (let i = 0; i < 15 && !playerOpen(); i++) await new Promise((r) => setTimeout(r, 100));
          await new Promise((r) => setTimeout(r, 250));
          const ok = playerOpen() && (click(sel) || tapEl(sel));
          log('skip: opened player=' + playerOpen() + ', pressed button=' + ok);
          setTimeout(() => click('[aria-label="Minimize player page"], [aria-label="Minimise player page"], [aria-label="Minimize"]'), 1200);
          resolve(ok);
        });
      };
      // Ask YouTube Music to navigate to the neighbouring song of the current queue.
      const navigateInQueue = () => {
        try {
          if (!player || typeof player.getPlaylist !== 'function') return false;
          const list = player.getPlaylist() || [];
          const idx = player.getPlaylistIndex();
          const target = list[idx + dir];
          const pid = (player.getPlaylistId && player.getPlaylistId()) ||
            (location.search.match(/[?&]list=([^&]+)/) || [])[1];
          log('skip: queue ' + (idx + 1) + '/' + list.length + ' -> ' + target);
          if (!target) return false;
          const endpoint = { watchEndpoint: Object.assign({ videoId: target }, pid ? { playlistId: pid, index: idx + dir } : {}) };
          const app = q('ytmusic-app') || document.body;
          app.dispatchEvent(new CustomEvent('yt-navigate', { bubbles: true, composed: true, detail: { endpoint } }));
          return true;
        } catch (e) {
          log('skip: queue navigation error ' + e);
          return false;
        }
      };
      // Polymer buttons react to "tap" gestures, which a plain click() doesn't always produce
      const tapEl = (selector) => {
        const el = q(selector);
        if (!el) return false;
        el.dispatchEvent(new CustomEvent('tap', { bubbles: true, composed: true, detail: { x: 0, y: 0 } }));
        return true;
      };
      // Ask YouTube Music for the queue around the current song and go to the neighbour.
      const askAndGo = async () => {
        const vid = currentVideoId();
        if (!vid) return false;
        const pid = (location.search.match(/[?&]list=([^&]+)/) || [])[1] ||
          (player && player.getPlaylistId && player.getPlaylistId()) || ('RDAMVM' + vid);
        const r = await api('next', { videoId: vid, playlistId: pid, isAudioOnly: true }, { anonymous: true });
        const items = [];
        (function walk(o) {
          if (!o || typeof o !== 'object') return;
          const v = o.playlistPanelVideoRenderer;
          if (v && v.videoId) items.push(v);
          for (const k in o) if (k !== 'playlistPanelVideoRenderer') walk(o[k]);
          if (v) for (const k in v) walk(v[k]);
        })(r);
        let at = items.findIndex((v) => v.selected || v.videoId === vid);
        const target = items[at + dir] || (dir > 0 && at < 0 ? items.find((v) => v.videoId !== vid) : null);
        log('skip: queue from YouTube has ' + items.length + ' songs, current #' + (at + 1) + ' -> ' + (target && target.videoId));
        if (!target) return false;
        const endpoint = { watchEndpoint: { videoId: target.videoId, playlistId: pid } };
        const app = q('ytmusic-app') || document.body;
        app.dispatchEvent(new CustomEvent('yt-navigate', { bubbles: true, composed: true, detail: { endpoint } }));
        // If YouTube Music ignored the in-page navigation, load the song's page directly
        setTimeout(() => {
          if (currentVideoId() === vid) {
            log('skip: loading the next song page');
            try { sessionStorage.setItem('ember.autoplay', '1'); } catch (e) {}
            location.assign('/watch?v=' + target.videoId + '&list=' + encodeURIComponent(pid));
          }
        }, 2500);
        return true;
      };
      const methods = [
        ['media session', () => call(dir > 0 ? 'nexttrack' : 'previoustrack')],
        ['button', () => playerOpen() && click(sel)],
        ['button tap', () => tapEl(sel)],
        ['open player + button', expandAndPress],
        ['queue navigation', navigateInQueue],
        ['ask youtube for the queue', askAndGo],
      ];
      for (const [name, fn] of methods) {
        let tried = false;
        try { tried = !!(await fn()); } catch (e) {}
        if (!tried) continue;
        for (let i = 0; i < 20; i++) {           // wait up to ~4s for the song to change
          await new Promise((r) => setTimeout(r, 200));
          if (currentVideoId() !== before) {
            log((dir > 0 ? 'next' : 'prev') + ' via ' + name + ' -> ' + currentVideoId());
            // make sure the new song actually starts
            for (let j = 0; j < 30; j++) {
              await new Promise((r) => setTimeout(r, 300));
              const nv = q('video');
              if (nv && nv.readyState >= 3 && nv.currentTime > 0.3) { log('new song playing'); return; }
              if (nv && nv.paused && j === 10) { wantPlay = true; const pp = nv.play(); if (pp && pp.catch) pp.catch(() => {}); }
            }
            const stuckId = currentVideoId();
            log('new song did not start (ready=' + (q('video') || {}).readyState + ') -> reloading it');
            try { sessionStorage.setItem('ember.autoplay', '1'); } catch (e) {}
            const list = (location.search.match(/[?&]list=([^&]+)/) || [])[1];
            location.assign('/watch?v=' + stuckId + (list ? '&list=' + list : ''));
            return;
          }
        }
      }
      log((dir > 0 ? 'next' : 'prev') + ' failed (handlers: ' + Object.keys(handlers).join(',') + ')');
    } finally {
      skipping = false;
      setTimeout(report, 300);
    }
  }

  /* ---------- 8. Swipe down on the player to go back ---------- */
  const layout = () => q('ytmusic-app-layout');
  function playerUiState() {
    const l = layout();
    return l ? (l.getAttribute('player-ui-state') || l.getAttribute('player-ui-state_') || l.playerUiState || '') : '';
  }
  function playerOpen() {
    const l = layout();
    const st = String(playerUiState());
    if (st) return st === 'PLAYER_PAGE_OPEN' || st === 'FULLSCREEN';
    if (l && (l.hasAttribute('player-page-open') || l.playerPageOpen_)) return true;
    const page = q('ytmusic-player-page');
    return !!(page && page.hasAttribute('player-page-open'));
  }
  const page = () => q('ytmusic-player-page') || q('#player-page');
  function setDrag(dy) {
    const el = page();
    if (!el) return;
    el.style.transition = dy ? 'none' : 'transform .2s ease, opacity .2s ease';
    el.style.transform = dy ? 'translateY(' + dy + 'px)' : '';
    el.style.opacity = dy ? String(Math.max(0.4, 1 - dy / 900)) : '';
  }
  async function minimizePlayer() {
    const methods = [
      ['button', () => click('[aria-label="Minimize player page"], [aria-label="Minimise player page"], ' +
        '[aria-label="Minimize"], [aria-label="Close player"], [aria-label="Collapse"], ' +
        '[aria-label="Minimise"], .player-minimize-button, ytmusic-player-page #minimize-button')],
      ['player bar', () => click('ytmusic-player-bar .middle-controls .content-info-wrapper, ytmusic-player-bar .middle-controls')],
      ['back', () => { if (history.length > 1) { history.back(); return true; } return false; }],
      ['home', () => click('ytmusic-pivot-bar-item-renderer[tab-id="FEmusic_home"], ytmusic-guide-entry-renderer a[href="/"], a[href="/"]')],
    ];
    for (const [name, fn] of methods) {
      let tried = false;
      try { tried = !!fn(); } catch (e) {}
      if (!tried) continue;
      for (let i = 0; i < 6; i++) {
        await new Promise((r) => setTimeout(r, 120));
        if (!playerOpen()) { log('swipe down -> closed player via ' + name); return; }
      }
    }
    log('swipe down -> could not close player (state=' + playerUiState() + ')');
  }
  (function swipeDown() {
    let sx = 0, sy = 0, st = 0, active = false, dragging = false;
    const blocked = (t) => t && t.closest && t.closest(
      'tp-yt-paper-slider, #progress-bar, input, textarea, ytmusic-player-queue, #side-panel, .slider');
    // Don't steal the gesture from lists you're scrolling (Up next, Lyrics, Related)
    const scrolledParent = (t) => {
      const list = t && t.closest && t.closest('ytmusic-tab-renderer, #tab-renderer, .tab-content, ytmusic-player-queue');
      return !!(list && list.scrollTop > 0);
    };
    document.addEventListener('touchstart', (e) => {
      active = false; dragging = false;
      if (e.touches.length !== 1 || !playerOpen()) return;
      const t = e.target;
      if (blocked(t) || scrolledParent(t)) { log('swipe ignored (on ' + (t.tagName || '?').toLowerCase() + ')'); return; }
      sx = e.touches[0].clientX; sy = e.touches[0].clientY; st = Date.now(); active = true;
    }, { capture: true, passive: true });
    document.addEventListener('touchmove', (e) => {
      if (!active) return;
      const dx = e.touches[0].clientX - sx, dy = e.touches[0].clientY - sy;
      if (!dragging && (dy < -10 || Math.abs(dx) > Math.max(30, dy))) { active = false; setDrag(0); return; }
      if (dy > 10) { dragging = true; setDrag(dy * 0.7); }
    }, { capture: true, passive: true });
    const end = (e) => {
      if (!active) return;
      active = false;
      const p = (e.changedTouches && e.changedTouches[0]) || { clientX: sx, clientY: sy };
      const dx = p.clientX - sx, dy = p.clientY - sy, dt = Date.now() - st;
      // A short drag or a quick flick is enough
      const far = dy > Math.min(110, window.innerHeight * 0.15);
      const flick = dy > 40 && dt < 300;
      setDrag(0);
      if (dragging && (far || flick) && Math.abs(dx) < dy * 0.7) minimizePlayer();
      else if (dy > 40) log('swipe too short/sideways (dy=' + Math.round(dy) + ', dx=' + Math.round(dx) + ')');
      dragging = false;
    };
    document.addEventListener('touchend', end, { capture: true, passive: true });
    document.addEventListener('touchcancel', () => { active = false; dragging = false; setDrag(0); }, { capture: true, passive: true });
  })();

  /* ---------- 9. Loop: off → playlist → song ---------- */
  const LOOP_NAMES = ['Loop off', '🔁 Looping this playlist', '🔂 Looping this song'];
  let repeatMode = 0;   // 0 off, 1 playlist, 2 song (kept across page reloads)
  try { repeatMode = +sessionStorage.getItem('ember.repeat') || 0; } catch (e) {}
  let loopGuard = 0;
  function restartForLoop(v) {
    if (Date.now() - loopGuard < 1500) return;
    loopGuard = Date.now();
    v.currentTime = 0;
    const p = v.play();
    if (p && p.catch) p.catch(() => {});
    log('loop: restarted song');
  }
  // Song loop: jump back just before the end so YouTube Music never moves on…
  document.addEventListener('timeupdate', (e) => {
    const v = e.target;
    if (repeatMode !== 2 || mutedForAd || !v || !isFinite(v.duration) || v.duration < 5) return;
    if (v.duration - v.currentTime < 0.9) restartForLoop(v);
  }, true);

  // Playlist loop: know whether the current song is the playlist's last one, and if so
  // go back to its first song when it ends (instead of YouTube Music's autoplay).
  let queueInfo = null, queueLoading = false;
  function collectQueue(r) {
    const items = [];
    (function walk(o) {
      if (!o || typeof o !== 'object') return;
      if (o.playlistPanelVideoRenderer && o.playlistPanelVideoRenderer.videoId) {
        items.push(o.playlistPanelVideoRenderer);
        return;
      }
      if (o.automixPreviewVideoRenderer) return;   // autoplay suggestions aren't the playlist
      for (const k in o) walk(o[k]);
    })(r);
    return items;
  }
  async function refreshQueueInfo() {
    const vid = currentVideoId();
    const pid = (location.search.match(/[?&]list=([^&]+)/) || [])[1];
    queueLoading = true;
    try {
      if (!vid || !pid || /^RD/.test(pid)) { queueInfo = { videoId: vid, last: false }; return; }
      const items = collectQueue(await api('next', { videoId: vid, playlistId: pid }, { anonymous: true }));
      const at = items.findIndex((v) => v.selected || v.videoId === vid);
      queueInfo = {
        videoId: vid, pid,
        first: items[0] && items[0].videoId,
        last: items.length > 1 && at === items.length - 1,
      };
      log('loop: song ' + (at + 1) + ' of ' + items.length + (queueInfo.last ? ' (last – will start over)' : ''));
    } catch (e) {
      queueInfo = { videoId: vid, last: false };
    } finally {
      queueLoading = false;
    }
  }
  function goToSong(videoId, pid) {
    const before = currentVideoId();
    const endpoint = { watchEndpoint: Object.assign({ videoId }, pid ? { playlistId: pid } : {}) };
    (q('ytmusic-app') || document.body).dispatchEvent(
      new CustomEvent('yt-navigate', { bubbles: true, composed: true, detail: { endpoint } }));
    setTimeout(() => {
      if (currentVideoId() === before) {
        try { sessionStorage.setItem('ember.autoplay', '1'); } catch (e) {}
        location.assign('/watch?v=' + videoId + (pid ? '&list=' + encodeURIComponent(pid) : ''));
      }
    }, 2500);
  }
  // Song loop safety net: if the song changed by itself anyway, go back to the looped one.
  // (Picking or skipping to another song yourself makes that the looped song instead.)
  let loopVideoId = null, lastSkipAt = 0;
  function maintainLoop() {
    const vid = currentVideoId();
    if (repeatMode === 2 && vid) {
      const userChose = Date.now() - lastGesture < 6000 || Date.now() - lastSkipAt < 10000 || skipping;
      if (!loopVideoId || userChose) loopVideoId = vid;
      else if (vid !== loopVideoId) {
        log('loop: song changed by itself -> back to the looped song');
        const target = loopVideoId;
        loopVideoId = null;
        goToSong(target, (location.search.match(/[?&]list=([^&]+)/) || [])[1]);
        return;
      }
    } else {
      loopVideoId = null;
    }
    if (repeatMode === 1 && !queueLoading && (!queueInfo || queueInfo.videoId !== currentVideoId())) refreshQueueInfo();
  }
  document.addEventListener('ended', (e) => {
    if (mutedForAd) return;
    if (repeatMode === 2) {                       // …and if it does reach the end, stop YouTube Music seeing it
      e.stopImmediatePropagation();
      restartForLoop(e.target);
    } else if (repeatMode === 1 && queueInfo && queueInfo.last && queueInfo.first &&
               queueInfo.videoId === currentVideoId()) {
      e.stopImmediatePropagation();
      log('loop: end of playlist -> back to the first song');
      goToSong(queueInfo.first, queueInfo.pid);
    }
  }, true);

  function toggleRepeat() {
    repeatMode = (repeatMode + 1) % 3;
    try { sessionStorage.setItem('ember.repeat', String(repeatMode)); } catch (e) {}
    queueInfo = null;
    maintainLoop();
    done('repeat', true, LOOP_NAMES[repeatMode]);
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
        skip(1);
        break;
      case 'prev':
        wantPlay = true;
        skip(-1);
        break;
      case 'like':
        toggleLike();
        return;
      case 'save':
        quickSave();
        return;
      case 'repeat':
        toggleRepeat();
        return;
      case 'playlists':
        listPlaylists();
        return;
      case 'setSavePlaylist':
        setSavePlaylist(arg);
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
    // The page doesn't always fill in media-session info, so fall back to the player itself
    let vd = null;
    try { const p = q('#movie_player'); vd = p && p.getVideoData ? p.getVideoData() : null; } catch (e) {}
    const text = (sel) => { const el = q(sel); return el ? (el.getAttribute('title') || el.textContent || '').trim() : ''; };
    const title = (md && md.title) || (vd && vd.title) ||
      text('ytmusic-player-bar .title') || text('ytmusic-player-page .title') || '';
    let artist = (md && md.artist) || (vd && vd.author) ||
      text('ytmusic-player-bar .byline') || text('ytmusic-player-page .byline') || '';
    artist = artist.replace(/\s*-\s*Topic$/, '').split(' • ')[0];
    if (!art) {
      const img = q('ytmusic-player-bar img.image, ytmusic-player-bar img');
      if (img && img.src && img.src.startsWith('http')) art = img.src;
      else if (vd && vd.video_id) art = 'https://i.ytimg.com/vi/' + vd.video_id + '/hqdefault.jpg';
    }
    const s = {
      title,
      artist,
      art,
      playing: !!(v && !v.paused && !v.ended && !mutedForAd),
      pos: v ? Math.floor(v.currentTime * 1000) : 0,
      dur: v && isFinite(v.duration) ? Math.floor(v.duration * 1000) : 0,
    };
    s.videoId = currentVideoId();
    s.liked = s.videoId ? isLiked(s.videoId) : false;
    s.saved = savedIds.has(s.videoId);
    s.repeat = repeatMode;
    const key = [s.title, s.artist, s.art, s.playing, s.dur, s.videoId, s.liked, s.saved, s.repeat].join('|');
    const now = Date.now();
    if (key !== lastKey || now - lastSent > 3000) {
      lastKey = key; lastSent = now;
      try { window.EmberNative.onState(JSON.stringify(s)); } catch (e) {}
    }
  }
  ['play', 'pause', 'ended', 'loadedmetadata', 'seeked'].forEach((t) =>
    document.addEventListener(t, () => { skipAds(); report(); }, true));

  ['waiting', 'stalled', 'ended', 'emptied'].forEach((t) =>
    document.addEventListener(t, () => log('media ' + t), true));
  document.addEventListener('error', (e) => {
    const err = e.target && e.target.error;
    if (e.target && e.target.tagName === 'VIDEO') log('media error ' + (err ? err.code + ' ' + err.message : '?'));
  }, true);
  window.addEventListener('load', () => log('loaded ' + location.href + ' "' + document.title + '"'));

  let pendingAutoplay = false;
  try { pendingAutoplay = sessionStorage.getItem('ember.autoplay') === '1'; sessionStorage.removeItem('ember.autoplay'); } catch (e) {}
  function autoplayAfterSkip() {
    if (!pendingAutoplay) return;
    const v = q('video');
    if (v && v.readyState >= 2) {
      pendingAutoplay = false;
      if (v.paused) { wantPlay = true; const p = v.play(); if (p && p.catch) p.catch(() => {}); }
      log('skip: playing the new song');
    }
  }

  let tickCount = 0;
  function tick() {
    addTheme(); skipAds(); report();
    if (++tickCount % 2 === 0) checkInterruption();
    autoplayAfterSkip();
    if (tickCount % 4 === 0) maintainLoop();
    if (tickCount % 20 === 0) {
      const v = q('video');
      log('heartbeat video=' + !!v + (v ? ' paused=' + v.paused + ' t=' + v.currentTime.toFixed(1) +
        ' ready=' + v.readyState : '') + ' url=' + location.pathname + location.search +
        ' player=' + (playerOpen() ? 'open' : 'mini') + '(' + playerUiState() + ')');
    }
  }
  setInterval(tick, 500);
  document.addEventListener('DOMContentLoaded', tick);
})();
