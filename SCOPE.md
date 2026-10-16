# YT Music Desktop — Project Scope

A personal desktop wrapper for `music.youtube.com`, written in Java. Google's own web
player handles playback; this app adds desktop integration around it.

## Decisions

| Area        | Decision                                   | Why |
|-------------|--------------------------------------------|-----|
| Approach    | Wrapper (load the real web app)            | Playback stays reliable; no reverse-engineered stream handling |
| Audience    | Personal use only                          | No code signing, installer polish, or auto-update needed |
| Language    | Java (JDK 26 locally)                      | Preference |
| Browser     | JCEF via `me.friwi:jcefmaven`              | Real Chromium; JavaFX WebView can't handle Google login / modern media |
| UI shell    | Swing (`JFrame` hosting the CEF browser)   | JCEF integrates with AWT/Swing natively |
| Build       | Maven                                      | Already installed |
| Platform    | Windows 11 only                            | Cross-platform is out of scope |
| Packaging   | `jpackage` app-image                       | A double-clickable exe without an installer |

## Architecture

```
┌──────────────────── Java (Swing) ─────────────────────┐
│  MainWindow ── CefBrowser (music.youtube.com)          │
│                   ▲                   │                │
│  PlayerControls ──┘ executeJavaScript │ cefQuery       │
│  (click player-bar buttons)           ▼                │
│                              bridge.js (injected)      │
│                              polls mediaSession/video  │
│                                       │                │
│                              MediaBridge → PlaybackState
│                                       │                │
│          Tray tooltip ◄── Window title ──► MiniPlayer  │
└────────────────────────────────────────────────────────┘
```

- **Java → page:** `PlayerControls` runs `executeJavaScript(...)` to click player-bar buttons.
- **Page → Java:** `bridge.js` polls once a second and posts changes through
  `CefMessageRouter` (`window.cefQuery`); `MediaBridge` parses them into `PlaybackState`.
- **Injection:** `App.injectOnLoad` runs `bridge.js` plus the user's custom CSS/JS on each
  YT Music page load (a `CefClient` keeps only one load handler).
- **Persistent login:** CEF `cache_path` under `%APPDATA%\YTMusicDesktop\profile`.
- **Single instance:** file lock + loopback socket (`SingleInstance`), checked before CEF starts.

## Milestones

### v0.1 — MVP
- [x] Maven project; JCEF window loads YT Music
- [x] Persistent session (stay logged in)
- [x] Window size/position remembered
- [x] Single-instance lock (second launch focuses the existing window)
- [x] System tray: play/pause, next, previous, show/hide, quit
- [x] Close button minimizes to tray

### v0.2 — Desktop integration
- [x] JS bridge: current track, artist, art, play state → Java (ads reported as no track)
- [x] Media keys (Play/Pause/Next/Prev) work while unfocused (built into Chromium)
- [x] Now playing (and paused state) in tray tooltip and window title

### v0.3 — Nice to have
- [x] Mini-player: always-on-top, art + title + controls, draggable, remembers position/open state
- [x] User CSS/JS injection from `%APPDATA%\YTMusicDesktop\custom`
- [x] Settings dialog + `settings.json` (close-to-tray vs quit, custom CSS/JS on/off)
- [x] `jpackage` app-image (`YT Music.exe` with bundled runtime)

## Build & run
- **Dev:** `mvn compile exec:exec`
- **Standalone:** `mvn package` → `target/dist/YT Music/YT Music.exe` (copy the whole
  `YT Music` folder somewhere outside `target/` before using it; `mvn clean` deletes it)
- **User data** (`%APPDATA%\YTMusicDesktop`): `profile/` (login), `jcef-bundle/` (Chromium),
  `custom/` (user CSS/JS), `settings.json`, `window.properties`, `miniplayer.properties`

## Out of scope
- Downloading / offline audio
- Custom playback engine or native (non-web) UI
- macOS / Linux
- Installer, code signing, auto-update

## Known risks
1. ✅ **Google sign-in in embedded Chromium.** Works with a stock Chrome user-agent
   (verified 2026-10-15).
2. ✅ **Media keys / Windows media overlay.** Work out of the box with
   `--enable-features=HardwareMediaKeyHandling`, so no global key hook is needed for
   media keys.
3. ✅ **Widevine/DRM.** Playback works without Widevine.
4. **JDK 26 native access.** JCEF loads native libraries; run with
   `--enable-native-access=ALL-UNNAMED` to avoid restricted-method warnings/errors.
5. **Fragile DOM selectors.** These break if YouTube changes its markup (all verified
   2026-10-15):
   - `ytmusic-player-bar #play-pause-button`, `.next-button`, `.previous-button` (tray controls)
   - `#movie_player.ad-showing` (ad detection)

   Track info comes from `navigator.mediaSession` and the `<video>` element, which are
   standard APIs and more stable.

## Libraries
- `me.friwi:jcefmaven` — JCEF + auto-downloaded Chromium natives
- `com.google.code.gson:gson` — bridge messages and `settings.json`
