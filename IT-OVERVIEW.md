# YT Music Desktop: notes for IT

YT Music Desktop shows music.youtube.com in its own window and adds a tray icon, media keys and a
mini-player. Music plays through YouTube Music's own web player, running in an embedded Chromium
(CEF 152). It's a personal open-source project, not affiliated with Google:
<https://github.com/bauschspencer/yt-music-desktop>.

## What the installer does

- A **per-user `.msi`** (Windows Installer). It installs for the signed-in user only and never
  asks for admin rights.
- **Files:** `%LOCALAPPDATA%\YT Music\`, about 500 MB: `YT Music.exe`, `app\` (the app and
  Chromium) and `runtime\` (a private Java runtime). Nothing is installed anywhere else, and
  nothing machine-wide: no services, drivers, scheduled tasks, browser extensions or firewall rules.
- A Start menu shortcut, a desktop shortcut, and an entry in Settings > Apps > Installed apps.
- **Not code-signed yet,** so SmartScreen and Smart App Control may warn about or block it. Because
  it runs from the user profile, AppLocker's default rules block it too unless it's allowed.
- **Optional, off by default:** "Start with Windows" in the app's Settings adds the value
  `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\YTMusicDesktop`.

## Silent install, upgrade and uninstall

Run these as the user, not as SYSTEM: it's a per-user package (in Intune, set the install behavior
to **User**).

```
msiexec /i "YT.Music-0.5.1.msi" /qn                                  # install, or upgrade in place
msiexec /i "YT.Music-0.5.1.msi" /qn JP_INSTALL_DESKTOP_SHORTCUT=""   # same, without the desktop shortcut
msiexec /x "YT.Music-0.5.1.msi" /qn                                  # uninstall
```

Every version has the upgrade code `{4E0E1895-9422-49B9-A05C-4419E7CD7972}`, so a newer `.msi`
replaces the installed one. To uninstall whichever version is installed, without its `.msi`:

```powershell
$installer = New-Object -ComObject WindowsInstaller.Installer
foreach ($product in $installer.RelatedProducts('{4E0E1895-9422-49B9-A05C-4419E7CD7972}')) {
    Start-Process msiexec -ArgumentList "/x $product /qn" -Wait
}
```

Uninstalling leaves the user's data in place (next section), **including the Google sign-in
cookies**. To remove that as well, use **Settings > Sign out and delete local data** in the app
before uninstalling, or delete both folders below and the Run value above.

## Where data lives

| Folder | Contents |
|---|---|
| `%LOCALAPPDATA%\YTMusicDesktop\` | `profile\`: the Chromium profile (Google sign-in cookies, cache, site data; typically 100 to 200 MB). Also `app.log`, `app.log.1`, `instance.lock`, `instance.port` |
| `%APPDATA%\YTMusicDesktop\` (roaming) | `settings.json`, `window.properties`, `miniplayer.properties`, a few KB in all |

Only the small settings files are in the roaming profile. The browser profile stays on the PC, so
roaming profiles and folder redirection never copy the sign-in cookies or cache to a server.
(Versions before 0.5.1 kept everything in roaming; 0.5.1 moves it the first time it starts.)

Like Chrome, Chromium's component updater downloads data from Google into the profile, such as
certificate revocation lists, and also Google's Widevine DRM module
(`profile\WidevineCdm\...\widevinecdm.dll`, signed by Google). Music plays without it, so blocking
it with AppLocker DLL rules does no harm.

**Settings > Sign out and delete local data** signs out of Google (which ends the session on
Google's side too), deletes both folders and the Run value, and quits.

## Network

Outbound only; nothing listens on the network. A loopback-only socket (127.0.0.1, random port)
lets a second launch bring the running window to the front.

- **The app itself:** `api.github.com`, once per start, to check for a newer release. It never
  downloads updates: updating means installing a newer `.msi`.
- **YouTube Music, as in any browser:** `music.youtube.com`, `www.youtube.com`,
  `*.googlevideo.com` (audio), `*.ytimg.com`, `*.ggpht.com` and `*.googleusercontent.com` (artwork),
  `accounts.google.com` and Google's other sign-in hosts, `fonts.googleapis.com`, `*.gstatic.com`,
  `www.google.com`, `jnn-pa.googleapis.com`, and Google's ad and measurement hosts
  (`*.doubleclick.net`, `www.googletagmanager.com`).
- **Chromium's own requests, as Chrome makes them:** `update.googleapis.com` (the component
  updates above), `clients2.google.com` (network time, over HTTP with signed responses) and
  `content-autofill.googleapis.com` (form autofill).
- **Everything else** (any link to another site) opens in the user's default browser.

Chromium uses the Windows proxy settings (including WPAD and PAC files) and the Windows certificate
store, so a corporate proxy or TLS inspection works as it does for Chrome and Edge. The host list
comes from Chromium's network log of a fresh, signed-out session (October 2026), plus the audio
host; YouTube may add hosts over time.

## Verifying a download

From 0.5.1, each [GitHub release](https://github.com/bauschspencer/yt-music-desktop/releases)
has a build-provenance attestation for its `.msi`, which shows the file was built from this
repository's source by its GitHub Actions release workflow. With the GitHub CLI:

```
gh attestation verify "YT.Music-0.5.1.msi" --repo bauschspencer/yt-music-desktop
```

For a quick check that a copy is intact, or for a hash-based allow rule, GitHub lists each file's
SHA-256 on the release page; compare it with `(Get-FileHash "YT.Music-0.5.1.msi").Hash`. That only
shows the copy matches what's on the page, though; the attestation is what shows who built it. The
build also checks the PGP signature of every library it bundles, Chromium included, against each
publisher's pinned key.

## Options

- **Per-machine install:** a version that installs to Program Files can be built if you prefer.
  It needs admin rights to install and upgrade, can be deployed as a system install from Intune or
  SCCM, and is allowed by AppLocker's default rules. It would have its own upgrade code, so it
  can't upgrade a per-user install: remove the per-user copy first.
- **Updates:** the app doesn't update itself, and Chromium's security fixes only arrive in new
  releases. Keeping it current means deploying each new `.msi`.
