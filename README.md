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

## Status

Honest state: the foundation build is structurally verified (signed APK,
leanback-launchable, no touchscreen required, parser unit tests pass), but
playback on a real stream and navigation with a real remote are UNVERIFIED
until tested on a Firestick / Android TV device.

Still to come: in-app updater, EPG/guide, Sports/Movies/Series sections,
favorites, color themes, multi-view, release signing, and a Downloader code
for easy family installs.

## Build

Standard Gradle project (AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34).
`manual-build/resolve_deps.py` documents the manual dependency pipeline used
when the Gradle daemon is unavailable.
