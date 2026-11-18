(function () {
  'use strict';
  // Observe the site's normal, user-initiated session. Do not generate credentials,
  // submit gates, solve challenges, or acknowledge "still watching" for the user.
  if (window.__omeRds) return;
  var state = window.__omeRds = {
    revision: 0, url: '', token: '', mode: '', expiresAt: 0, error: '', native: true,
    external: [], sources: [], autoStarted: false,
    tokenRequests: 0, tokenGranted: 0, tokenRejected: 0
  };
  var originalFetch = window.fetch;
  function tokenResponse(requestUrl, json) {
    try {
      var request = new URL(requestUrl, location.href);
      var cfg = document.getElementById('rds-player-config');
      var stream = cfg && JSON.parse(cfg.textContent).stream;
      if (!stream || request.origin !== new URL(stream).origin) return;
      if (request.pathname === '/media-hb' && json.revoked) {
        state.error = 'session_revoked'; return;
      }
      if (request.pathname !== '/stream-token' || !request.searchParams.has('source')) return;
      if (json.token) {
        state.tokenGranted++;
        var source = new URL(request.searchParams.get('source'));
        if (source.origin !== request.origin || !/^\/[a-zA-Z0-9/_-]+\/index\.m3u8$/.test(source.pathname)) return;
        state.url = json.url || source.origin + '/media' + source.pathname;
        state.token = json.token;
        state.mode = json.mode || '';
        state.expiresAt = Date.now() + Math.max(15, Number(json.ttl) || 600) * 1000;
        state.error = ''; state.revision++;
      } else if (/^(still_watching|verification_required|session_revoked|vid_revoked|vid_shared|geo|gate|banned|forbidden|rate_limited|too_many_channels)$/.test(json.error || '')) {
        state.tokenRejected++;
        state.error = json.error;
      }
    } catch (ignored) {}
  }
  window.fetch = function (input, options) {
    var url = typeof input === 'string' ? input : input && input.url;
    if (url && /\/stream-token\?/.test(url) && /[?&]source=/.test(url)) state.tokenRequests++;
    return originalFetch.apply(this, arguments).then(function (response) {
      if (url && /\/(stream-token|media-hb)(\?|$)/.test(url)) {
        response.clone().json().then(function (json) { tokenResponse(url, json); }).catch(function () {});
      }
      if (url && new URL(url, location.href).origin === location.origin && /admin-ajax\.php/.test(url)) {
        response.clone().json().then(function (json) {
          if (json.success && json.data && json.data.sources) {
            state.sources = json.data.sources;
            state.external = json.data.sources.filter(function (source) {
              return source.k === 'hls' && /^https:\/\//.test(source.u || '');
            }).map(function (source) { return source.u; });
          }
        }).catch(function () {});
      }
      return response;
    });
  };
  // Match RDS Test: keep the site's renewal/heartbeat, but stop its video
  // downloader as soon as ExoPlayer takes over. Running both can consume
  // multiple concurrent channel slots on the provider.
  state.useNative = function (enabled) {
    state.native = enabled;
    if (state.hls) {
      if (enabled) state.hls.stopLoad();
      else state.hls.startLoad(-1);
    }
    document.querySelectorAll('video').forEach(function (video) {
      if (enabled) {
        if (video.__omePreviousMuted === undefined) video.__omePreviousMuted = video.muted;
        video.muted = true;
        video.pause();
      } else if (video.__omePreviousMuted !== undefined) {
        video.muted = video.__omePreviousMuted;
        delete video.__omePreviousMuted;
      }
    });
  };
  function hookHls(Hls) {
    if (Hls && !Hls.prototype.__omeHooked) {
      var proto = Hls.prototype, load = proto.loadSource;
      proto.__omeHooked = true;
      proto.loadSource = function () {
        state.hls = this;
        var url = String(arguments[0] || '');
        if (state.external.indexOf(url) !== -1 && state.url !== url) {
          state.url = url; state.token = ''; state.mode = 'direct'; state.error = '';
          state.expiresAt = Date.now() + 12 * 3600000;
          state.revision++;
        }
        if (state.native) this.config.autoStartLoad = false;
        return load.apply(this, arguments);
      };
    }
  }
  // Hook before the dynamically loaded HLS library first attaches a video.
  var hlsGlobal = window.Hls;
  try {
    Object.defineProperty(window, 'Hls', { configurable: true,
      get: function () { return hlsGlobal; },
      set: function (value) { hlsGlobal = value; hookHls(value); }
    });
  } catch (ignored) {}
  hookHls(hlsGlobal);
  var timer = setInterval(function () {
    hookHls(window.Hls);
    var root = document.querySelector('[data-rp-player]');
    var gate = document.querySelector('[data-rp-gate]');
    if (gate && !state.gateSubmitted) {
      var cft = gate.querySelector('input[name="cft"]');
      var viewer = ''; try { viewer = localStorage.getItem('rds_vid') || ''; } catch (ignored) {}
      // Submit the site's ordinary start form only after its own verification
      // has supplied a token, or it recognizes an existing paired viewer.
      if ((cft && cft.value) || viewer) {
        var submit = gate.querySelector('button[type="submit"], input[type="submit"]');
        if (submit) { state.gateSubmitted = true; submit.click(); }
      }
    }
    if (!state.autoStarted && root && root.rdsPlayer) {
      var start = root.querySelector('[data-player-start]');
      state.autoStarted = true;
      if (start && !start.hidden) {
        root.rdsPlayer.playLive();
      }
    }
    if (state.native) {
      if (state.hls) state.hls.stopLoad();
      document.querySelectorAll('video').forEach(function (video) {
        if (!video.paused) video.pause();
      });
    }
  }, 500);
  window.addEventListener('pagehide', function () { clearInterval(timer); });
  state.selectSource = function (index) {
    var buttons = document.querySelectorAll('[data-rp-src]');
    for (var i = 0; i < buttons.length; i++) {
      if (Number(buttons[i].getAttribute('data-rp-src')) === index) {
        state.error = ''; buttons[i].click(); return true;
      }
    }
    return index === 0 && state.sources.length === 1;
  };
  state.selectQuality = function (id) {
    var buttons = document.querySelectorAll('[data-q]');
    for (var i = 0; i < buttons.length; i++) {
      if (buttons[i].getAttribute('data-q') === id) {
        state.error = ''; buttons[i].click(); return true;
      }
    }
    return false;
  };
  state.snapshot = function () {
    var prompt = '', selected = 0, quality = '';
    if (/prea multe canale (simultan|deschise)/i.test(
      document.body && document.body.innerText || '')) state.error = 'too_many_channels';
    document.querySelectorAll('[data-rp-src]').forEach(function (button) {
      if (button.getAttribute('aria-selected') === 'true') selected = Number(button.getAttribute('data-rp-src'));
    });
    document.querySelectorAll('[data-q]').forEach(function (button) {
      if (button.getAttribute('aria-checked') === 'true') quality = button.getAttribute('data-q');
    });
    document.querySelectorAll('.rp__dialog').forEach(function (dialog) {
      if (!dialog.hidden && getComputedStyle(dialog).display !== 'none') {
        prompt = (dialog.innerText || '').substring(0, 400);
      }
    });
    return JSON.stringify({ revision: state.revision, url: state.url, token: state.token,
      mode: state.mode, expiresAt: state.expiresAt, error: state.error, prompt: prompt,
      ready: true,
      selectedSource: selected, quality: quality,
      tokenRequests: state.tokenRequests, tokenGranted: state.tokenGranted,
      tokenRejected: state.tokenRejected,
      sources: state.sources.map(function (source, index) {
        return {index: index, name: source.label || source.name || source.title || ('S' + (index + 1)),
          kind: source.k, qualities: (source.variants || []).map(function (variant) {
            return {id: variant.id, name: variant.label || variant.name ||
              (variant.id === '480' ? 'SD · 480p' : variant.id === 'hd' ? 'HD / Full HD' : variant.id)};
          })};
      }) });
  };
})();
