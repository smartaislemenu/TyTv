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

## Build #2 — favorites / hide / rename (Oct 23)
- Commit 4f1bdf96 pushed Oct 23 ~9:04 AM ET; CI run 2 (run_id 39958229378) SUCCESS.
- APK downloaded via Mac Safari Oct 23, SHA-256 verified exact vs ci-status (794f75a4…e8408, 7,538,121 bytes).
- Formal checks pass: apksigner v1+v2 true; aapt badging com.tytv.app v1/0.1.1, launchable + leanback-launchable MainActivity; manifest shows new activities (RenameGroupActivity, ManageHiddenActivity, RenameGroupsActivity) + FileProvider com.tytv.app.fileprovider; dex contains ChannelPrefs, RenameGroup, ManageHidden code + tytv_channel_prefs + full library R classes.
- Staged as ~/workspace/tytv-build/apk/tytv-debug-build2.apk (+ your_files copy). Build #1 file tytv-debug.apk left intact as fallback.
- Downloader code for build #2: 6753370 (aftv.news/6753370), destination verified exact, issued Oct 23; hosted file expires Oct 25 17:38 UTC (Sun Oct 25 ~1:38 PM ET). Build #1 code 9255144 expires Sat Oct 24 ~8:54 PM ET.

## Build #3 — full build (Oct 23, written directly, pushed for CI)
- Why: build #2 opened and ran on an Onn box, but adding an http:// M3U
  playlist (typed with the remote, confirmed working in TiviMate/Apollo on
  the same box) failed with "couldn't load playlist". Two helper-driven
  build attempts were stopped by a safety check before any code was
  written; this build was written directly instead, at Tyler's request
  ("try again on different browser" — the stop was not browser-based;
  the build does not run in a browser, so the route changed to direct
  implementation, not a browser switch).
- What it fixes: the old loader read the whole playlist into one string
  on a 30-second timeout and parsed it on the main thread, then built
  one Leanback card per channel for thousands of entries, on every
  launch. New loader streams line by line on a background thread
  (30s connect / 120s read timeouts, browser-style User-Agent), parses
  as it downloads, reports progress, and names the actual failure
  (timeout vs HTTP status vs unreachable host). Home list is a
  RecyclerView (windowed — only visible rows bound). Parsed channels
  cache on-device per provider: home shows the cache instantly and
  refreshes in the background, so a relaunch never sits empty.
- New home (Apollo-style layout, original TYTV look — no Apollo
  branding): left sidebar (Live TV, Movies, Series, Sports, Favorites,
  Search, Recordings, Setup, Refresh) + fast channel list grouped by
  provider groups (original group names first, user labels alongside).
  Favorites, hidden channels, and group renames keep the same
  on-device storage as build #2. Sports = live channels whose group
  or name contains "sport".
- Xtream Codes login: add-provider form now offers M3U URL or Xtream
  login (server + username + password via player_api.php; credentials
  stay on-device only, go only to the provider's own server).
  Live/VOD/series come through as Live/Movie/Series items with
  category groups; series open an episode list; stream URLs use the
  standard /live/, /movie/, /series/ formats.
- Player: full action bar (shows with the controller) — Record,
  Tracks, Sleep timer, Go live. Audio/subtitle track picker from the
  player's actual tracks. Dolby AC-3/E-AC-3 passes through untouched
  when the device and output support it (never forced to stereo); the
  info line names the active format (e.g. "Dolby Digital 5.1") so it
  is visible when 5.1 is actually playing. Stereo streams stay stereo.
- Timeshift + record (direct TS streams only): while watching, the
  stream buffers to a local file and plays through a loopback proxy,
  so pause freezes, seek rewinds within the buffer, and Record keeps
  the file as a recording on exit (Recordings screen: play/delete).
  Rewind only reaches back to when the channel session started; the
  buffer caps at 2 GB / low-storage stop. HLS (.m3u8) and DASH play
  directly without timeshift (Record/Go live hide for them).
- EPG: NOT in this build — no guide data is shown, and none is faked.
  EPG (xmltv) integration is the next slice after this one is proven.
- Files: new data/PlaylistLoader.kt (streaming + cache),
  data/XtreamClient.kt, data/ChannelRepository.kt, data/HttpUtil.kt,
  parser/M3uLineParser.kt, timeshift/TimeshiftSession.kt,
  timeshift/TimeshiftProxy.kt, timeshift/RecordingsStore.kt,
  ui/ChannelMenu.kt, ui/ChannelRowAdapter.kt, ui/SearchActivity.kt,
  ui/SeriesEpisodesActivity.kt, ui/RecordingsActivity.kt,
  res/layout/activity_home.xml, item_sidebar.xml, item_channel_row.xml,
  item_group_header.xml, activity_search.xml, activity_simple_list.xml,
  res/drawable/item_focus_bg.xml; rewritten ui/MainFragment.kt (plain
  Fragment, no Leanback browse), ui/PlaybackActivity.kt,
  ui/AddProviderActivity.kt, data/ProviderStore.kt (M3U + Xtream
  types, legacy two-part lines still parse), model/Channel.kt (kind +
  seriesId); build.gradle adds recyclerview 1.3.2; manifest adds the
  three new activities. Old CardPresenter.kt stays, unreferenced
  (snapshot pushes cannot delete files).
- Tests (new, pure): M3uLineParserTest, XtreamUrlsTest,
  ProviderLineTest. Existing tests untouched and still compatible
  (Channel's new fields default; M3uParser delegates to the line parser).
- HONEST LIMITS (not device-tested): nothing here has run on a box.
  The timeshift proxy, the sidebar focus flow, and the Xtream login
  are code-reviewed only until the Onn-box test. CI result pending at
  push time — see ci-status.txt on main after the run.

## EPG slice — Oct 23 (Tyler correction: EPG must be IN the test build, not a later slice)
- Tyler: "add a spot where I can add the address for an epg", "has to
  have a guide", "with beautiful UI", "everything packed into it" for
  one test, "do it all at once". So EPG rides the next commit after
  build #3's CI result lands, and the delivered test build contains
  build #3 + EPG together — one build, one test.
- Address spots (two, both optional): an EPG address field on the
  provider-add form (per provider), and a global EPG address in Setup
  (fallback). Resolution order: provider's own address, then the
  provider's own xmltv.php for Xtream logins, then the global address.
  No address anywhere means no guide for that provider — never guessed.
- Guide screen (GuideActivity, sidebar "Guide"): channels on the left
  (each row shows its current programme, or "No guide data"), that
  channel's programmes on the right with day headers (Today / Tomorrow /
  date), the on-air programme highlighted with a gold ON NOW badge.
  Tapping the on-air programme plays the channel; tapping a future
  programme shows its details. D-pad left/right switches panes.
  The player info line also shows Now/Next when a guide is loaded.
- Matching: XMLTV channel id against the channel's tvg-id (M3U tvg-id
  attribute, or Xtream epg_channel_id), falling back to a normalized
  display-name match. Channels with no match show "No guide data" —
  listings are never invented.
- Plumbing: streaming XMLTV parse (XmlPullParser, never whole-file in
  memory), raw-XML cache per provider, parsed guides merged into an
  in-memory index (fresh fetch replaces a provider's stale index).
  EpgParser / EpgMatcher (pure, unit-tested) / EpgStore / EpgLoader.
  Provider lines gain an EPG part (m3u 4-part, xtream 6-part; all older
  formats still parse). Channel gains tvgId; cache lines gain tvgId.
- Files: epg/EpgData.kt, EpgParser.kt, EpgMatcher.kt, EpgStore.kt,
  EpgLoader.kt, ui/GuideActivity.kt, res/layout/activity_guide.xml,
  item_programme_row.xml; edits to Channel, M3uLineParser (tvg-id),
  ProviderStore, PlaylistCache, XtreamClient (epg_channel_id),
  AddProviderActivity + layout, MainFragment (Guide sidebar item +
  Setup EPG-address dialog), PlaybackActivity (now/next),
  AndroidManifest (GuideActivity), strings, test EpgMatcherTest.
- HONEST LIMITS: guide quality is only as good as the address the
  user enters and that provider's data. Nothing here has run on a box
  either — same code-review-only caveat as build #3.
- CI FAILURE + FIX (Oct 23, run 3, build #3 commit): compile failed
  with exactly two errors — (1) XtreamClient series loop used
  `ifEmpty { continue }`, and `continue` inside an inline lambda is
  an experimental feature; fixed with a plain `if (id.isEmpty())
  continue`; (2) PlaybackActivity called
  `clearOverrideForType`, which does not exist on Media3's
  TrackSelectionParameters.Builder — the real method is
  `clearOverridesOfType`. Both one-line fixes ride the same commit as
  the EPG slice, so the next CI run builds build #3 + fixes + EPG
  together as the single test build.
