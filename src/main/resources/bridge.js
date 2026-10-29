// Injected into music.youtube.com. Reports now-playing state to Java via window.cefQuery
// whenever it changes, and gives Java a small API (window.__ytmd) for driving the player.
// Polls once a second, which is simpler and sturdier than hooking YT Music's internal events.
(() => {
  if (window.__ytmd) return;

  const PREFIX = '__PREFIX__';
  const COMMAND_PREFIX = '__COMMAND_PREFIX__';
  // These selectors are what break if YouTube changes its markup.
  const BUTTONS = {
    playPause: 'ytmusic-player-bar #play-pause-button',
    next: 'ytmusic-player-bar .next-button',
    previous: 'ytmusic-player-bar .previous-button',
  };
  const VOLUME_SLIDER = 'ytmusic-player-bar #volume-slider';
  // Like/dislike: the renderer's like-status attribute is LIKE, DISLIKE or INDIFFERENT. Not part of
  // the BUTTONS health check; a missing renderer is reported as an empty likeStatus instead.
  const LIKE_RENDERER = 'ytmusic-player-bar ytmusic-like-button-renderer';
  const RATING_BUTTONS = {
    like: '.like-button-wrapper button', // Inside a wrapper div...
    dislike: ':scope > yt-button-shape button', // ...while the dislike button sits directly under the renderer.
  };
  // Polls in a row the buttons must be missing before reporting it, so a slow first render doesn't count.
  const MISSING_POLLS = 5;
  // "Pause at end of track" fires this many seconds early, so the next track doesn't start first.
  // timeupdate fires about every 250 ms while playing.
  const END_MARGIN = 0.75;

  let last = '';
  let sleepStatus = '';
  let missingPolls = 0;
  let pauseAtTrackEnd = false;
  let armedTrack = '';

  const video = () => document.querySelector('video');
  const player = () => document.querySelector('#movie_player');
  const click = name => document.querySelector(BUTTONS[name])?.click();
  const isAd = () => !!document.querySelector('#movie_player.ad-showing');

  // Ads also fill in mediaSession metadata; report them as "no track" instead.
  const metadata = () => (isAd() ? null : navigator.mediaSession.metadata);
  const trackKey = () => {
    const md = metadata();
    return md ? md.title + '\n' + md.artist : '';
  };

  // The track's ID, from the player (which knows it even during an ad) or the address bar.
  function videoId() {
    const data = player()?.getVideoData?.();
    return (data && data.video_id) || new URLSearchParams(location.search).get('v') || '';
  }

  const likeStatus = () => document.querySelector(LIKE_RENDERER)?.getAttribute('like-status') || '';

  // The volume as YT Music's own slider shows it (0 when muted).
  function volume() {
    const slider = document.querySelector(VOLUME_SLIDER);
    if (slider) return Math.round(slider.value);
    const p = player();
    if (!p || !p.getVolume) return -1;
    return p.isMuted() ? 0 : p.getVolume();
  }

  function report() {
    const md = metadata();
    const v = video();
    const artwork = md && md.artwork.length ? md.artwork[md.artwork.length - 1].src : '';
    const json = JSON.stringify({
      title: md ? md.title : '',
      artist: md ? md.artist : '',
      album: md ? md.album : '',
      artworkUrl: artwork,
      duration: v && isFinite(v.duration) ? v.duration : 0,
      // Whole seconds, so a playing track changes the report once a second at most.
      currentTime: v && isFinite(v.currentTime) ? Math.floor(v.currentTime) : 0,
      playing: !!v && !v.paused,
      volume: volume(),
      controlsAvailable: missingPolls < MISSING_POLLS,
      pauseAtTrackEnd,
      videoId: md ? videoId() : '',
      likeStatus: md ? likeStatus() : '',
    });
    if (json !== last) {
      last = json;
      window.cefQuery({ request: PREFIX + json, onSuccess() {}, onFailure() {} });
    }
  }

  function pause() {
    const v = video();
    if (v && !v.paused) click('playPause');
  }

  function play() {
    const v = video();
    if (v && v.paused) click('playPause');
  }

  /**
   * Sets the volume (0-100) as if the user moved YT Music's slider, so its UI and saved volume stay
   * in step (it maps the slider onto the player's volume on a curve, and unmutes).
   * #movie_player.setVolume() alone would leave the slider stale, so it's only the fallback if the
   * slider can't be found.
   */
  function setVolume(value) {
    const level = Math.max(0, Math.min(100, Math.round(value)));
    const slider = document.querySelector(VOLUME_SLIDER);
    const p = player();
    if (slider) {
      slider.value = level;
      slider.dispatchEvent(new CustomEvent('change'));
    } else if (p && p.setVolume) {
      p.setVolume(level);
      if (level > 0 && p.isMuted()) p.unMute();
    }
    report();
  }

  function rate(name) {
    document.querySelector(LIKE_RENDERER)?.querySelector(RATING_BUTTONS[name])?.click();
    report();
  }

  // Pauses once the armed track is about to end, or has stopped being the current track
  // (skipped, or the end was missed between timeupdates).
  function checkTrackEnd() {
    if (!pauseAtTrackEnd) return;
    const v = video();
    const nearEnd = v && !isAd() && isFinite(v.duration) && v.duration - v.currentTime < END_MARGIN;
    if (nearEnd || trackKey() !== armedTrack) {
      pauseAtTrackEnd = false;
      pause();
      report();
    }
  }


  // --- Extra controls in YT Music's player bar: mini-player toggle and sleep timer ---------------
  // The timer itself lives in Java; these buttons only send it commands and show its status.
  const CONTROLS_PARENT = 'ytmusic-player-bar .right-controls-buttons, ytmusic-player-bar .right-controls';
  const MIN_CUSTOM_MINUTES = 0.1; // Fractions work: 0.5 is 30 seconds.
  const MAX_CUSTOM_MINUTES = 1440;
  const SLEEP_CHOICES = [['15', '15 minutes'], ['30', '30 minutes'], ['60', '60 minutes'], ['track', 'End of current track']];
  // Path data for 24x24 icons (built with DOM calls: YouTube's Trusted Types rules reject innerHTML).
  const ICON_MOON = 'M12.3 3a9 9 0 1 0 8.7 11.6A7 7 0 0 1 12.3 3z';
  const ICON_MINI = 'M19 7H5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2zm0 10H5V9h14v8zm-1-4h-6v3h6v-3z';

  const sendCommand = command =>
    window.cefQuery({ request: COMMAND_PREFIX + command, onSuccess() {}, onFailure() {} });

  function addStyles() {
    const style = document.createElement('style');
    style.id = 'ytmd-style';
    style.textContent = `
      #ytmd-controls { display: inline-flex; align-items: center; }
      #ytmd-controls button { display: inline-flex; align-items: center; gap: 4px; padding: 8px; border: 0;
        background: none; cursor: pointer; color: var(--ytmusic-text-secondary, #aaa); }
      #ytmd-controls button:hover, #ytmd-controls button.active { color: var(--ytmusic-text-primary, #fff); }
      #ytmd-controls .status { font-size: 12px; }
      #ytmd-menu { position: fixed; z-index: 100000; min-width: 190px; padding: 8px 0; border-radius: 8px;
        background: var(--ytmusic-menu-background, #212121); color: var(--ytmusic-text-primary, #fff);
        box-shadow: 0 4px 24px rgba(0, 0, 0, .6); font-size: 14px; }
      #ytmd-menu div { padding: 10px 16px; cursor: pointer; }
      #ytmd-menu div:hover { background: rgba(255, 255, 255, .1); }
      #ytmd-menu div.disabled { opacity: .4; pointer-events: none; }
      #ytmd-menu div.custom { display: flex; align-items: center; gap: 8px; cursor: default; }
      #ytmd-menu div.custom:hover { background: none; }
      #ytmd-menu input { width: 64px; padding: 4px 6px; border: 1px solid rgba(255, 255, 255, .3); border-radius: 4px;
        background: transparent; color: inherit; font: inherit; }
      #ytmd-menu input.invalid { border-color: #f44; outline-color: #f44; }
      #ytmd-menu div.custom button:disabled { opacity: .4; cursor: default; }
      #ytmd-menu div.custom button { margin-left: auto; padding: 4px 10px; border: 0; border-radius: 4px;
        background: rgba(255, 255, 255, .15); color: inherit; font: inherit; cursor: pointer; }`;
    document.head.appendChild(style);
  }

  function closeMenu() {
    document.getElementById('ytmd-menu')?.remove();
  }

  // A "custom minutes" field; clicks inside it must not close the menu.
  function customRow() {
    const row = document.createElement('div');
    row.className = 'custom';
    const input = document.createElement('input');
    input.type = 'number';
    input.min = '0.1';
    input.step = 'any';
    input.max = String(MAX_CUSTOM_MINUTES);
    input.placeholder = MIN_CUSTOM_MINUTES + '–' + MAX_CUSTOM_MINUTES;
    const unit = document.createElement('span');
    unit.textContent = 'min';
    const start = document.createElement('button');
    start.textContent = 'Start';
    start.disabled = true;
    // The typed text as a number, or null unless it is a plain number within range.
    const parsed = () => {
      if (!/^(\d+\.?\d*|\.\d+)$/.test(input.value.trim())) return null;
      const minutes = Number(input.value);
      return minutes >= MIN_CUSTOM_MINUTES && minutes <= MAX_CUSTOM_MINUTES ? minutes : null;
    };
    const validate = () => {
      const valid = parsed() !== null;
      start.disabled = !valid;
      // A number field reports half-typed text such as "1.2.3" as an empty value plus badInput.
      const invalid = !valid && (input.value !== '' || input.validity.badInput);
      input.classList.toggle('invalid', invalid);
      input.title = !invalid
        ? '' : 'Enter ' + MIN_CUSTOM_MINUTES + ' to ' + MAX_CUSTOM_MINUTES + ' minutes';
    };
    const submit = () => {
      const minutes = parsed();
      if (minutes === null) return input.select();
      sendCommand('sleep:' + minutes);
      closeMenu();
    };
    start.addEventListener('click', submit);
    input.addEventListener('input', validate);
    input.addEventListener('keydown', e => {
      e.stopPropagation(); // Keep YT Music's keyboard shortcuts out of the field.
      if (e.key === 'Enter') submit();
      // A number field would also take exponents and signs, which are never valid here.
      if (/^[eE+-]$/.test(e.key)) e.preventDefault();
    });
    row.addEventListener('click', e => e.stopPropagation());
    row.append(input, unit, start);
    return row;
  }

  function openSleepMenu(anchor) {
    if (document.getElementById('ytmd-menu')) return closeMenu();
    const menu = document.createElement('div');
    menu.id = 'ytmd-menu';
    for (const [command, label] of SLEEP_CHOICES) {
      const item = document.createElement('div');
      item.textContent = label;
      item.addEventListener('click', () => {
        sendCommand('sleep:' + command);
        closeMenu();
      });
      menu.appendChild(item);
    }
    menu.appendChild(customRow());
    const cancel = document.createElement('div');
    cancel.textContent = 'Cancel timer';
    if (!sleepStatus) cancel.className = 'disabled';
    cancel.addEventListener('click', () => {
      sendCommand('sleep:cancel');
      closeMenu();
    });
    menu.appendChild(cancel);
    document.body.appendChild(menu);
    const r = anchor.getBoundingClientRect();
    menu.style.left = Math.max(8, Math.min(r.left, window.innerWidth - menu.offsetWidth - 8)) + 'px';
    menu.style.bottom = (window.innerHeight - r.top + 8) + 'px';
  }

  function makeButton(iconPath, title, onClick, withStatus) {
    const button = document.createElement('button');
    button.title = title;
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('width', '24');
    svg.setAttribute('height', '24');
    svg.setAttribute('fill', 'currentColor');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', iconPath);
    svg.appendChild(path);
    button.appendChild(svg);
    if (withStatus) {
      const status = document.createElement('span');
      status.className = 'status';
      button.appendChild(status);
    }
    button.addEventListener('click', e => {
      e.stopPropagation();
      onClick(button);
    });
    return button;
  }

  function showSleepStatus() {
    const button = document.querySelector('#ytmd-sleep');
    if (!button) return;
    button.classList.toggle('active', !!sleepStatus);
    button.title = sleepStatus ? 'Sleep timer: ' + sleepStatus : 'Sleep timer';
    button.querySelector('.status').textContent = sleepStatus;
  }

  // Re-run by the poll, because the player bar can be re-rendered (and the buttons lost).
  function ensureControls() {
    if (document.getElementById('ytmd-controls')) return;
    const parent = document.querySelector(CONTROLS_PARENT);
    if (!parent) return;
    if (!document.getElementById('ytmd-style')) addStyles();
    const box = document.createElement('div');
    box.id = 'ytmd-controls';
    box.appendChild(makeButton(ICON_MINI, 'Mini player', () => sendCommand('miniplayer')));
    const sleep = makeButton(ICON_MOON, 'Sleep timer', openSleepMenu, true);
    sleep.id = 'ytmd-sleep';
    box.appendChild(sleep);
    parent.appendChild(box);
    showSleepStatus();
  }

  document.addEventListener('click', closeMenu);
  document.addEventListener('keydown', e => e.key === 'Escape' && closeMenu());

  // --- Navigation and zoom shortcuts the embedded browser lacks (Ctrl+wheel zoom it has) -------
  const ZOOM_KEYS = { '=': 'in', '+': 'in', '-': 'out', '0': 'reset' };
  window.addEventListener('keydown', e => {
    if (e.altKey && !e.ctrlKey && !e.shiftKey && !e.metaKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
      e.preventDefault();
      e.stopPropagation();
      if (e.key === 'ArrowLeft') history.back();
      else history.forward();
    } else if (e.ctrlKey && !e.altKey && !e.metaKey && ZOOM_KEYS[e.key]) {
      e.preventDefault();
      e.stopPropagation();
      sendCommand('zoom:' + ZOOM_KEYS[e.key]); // Java zooms; Chromium remembers it for the site.
    }
  }, true);
  document.addEventListener('mouseup', e => {
    if (e.button === 3) history.back();
    else if (e.button === 4) history.forward();
  });

  window.__ytmd = {
    playPause: () => click('playPause'),
    next: () => click('next'),
    previous: () => click('previous'),
    pause,
    play,
    setVolume,
    /** Changes the volume by delta (percentage points); see setVolume. */
    changeVolume: delta => setVolume(Math.max(0, volume()) + delta),
    /** Clicks YT Music's like (or dislike) button, which also takes the rating back if it's set. */
    like: () => rate('like'),
    dislike: () => rate('dislike'),
    pauseAtTrackEnd(on) {
      pauseAtTrackEnd = on;
      armedTrack = trackKey();
      report();
    },
    /** The sleep timer's status text from Java ("" when idle), shown on the player-bar button. */
    setSleepStatus(status) {
      sleepStatus = status;
      showSleepStatus();
    },
  };

  // Media events don't bubble, but a capturing listener on the document still sees them.
  document.addEventListener('timeupdate', checkTrackEnd, true);

  setInterval(() => {
    const found = Object.values(BUTTONS).every(selector => document.querySelector(selector));
    missingPolls = found ? 0 : missingPolls + 1;
    checkTrackEnd();
    ensureControls();
    report();
  }, 1000);
})();
