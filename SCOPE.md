# YT Music Desktop — Project Scope

A personal desktop wrapper for `music.youtube.com`, written in Java. Google's own web
player handles playback; this app adds desktop integration around it.

## Decisions

| Area        | Decision                                   | Why |
|-------------|--------------------------------------------|-----|
| Approach    | Wrapper (load the real web app)            | Playback stays reliable; no reverse-engineered stream handling |
| Audience    | Personal use only                          | No code signing, installer polish, or auto-update needed |
| Language    | Java (JDK 26 locally, compiled for 25)     | Preference; 22+ is needed for the Foreign Function & Memory API |
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
│  (window.__ytmd.*)                    ▼                │
│                              bridge.js (injected)      │
│                              polls mediaSession/video, │
│                              owns the DOM selectors    │
│                                       │                │
│                              MediaBridge → PlaybackState
│                                       │                │
│  Tray, window title, MiniPlayer, TaskbarButtons,       │
│  SleepTimer                                            │
└────────────────────────────────────────────────────────┘
```

- **Java → page:** `PlayerControls` runs `executeJavaScript("window.__ytmd.…()")`. That small
  API is defined by `bridge.js`, which clicks the player-bar buttons and moves the volume
  slider, so every DOM selector lives in `bridge.js` (and its health check tests them).
- **Page → Java:** `bridge.js` polls once a second and posts changes through
  `CefMessageRouter` (`window.cefQuery`); `MediaBridge` parses them into `PlaybackState`.
- **Injection:** `App.injectOnLoad` runs `bridge.js` plus the user's custom CSS/JS on each
  YT Music page load (a `CefClient` keeps only one load handler).
- **Persistent login:** CEF `cache_path` under `%APPDATA%\YTMusicDesktop\profile`.
- **Single instance:** file lock + loopback socket (`SingleInstance`), checked before CEF starts.
- **Windows APIs:** `TaskbarButtons` (COM `ITaskbarList3`, plus a window-procedure subclass to
  receive the button clicks) and `Autostart` (registry) call Win32 through the Foreign
  Function & Memory API (`Win32` helper), so there's no JNA dependency.

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

### v0.4 — Installer (for installing on multiple personal devices)
- [x] `.msi` built by GitHub Actions and attached to a GitHub Release on each `v*` tag
- [x] Per-user install (no admin prompt), Start menu + optional desktop shortcut
- [x] New versions upgrade the existing install in place

Unsigned for now. Devices with **Smart App Control** on (Windows 11) will likely block
it; Azure Trusted Signing (~$10/month) is the fix if that becomes a problem.

### v0.5 — Everyday polish
- [x] **Start with Windows:** Settings checkbox that adds/removes a `HKCU\...\Run` entry
  (packaged exe only; a dev run has no launcher to register)
- [x] **Start minimized:** `--minimized` flag (used by the Run entry) starts in the tray
  without showing the main window; the hidden browser still loads (`createImmediately()`)
- [x] **Log file:** send stdout/stderr to `%APPDATA%\YTMusicDesktop\app.log` (keep the previous
  run as `app.log.1`), since the packaged exe has no console. Covers Java's output; Chromium's
  own native logging doesn't go through `System.out`/`err`
- [x] **Sleep timer:** tray submenu to pause in 15 / 30 / 60 min or at the end of the current
  track; shows the remaining time in the menu, and the timer can be cancelled
  Also reachable from the player bar: `bridge.js` adds a moon button (15 / 30 / 60 / custom minutes /
  end of track / cancel, with the time left shown) and a mini-player toggle next to YT Music's own
  controls. They send commands to Java (`ytmd-cmd:` messages) and are built with DOM calls, since
  YouTube's Trusted Types policy rejects `innerHTML`
- [x] **Volume control:** scroll over the mini-player to change volume (it shows "Volume N%" for
  a moment). Goes through YT Music's own volume slider so its UI and saved volume stay in
  sync: the planned `#movie_player.setVolume()` turned out to leave the slider stale (YT Music
  maps the slider onto the player volume on a curve), so that's only the fallback
- [x] **Selector health check:** `bridge.js` reports whether the player-bar buttons were found;
  if not, the tray tooltip and mini-player show "controls unavailable" and the taskbar buttons
  grey out (no pop-ups)
- [x] **Taskbar thumbnail buttons:** previous / play-pause / next in the taskbar hover preview
  (`ITaskbarList3::ThumbBarAddButtons`), called through the JDK's Foreign Function & Memory
  API so no JNA dependency is needed
- [x] **Update check:** on startup, ask the GitHub Releases API for the latest `v*` tag; if it's
  newer, add an "Update available (vX.Y)" tray menu item that opens the release page.
  The unauthenticated API only sees **public** repos: while this one is private, the check
  gets a 404 and just logs it

## Build & run
- **Dev:** `mvn compile exec:exec`
- **Standalone:** `mvn package` → `target/dist/YT Music/YT Music.exe` (copy the whole
  `YT Music` folder somewhere outside `target/` before using it; `mvn clean` deletes it)
- **Installer:** `mvn package -Pinstaller` → `target/installer/YT Music-<version>.msi`.
  Needs WiX Toolset, which Smart App Control blocks on the dev machine, so it's built by
  [.github/workflows/release.yml](.github/workflows/release.yml) instead.
- **Releasing a new version:**
  1. Bump `<version>` in `pom.xml` (must increase, or installs won't upgrade)
  2. Commit, then `git tag v<version>` and `git push --follow-tags`
  3. The workflow publishes a GitHub Release with the `.msi`
- **Testing:** `--remote-debugging-port=9222` opens the Chrome DevTools Protocol so Playwright
  can drive the embedded page (`connectOverCDP`). `-Dytmusic.releasesApi=<url>` points the
  update check at a mock (for the exe, pass it in `JAVA_TOOL_OPTIONS`). Pointing `APPDATA` at a
  scratch folder gives a separate profile, lock and log, so a test copy can run beside the
  installed one.
- **User data** (`%APPDATA%\YTMusicDesktop`): `profile/` (login), `jcef-bundle/` (Chromium),
  `custom/` (user CSS/JS), `settings.json`, `window.properties`, `miniplayer.properties`,
  `app.log` + `app.log.1`. "Start with Windows" is the registry value `HKCU\...\Run\YTMusicDesktop`.
  Each device has its own; uninstalling leaves it in place.

## Out of scope
- Downloading / offline audio
- Custom playback engine or native (non-web) UI
- macOS / Linux
- Code signing (for now), auto-update (v0.5's update check only links to the release; it
  doesn't download or install anything)

## Known risks
1. ✅ **Google sign-in in embedded Chromium.** Works with a stock Chrome user-agent
   (verified 2026-10-15).
2. ✅ **Media keys / Windows media overlay.** Work out of the box with
   `--enable-features=HardwareMediaKeyHandling`, so no global key hook is needed for
   media keys.
3. ✅ **Widevine/DRM.** Playback works without Widevine.
4. **JDK 26 native access.** JCEF loads native libraries; run with
   `--enable-native-access=ALL-UNNAMED` to avoid restricted-method warnings/errors.
5. **Fragile DOM selectors.** These break if YouTube changes its markup (all in `bridge.js`,
   verified 2026-10-15). The health check watches the buttons:
   - `ytmusic-player-bar #play-pause-button`, `.next-button`, `.previous-button` (controls)
   - `ytmusic-player-bar #volume-slider` (volume; falls back to `#movie_player.setVolume()`)
   - `#movie_player.ad-showing` (ad detection)

   Track info comes from `navigator.mediaSession` and the `<video>` element, which are
   standard APIs and more stable.

## Libraries
- `me.friwi:jcefmaven` — JCEF + auto-downloaded Chromium natives
- `com.google.code.gson:gson` — bridge messages and `settings.json`
