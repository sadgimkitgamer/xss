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
    const key = [s.title, s.artist, s.art, s.playing, s.dur].join('|');
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
