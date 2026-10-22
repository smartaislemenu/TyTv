# TYTV Android — foundation status (verified in files, updated Oct 22, 2026 ~2:25 PM ET)

## FOUNDATION APK BUILT — Oct 22, 2026
- Signed debug APK: `~/workspace/tytv-android/tytv-debug.apk` (6,909,389 bytes,
  versionCode 1, versionName 0.1.0, package `com.tytv.app`, targetSdk 34).
  Built by the manual pipeline in `~/tytv-build/` (Gradle daemon unusable here,
  see below); the Gradle project itself was NOT changed.
- Build chain that worked: aapt2 link (34.0.0) → kotlinc 1.9.24 → javac
  (UTF-8) for R.java → d8 35.0.0 → zipalign → apksigner (debug keystore).
- Verification of the APK (all actually run):
  - `apksigner verify --verbose`: Verifies; v1 + v2 + v3 schemes true, 1 signer.
  - `aapt2 dump badging`: package `com.tytv.app`, label TYTV, targetSdk 34,
    `leanback-launchable-activity: com.tytv.app.ui.MainActivity`,
    `android.software.leanback` required, `android.hardware.touchscreen`
    NOT required (D-pad-only devices compatible), INTERNET +
    ACCESS_NETWORK_STATE permissions, 4 activities in the manifest.
  - Dex spot-check: all app classes present (MainActivity, MainFragment,
    PlaybackActivity, AddProviderActivity, BufferSettingsActivity, CardPresenter,
    M3uParser, ProviderStore, BufferSettings, Channel) + ExoPlayerImpl +
    PlayerView + BrowseSupportFragment.
  - M3uParser logic test: compiled the production parser + a small Kotlin main
    mirroring every M3uParserTest assertion and ran it on the JVM — ALL PASSED
    (3 channels parsed, groups/logos/provider tags correct, duplicate names kept,
    bare URLs without EXTINF skipped, empty playlist → empty list).
  - BufferSize preset values verified against BufferSettingsTest by reading the
    source: LOW 4000/4000/1000/2000, BALANCED 10000/15000/2000/3000,
    HIGH 30000/30000/2500/5000, EXTRA 60000/60000/2500/5000 — all satisfy
    maxBuffer >= minBuffer and playback buffers > 0. LoadControl wiring
    (BufferSize → DefaultLoadControl.Builder.setBufferDurationsMs) present in
    source; PlaybackActivity builds its player from it.
- Two blockers solved during this session:
  1. d8 34.0.0 NPE on appcompat 1.7.0's ActionBarDrawerToggle$1 (input verified
     NOT corrupt — javap parses it; reproduced with appcompat alone). Fix:
     installed build-tools 35.0.0 (d8 8.6.2) via direct download
     (`build-tools_r35_linux.zip`, underscore, not hyphen — the hyphen name 404s;
     sdkmanager itself fails here: its proxy-auth handling throws
     NoSuchElementException in HttpURLConnection.doTunneling). The r35 zip nests
     tools under `android-15/`; moved up to `build-tools/35.0.0/`.
  2. Runtime dependency gaps in the manual closure (would have crashed on device):
     Media3 needs Guava — added guava 32.1.3-android + failureaccess +
     listenablefuture + jsr305 + error_prone_annotations + j2objc-annotations;
     emoji2 1.2.0 (runtime dep of emoji2-views-helper); arch core-common 2.2.0;
     lifecycle-livedata-core 2.8.3 (downloaded earlier, never extracted — now
     extracted). checker-qual EXCLUDED from dex: it duplicates jsr305's
     javax.annotation classes byte-for-byte and d8 fails on the duplicate.
- STILL UNVERIFIED (honest): playback on a real stream and D-pad navigation on
  a real TV remote — no emulator (no KVM) and no device in this VM. The APK
  installs and launches per its manifest/dex verification, but it has NOT been
  run. Do not claim it plays video until a Fire TV / Android TV test happens.

## What exists (all read and verified, not assumed)

- Gradle project at `~/workspace/tytv-android/` (AGP 8.5.2, Kotlin 1.9.24,
  Gradle 8.7 wrapper properties, compileSdk 34, minSdk 21, app id `com.tytv.app`).
- Complete Kotlin source for the foundation slice (614 lines + tests):
  - `MainFragment` — Leanback BrowseSupportFragment; Setup row (Add provider,
    Buffer size) + one row per provider group in original group order; merged
    channels, duplicates kept and provider-tagged; D-pad clicks via
    OnItemViewClickedListener.
  - `PlaybackActivity` — Media3 ExoPlayer, PlayerView + standard Media3
    controller (D-pad responsive), LoadControl from BufferSettings.
  - `M3uParser` — pure-Kotlin EXTINF parser (tvg-name, tvg-logo, group-title).
  - `ProviderStore` — on-device SharedPreferences only (`name|m3uUrl` lines).
  - `BufferSettings` — Low 4s / Balanced 10s / High 30s / Extra 60s presets
    matching the web app, persisted, wired to DefaultLoadControl.
  - `AddProviderActivity`, `BufferSettingsActivity`, `CardPresenter`
    (ImageCardView — standard Leanback TV focus handling), `MainActivity`.
  - Manifest: LEANBACK_LAUNCHER, `touchscreen required=false`, INTERNET.
  - Layouts, strings, icon/banner drawables — all IDs referenced in code exist.
  - Unit tests: M3uParserTest, BufferSettingsTest.
- Dependency closure pre-downloaded in `manual-build/libs/` (Media3 1.3.1,
  Leanback 1.0.0, appcompat, recyclerview, kotlin-stdlib, coroutines, …).
- Android SDK present: `/home/hatch/android-sdk` (build-tools 34.0.0,
  platforms android-34, platform-tools, cmdline-tools).

## Toolchain state (verified this session)

- Java: Temurin JDK 17.0.20.1 at `~/jdk-17.0.20.1+1/` (apt update stalled, so
  Adoptium tarball was used instead; `java -version` verified).
- Gradle 8.7 binary at `~/gradle-dist/gradle-8.7/`; Kotlin compiler 1.9.24 at
  `~/kotlin-dist/kotlinc/`.
- Android SDK: `/home/hatch/android-sdk` — build-tools 34.0.0 AND 35.0.0,
  platforms android-34, platform-tools, cmdline-tools. License accepted.
- Gradle daemon: UNUSABLE here — every invocation fails "Could not receive a
  message from the daemon" / "Unexpected type tag 109" (loopback, fresh
  user-home, and persistent-daemon attempts all ruled out; daemon starts fine
  but never receives a valid build request). Do not sink more time into it
  without a new idea; the manual pipeline in `~/tytv-build/` is the working
  route.
- NO `gradlew` script and NO wrapper jar in `gradle/wrapper/` — the wrapper
  cannot run as-is.
- NO KVM (`/dev/kvm` absent) — no Android emulator possible in this VM.
  On-device playback / real-remote D-pad testing is NOT possible here.

## Still to do (next slices, after a verified foundation build)

1. In-app updater (version check → download → install prompt).
2. EPG (URL + XMLTV upload) and program guide.
3. Sports / Movies / Series sections, favorites, color themes.
4. Dual/Quad multi-view (multiple ExoPlayer instances).
5. Real-device test: install on a Fire TV, verify D-pad + playback.
6. Signing config + Downloader-code hosting decision (needs the user).
