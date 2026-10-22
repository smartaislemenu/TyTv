# TYTV Android — foundation status (verified in files, updated Oct 22, 2026 ~12:20 PM ET)

## UPDATER SLICE — Oct 22, 2026 (built, NOT device-tested, NOT pushed)
- What it does: silent version check on app launch (UI only when an
  update is available) + manual "Check for updates" card on the Setup row
  (always reports: available / up to date / couldn't check). Update flow:
  D-pad dialog (Update/Later, back dismisses) → one-time system
  "install unknown apps" gate → download to app-private storage →
  system install prompt (the user's confirmation; never silent install).
- New files: `app/src/main/java/com/tytv/app/update/` — VersionInfo.kt
  (pure-Kotlin descriptor parse + version compare, JVM-testable),
  UpdateChecker.kt (raw.githubusercontent fetch off the main thread,
  never throws), UpdateInstaller.kt (HttpURLConnection download +
  FileProvider install intent), UpdateDialog.kt (framework AlertDialog —
  NOT AppCompat, which needs an AppCompat theme this Leanback app lacks),
  UpdateFlow.kt (shared orchestration). Plus `version.json` (project root:
  versionCode 1, versionName 0.1.0, apkUrl =
  https://github.com/smartaislemenu/TyTv/releases/latest/download/tytv.apk
  — a placeholder until the first real release; unreachable URL = failed
  check, never a loop or crash), `app/src/main/res/xml/file_paths.xml`,
  `app/src/test/java/com/tytv/app/UpdateCheckerTest.kt`.
- Changed: AndroidManifest.xml (+REQUEST_INSTALL_PACKAGES, FileProvider
  `com.tytv.app.fileprovider`), MainActivity (silent launch check),
  MainFragment + CardPresenter ("Check for updates" card, current version
  in its description), strings.xml (13 update strings), README.md
  (Updates section + signing-key rule; Firestick refs → Onn box).
- Signing-key rule (also in README): an update installs ONLY when signed
  with the SAME key as the installed app. Current builds use the debug
  key; a permanent release key is a later decision and must never change
  after that, or updates fail and require uninstall/reinstall.
- Verified (all actually run, Oct 22): kotlinc clean (one lambda bug
  found and fixed during compile; one always-true check simplified —
  final compile zero errors, zero warnings); JVM mirror of every
  UpdateCheckerTest assertion — 16/16 PASSED; aapt2 link regenerated
  R.java with the new strings + file_paths; FileProvider confirmed in
  the linked manifest; apksigner verify passes (v1+v2+v3, 1 signer);
  badging: package com.tytv.app, versionCode 1, versionName 0.1.0,
  targetSdk 34, leanback-launchable MainActivity, touchscreen NOT
  required, permissions exactly INTERNET + ACCESS_NETWORK_STATE +
  REQUEST_INSTALL_PACKAGES; dex spot-check: all 10 update classes
  present; version.json is NOT inside the APK (correct — it is fetched
  from GitHub at runtime); file audit: all new/changed files text, no
  secrets, no credentials, no absolute local paths, local.properties
  untouched.
- Built APK (local only, NOT pushed, NOT the project APK):
  `~/tytv-build/apk/tytv-updater.apk` (6,925,037 bytes). The project
  `~/workspace/tytv-android/tytv-debug.apk` (foundation build) was NOT
  overwritten.
- Manual-pipeline lessons (do not repeat the detours):
  1. aapt2 link MUST pass `--min-sdk-version 21 --target-sdk-version 34`
     or it adds legacy implied permissions (READ/WRITE_EXTERNAL_STORAGE,
     READ_PHONE_STATE) and leaves targetSdk unset; with the flags the
     permission set matches the foundation APK exactly.
  2. Exclude `media-1.7.0.zip` from the link inputs: its values.xml
     conflicts with appcompat's (primary/secondary_text_default_material_dark,
     cancel_button_image_alpha) and fails the merge.
  3. d8 35.0.0 rejects a loose directory of .class files ("Unsupported
     source file type") — jar the staged classes first, strip all
     non-.class files (kotlin_builtins, .knm, .bin, META-INF), and
     exclude checker-qual (duplicates jsr305) and library R classes.
  4. d8 emits multi-dex (classes.dex + classes2.dex); add BOTH at the
     APK root (zip without directory prefixes).
  5. apksigner/zipalign need java on PATH (Temurin JDK 17 bin dir).
- STILL UNVERIFIED (honest, same as foundation): nothing here has run on
  a device. The updater dialog, download, and install prompt are
  code-reviewed and compile-verified only; the Onn-box test is what
  proves them. The version.json URL currently 404s (nothing pushed),
  so until the first release the updater reports "couldn't check" on
  manual checks and stays silent on launch — by design.

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
  run. Do not claim it plays video until an Onn box (Android TV /
  Google TV) test happens.

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

1. In-app updater — DONE Oct 22 (see Updater slice section above; built
   and verified in files, NOT device-tested, NOT pushed).
2. EPG (URL + XMLTV upload) and program guide.
3. Sports / Movies / Series sections, favorites, color themes.
4. Dual/Quad multi-view (multiple ExoPlayer instances).
5. Real-device test: install on an Onn box (Android TV / Google TV),
   verify D-pad + playback.
6. Signing config + Downloader-code hosting decision (needs the user).
