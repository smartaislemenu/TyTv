# TYTV

A native Android TV IPTV player. Kotlin + Media3 ExoPlayer + Leanback-style TV UI.
No WebView shell — a real app, built for the remote.

## What it is

TYTV starts empty. There are no accounts, playlists, or credentials baked in.
Each viewer adds their own M3U providers on their own device, and everything
(providers, favorites, settings) lives only on that device.

## Foundation (v0.1.0)

- Add a provider by M3U URL (stored on-device only)
- Merged channel list, one row per provider group in the provider's original
  group order; duplicates kept and provider-tagged
- Full D-pad navigation: arrows, select, back — no mouse or touch needed
- Single-channel playback via Media3 ExoPlayer
- Playback buffer presets: Low 4s / Balanced 10s (default) / High 30s / Extra 60s
- In-app updater (see Updates below)

## Updates

TYTV checks for updates itself — no reinstall, no new code to type:

- On launch it silently checks `version.json` on the main branch and only
  speaks up when a newer version exists.
- "Check for updates" on the Setup row reports the result every time
  (available / up to date / couldn't check).
- Tapping Update downloads the APK to app-private storage and hands it to
  the system installer. The system install prompt is the confirmation step;
  nothing installs silently, and on first use TYTV must be allowed to
  install unknown apps (a one-time system toggle).

Signing rule: an update only installs when it is signed with the SAME key
as the installed app — the system installer rejects a differently-signed
APK. Current builds use the debug key; a permanent release key is a later
decision, and once chosen it must stay the same key from then on, or
updates will fail and require an uninstall/reinstall.

## Status

Honest state: the foundation build is structurally verified (signed APK,
leanback-launchable, no touchscreen required, parser unit tests pass), but
playback on a real stream and navigation with a real remote are UNVERIFIED
until tested on an Onn box (Android TV / Google TV).

Still to come: EPG/guide, Sports/Movies/Series sections,
favorites, color themes, multi-view, release signing, and a Downloader code
for easy family installs.

## Build

Standard Gradle project (AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34).
`manual-build/resolve_deps.py` documents the manual dependency pipeline used
when the Gradle daemon is unavailable.
