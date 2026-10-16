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
| Packaging   | `jpackage` app-image (optional)            | A double-clickable exe without an installer |

## Architecture

```
┌──────────────── Java (Swing) ────────────────┐
│  MainWindow ── CefBrowser (music.youtube.com) │
│      │               ▲  executeJavaScript()   │
│      │               │  (commands: play/next) │
│      │               ▼  cefQuery()            │
│      │         Injected JS bridge             │
│      │         (reads mediaSession metadata,  │
│      │          playback state, sends events) │
│      ▼                                        │
│  PlayerState ──► TrayIcon / Notifications /   │
│                  Discord RPC / Hotkeys        │
└───────────────────────────────────────────────┘
```

- **Java → page:** `browser.executeJavaScript(...)` to click player controls.
- **Page → Java:** an injected script posts track/state changes through
  `CefMessageRouter` (`window.cefQuery`).
- **Persistent login:** set a CEF `cache_path` under `%APPDATA%` so cookies survive restarts.

## Milestones

### v0.1 — MVP
- [x] Maven project; JCEF window loads YT Music
- [x] Persistent session (stay logged in)
- [x] Window size/position remembered
- [x] Single-instance lock (second launch focuses the existing window)
- [x] System tray: play/pause, next, previous, show/hide, quit
- [x] Close button minimizes to tray

### v0.2 — Desktop integration
- [ ] JS bridge: current track, artist, art, play state → Java
- [x] Media keys (Play/Pause/Next/Prev) work while unfocused (built into Chromium)
- [ ] Track-change notifications
- [ ] Global hotkeys (configurable)
- [ ] Discord Rich Presence

### v0.3 — Nice to have
- [ ] Mini-player / always-on-top mode
- [ ] User CSS/JS injection (themes, hiding UI elements)
- [ ] Settings dialog + JSON config file
- [ ] Last.fm scrobbling
- [ ] `jpackage` app-image

## Out of scope
- Downloading / offline audio
- Custom playback engine or native (non-web) UI
- macOS / Linux
- Installer, code signing, auto-update
- Mobile

## Known risks
1. ✅ **Google sign-in in embedded Chromium.** Works with a stock Chrome user-agent
   (verified 2026-10-15).
2. ✅ **Media keys / Windows media overlay.** Work out of the box with
   `--enable-features=HardwareMediaKeyHandling`, so no global key hook is needed for
   media keys.
3. ✅ **Widevine/DRM.** Playback works without Widevine.
4. **JDK 26 native access.** JCEF loads native libraries; run with
   `--enable-native-access=ALL-UNNAMED` to avoid restricted-method warnings/errors.
5. **Fragile DOM selectors.** Controlling the player by clicking elements breaks when
   YouTube changes its markup. Prefer `navigator.mediaSession` and the `<video>`
   element over CSS selectors where possible.

## Candidate libraries
- `me.friwi:jcefmaven` — JCEF + auto-downloaded Chromium natives
- `com.github.kwhat:jnativehook` — global keyboard hooks (media keys / hotkeys)
- Discord IPC client (e.g. `com.github.jagrosh:DiscordIPC`) — Rich Presence
- `com.google.code.gson:gson` or Jackson — config file
