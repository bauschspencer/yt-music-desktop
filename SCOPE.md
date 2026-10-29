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
│  SleepTimer, LockWatcher                               │
└────────────────────────────────────────────────────────┘
```

- **Java → page:** `PlayerControls` runs `executeJavaScript("window.__ytmd.…()")`. That small
  API is defined by `bridge.js`, which clicks the player-bar buttons and moves the volume
  slider, so every DOM selector lives in `bridge.js` (and its health check tests them).
- **Page → Java:** `bridge.js` polls once a second and posts changes through
  `CefMessageRouter` (`window.cefQuery`); `MediaBridge` parses them into `PlaybackState`.
- **Browser handlers:** a `CefClient` keeps only one handler of each kind, so `BrowserHandlers`
  holds them all: it runs `bridge.js` on each YT Music page load, retries failed loads, reloads
  after a renderer crash, and keeps the window on YouTube and Google sign-in pages (everything
  else opens in the default browser). Its life-span handler has to pass `doClose` on to the
  browser, or quitting breaks.
- **URL policy:** `UrlPolicy` holds every decision about which URLs the app trusts (which page
  gets `bridge.js` and may message Java, which pages stay in the window, where album art may
  come from). Hosts are compared whole, never by prefix.
- **Chromium natives:** bundled into the app image at build time (`BundleNatives.java`), so the
  app never downloads or unpacks Chromium itself. (Chromium's component updater still fetches
  Google-signed components into the profile, Widevine's DLL among them, as Chrome does.)
- **Data folders:** `DataFolders`. The browser profile (CEF `cache_path`, which keeps the login),
  the log and the single-instance lock are in `%LOCALAPPDATA%\YTMusicDesktop`; Roaming
  (`%APPDATA%\YTMusicDesktop`) only holds the settings files, since roaming profiles and folder
  redirection copy it to a server and onto every PC.
- **Single instance:** file lock + loopback socket (`SingleInstance`), checked before CEF starts.
- **Windows APIs:** `NativeWindow` (the main window's handle, dark title bar, and one
  window-procedure subclass that hands messages to whoever registered for them), `TaskbarButtons`
  (COM `ITaskbarList3`: thumbnail buttons and the progress bar), `LockWatcher` (session
  lock/unlock) and `Autostart` (registry) call Win32 through the Foreign Function & Memory API
  (`Win32` helper), so there's no JNA dependency.

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
- [x] ~~User CSS/JS injection from `%APPDATA%\YTMusicDesktop\custom`~~ (removed in v0.5.1: no
  longer wanted, and it ran any file in that folder inside the signed-in session)
- [x] Settings dialog + `settings.json` (close-to-tray vs quit)
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

### v0.5.1 — Robustness and quality of life
Like v0.5, nothing here pops up a notification: state is shown passively (status lines,
taskbar, tooltips).

Fixes for things that looked broken:
- [x] **Recover from offline starts and renderer crashes:** a failed main-frame load is retried
  after 2, 4, 8, 16, 30, then every 60 s; a renderer crash reloads the page after 1 s (at most 3
  times in 5 minutes). Meanwhile the window title, tray tooltip and mini-player show
  "Offline, retrying" (or "Page crashed" once it stops reloading). Chromium's error page still
  fires `onLoadEnd` with the failed URL, so injection skips it (`onLoadError` comes first)
- [x] **Open outside links in the default browser:** popups (`target=_blank`) open in the default
  browser, except `accounts.google.com`. The main window only shows `*.youtube.com` and the
  Google hosts sign-in, consent and sign-out use (`accounts`, `consent`, `gds`, `myaccount`,
  `www.google.com`); any other web page opens in the default browser, whether a click, a
  redirect or a script would have taken the window there. If sign-in ever bounces to the
  browser, `app.log` says which host to add to `UrlPolicy`
- [x] **Dark title bar, no white flash:** `DwmSetWindowAttribute` (dark mode plus caption color
  `#030303`), a dark `cefSettings.background_color`, and dark frame/browser backgrounds

Features:
- [x] **Pause on lock:** Settings > Playback: "Pause when the PC locks" and "Resume when unlocked"
  (resumes only if the lock paused it). `WTSRegisterSessionNotification` + `WM_WTSSESSION_CHANGE`
- [x] **Track progress:** `bridge.js` reports `currentTime` (whole seconds); a taskbar progress
  bar (paused state while paused, none during ads) and a thin strip plus "1:23 / 3:45" in the
  mini-player
- [x] **Back/forward and zoom:** Alt+Left/Right and the mouse's back/forward buttons; Ctrl+= / - /
  0 zoom in Chrome's steps. CEF never calls a `CefKeyboardHandler` in this setup, so `bridge.js`
  catches the keys (back/forward via `history`, zoom via a `zoom:` command to Java's
  `setZoomLevel`), which means they only work on YT Music pages. Ctrl+wheel zoom was already
  built in, and Chromium remembers the zoom per site in the profile, so there's no
  `settings.json` entry for it
- [x] **Tray clicks:** a click shows/hides the window (a double click toggles once), a middle
  click plays/pauses; the current track heads the menu as a disabled item
- [x] **Sleep timer fade-out:** the volume falls over the last 30 s (or the whole timer if it's
  shorter), then playback pauses and the volume is put back. Works for "end of track" too
- [x] **Like/dislike** in the mini-player, tray (checkbox items) and taskbar preview (five
  buttons: dislike, previous, play/pause, next, like; Windows only allows adding the set once).
  `bridge.js` reports `likeStatus` (`LIKE`/`DISLIKE`/`INDIFFERENT`, or "" if the buttons aren't
  found; a boolean couldn't say "disliked"), kept out of the `BUTTONS` health check
- [x] **Copy track link:** "Copy link" in the mini-player and tray menus, confirmed on the
  mini-player status line
- [x] **About section in Settings:** version, last update-check result, "Open data folder", and
  "Open release page" when there's a newer release
- [x] **Mini-player options:** snaps to screen edges (within 16 px) while dragging; right-click
  toggles for "Always on top" and "Show when the main window is hidden to the tray" (it closes
  again when the window comes back, and a `--minimized` start counts as hiding)
Security review fixes (2026-10-28):
- [x] **Bridge messages only from YT Music:** CEF gives `window.cefQuery` to every frame of every
  page, so `MediaBridge` ignores messages unless they come from the main frame on exactly
  `music.youtube.com`
- [x] **Album art only from YouTube's image hosts:** HTTPS on `*.googleusercontent.com`,
  `*.ytimg.com` or `*.ggpht.com`. Any other URL would have been fetched by Java, and a
  `file://host/...` one makes Windows send that host the user's NTLM login hash
- [x] **No HTML in mini-player labels:** `html.disable` on the text labels, since Swing renders
  (and loads images for) any text starting with `<html>`
- [x] **Exact host checks:** `music.youtube.com.example.net` used to pass a `startsWith` check
- [x] **Update check opens a fixed page:** `releases/latest` on GitHub instead of the response's
  `html_url`, and the tag must look like `v1.2.3`
- [x] **Remote debugging in dev runs only:** Chromium reads its switches (`--remote-debugging-port`,
  `--proxy-server`, ...) straight from the process command line, so the packaged exe refuses to
  start with any argument but `--minimized`, and logs why
- [x] **Chromium ships in the installer:** see "Chromium natives" above; the app never downloads
  it, so no unverified Chromium lands in (or runs from) the user's profile
- [x] **Release workflow:** actions pinned to commit SHAs (Dependabot updates them), the tag name
  passed through `env:`, a read-only build job, and a separate release job that publishes the
  `.msi` with a build-provenance attestation (`gh release` instead of a
  third-party action)
- [x] **Custom CSS/JS removed** (see v0.3)

For running on a work laptop (2026-10-28):
- [x] **Browser profile in Local AppData:** the profile (180 MB here, sign-in cookies included)
  was in Roaming, which roaming profiles and folder redirection copy to a file server. Now only
  the settings files stay there (see "Data folders" above). The first start moves the old profile,
  as a rename, or as copy-then-delete if Roaming is on another drive (untested: this PC has one
  drive), and keeps the user signed in. It also deletes what older versions left in Roaming:
  Chromium (370 MB, downloaded there before v0.5.1), the log and the lock. If an older version is
  still running from Roaming, it's brought to the front instead and nothing moves
- [x] **PGP signatures checked:** `pgpverify-maven-plugin` (validate phase, so every build) checks
  every dependency, the Chromium jar included, against the keys pinned in
  `src/main/packaging/pgp-keys.list`; Central's SHA-1 checksums come from the same server as the
  files. An unlisted dependency or a different key fails the build. jcefmaven changed its key on
  2026-05-05 (146.0.10); the list says how that was confirmed
- [x] **No attaching to the JVM:** `-XX:+DisableAttachMechanism` (dev runs too), so `jcmd` or a
  late-loaded `-javaagent` can't read the session out of memory. Like the debug-port fix, it's
  against something already running as the user, which could still set `JAVA_TOOL_OPTIONS`
- [x] **"Sign out and delete local data"** in Settings: opens Google's sign-out page (ending the
  session on Google's side), quits without saving, then deletes both data folders and the Run
  value. Chromium keeps a few profile files open until its process exits (even after shutdown), so
  a second copy of the app (`--delete-data-after=<pid>`, which never starts Chromium) waits for the
  exit and does the deleting; it only shows anything if something couldn't be deleted. Deletion
  carries on past locked files, so one can't keep the cookies, and unlinks junctions rather than
  following them. Chromium's `chrome_debug.log` is off now, since it stayed open the same way
- [x] **IT one-pager:** [IT-OVERVIEW.md](IT-OVERVIEW.md) (what's installed where, network hosts,
  data, silent install/uninstall, verifying a download). The hosts came from Chromium's
  `--log-net-log` in a dev run with a fresh profile; re-measure if the app starts talking to
  something new
- [x] **Release asset name:** GitHub turns `YT Music-x.y.z.msi` into `YT.Music-x.y.z.msi`, so the
  release job renames it first and the attestation names the file people download
- [x] **No `SHA256SUMS.txt`** after 0.5.1: GitHub already shows each asset's SHA-256 on the release
  page (and in the API as `digest`), and a checksum served beside the file can't show who built it;
  the attestation does that

Waiting on others:
- [ ] **Per-machine `.msi`** (Program Files, for Intune/SCCM): ask IT whether they want it. It
  needs its own `--win-upgrade-uuid`, since a per-machine install can't upgrade a per-user one
- [ ] **Code signing** (Azure Trusted Signing, needs an Azure account), then **winget** so
  `winget upgrade` and IT tooling can deploy updates; an in-app updater only after signing, since
  it would have to check the download's Authenticode signer

- [ ] ~~**Resume where you left off**~~ — tried and dropped. Cold-loading `watch?v=…&t=…` does
  start at the saved position, but YT Music then plays at once rather than paused (Chromium
  allows autoplay once a profile has listening history), so it would start music by itself at
  sign-in. The queue also becomes a new radio mix unless the URL's `list=` is kept

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
- **Verifying a release:** `gh attestation verify "YT.Music-<version>.msi" --repo
  bauschspencer/yt-music-desktop` confirms the `.msi` was built by this repo's workflow.
- **Updating Chromium:** JCEF has Chromium's sandbox switched off (hard-coded upstream), and its
  Chromium only gets security fixes when jcefmaven ships them and this app is rebuilt, so update
  promptly after Chrome security releases (watch the
  [jcefmaven releases](https://github.com/jcefmaven/jcefmaven/releases)):
  1. Set `jcefmaven.version` in `pom.xml` to the new release
  2. Set `jcef.version` to the `jcef-api` version that release's pom depends on (if it's wrong,
     `mvn package` stops and prints the right one)
  3. Bump the Chrome major in `App.USER_AGENT` to match
  4. Build. If the new release is signed with a different key, or brings a new dependency, the
     signature check stops the build and names the artifact. Before adding a key to
     `pgp-keys.list`, confirm it in jcefmaven's own repository, not just by the name on the key:
     anyone can make a key that says "Fritz Windisch"
- **Testing:** in a dev run, `--remote-debugging-port=9222` opens the Chrome DevTools Protocol so
  Playwright can drive the embedded page (`connectOverCDP`); the packaged exe won't start with it. Dev
  runs unpack Chromium from the `jcef-natives` jar on the compile classpath into
  `%LOCALAPPDATA%\YTMusicDesktop\jcef-bundle` (`mvn compile exec:exec` includes it), and never
  download it. `-Dytmusic.releasesApi=<url>` points the
  update check at a mock (for the exe, pass it in `JAVA_TOOL_OPTIONS`). Pointing both `APPDATA` and
  `LOCALAPPDATA` at scratch folders gives a separate profile, settings, lock and log, so a test copy
  can run beside the installed one (with only `APPDATA` changed, it would share the installed copy's
  profile and lock). Dev runs also start with `-XX:+DisableAttachMechanism`, so `jcmd` and
  profilers can't attach; take it out of the `exec` arguments to use them.
- **User data:** Roaming (`%APPDATA%\YTMusicDesktop`): `settings.json` (close-to-tray,
  pause-on-lock and the mini-player options), `window.properties`, `miniplayer.properties`. Local
  (`%LOCALAPPDATA%\YTMusicDesktop`): `profile/` (login, and the zoom level), `app.log` +
  `app.log.1`, `instance.lock` + `instance.port`, and `jcef-bundle/` (Chromium, dev runs only).
  "Start with Windows" is the registry value `HKCU\...\Run\YTMusicDesktop`. Each device has its own;
  uninstalling leaves it in place, and Settings > "Sign out and delete local data" removes it all
  (a dev run leaves the Run value alone, since it belongs to the installed app).

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
   - `ytmusic-player-bar ytmusic-like-button-renderer` and its `like-status` attribute; the like
     button is inside `.like-button-wrapper`, the dislike button directly under the renderer
     (not part of the health check: a missing renderer just greys out like/dislike)
   - `#movie_player.getVideoData().video_id` (track link; falls back to `?v=` in the address bar)

   Track info comes from `navigator.mediaSession` and the `<video>` element, which are
   standard APIs and more stable.

## Libraries
- `me.friwi:jcefmaven` — JCEF; its Chromium natives (`jcef-natives-windows-amd64`) are
  unpacked into the app image at build time
- `com.google.code.gson:gson` — bridge messages and `settings.json`
