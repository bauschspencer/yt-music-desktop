// Injected into music.youtube.com. Reports now-playing state to Java via window.cefQuery
// whenever it changes. Polls once a second, which is simpler and sturdier than hooking
// YT Music's internal events.
(() => {
  if (window.__ytmdBridge) return;
  window.__ytmdBridge = true;

  const PREFIX = '__PREFIX__';
  let last = '';

  setInterval(() => {
    // Ads also fill in mediaSession metadata; report them as "no track" instead.
    const ad = !!document.querySelector('#movie_player.ad-showing');
    const md = ad ? null : navigator.mediaSession.metadata;
    const video = document.querySelector('video');
    const artwork = md && md.artwork.length ? md.artwork[md.artwork.length - 1].src : '';
    const json = JSON.stringify({
      title: md ? md.title : '',
      artist: md ? md.artist : '',
      album: md ? md.album : '',
      artworkUrl: artwork,
      duration: video && isFinite(video.duration) ? video.duration : 0,
      playing: !!video && !video.paused,
    });
    if (json !== last) {
      last = json;
      window.cefQuery({ request: PREFIX + json, onSuccess() {}, onFailure() {} });
    }
  }, 1000);
})();
