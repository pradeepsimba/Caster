# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Caster: an Android app (Kotlin, Jetpack Compose, Material 3; package `mano.algo.caster`, minSdk 24, targetSdk 37, single `:app` module) that shares the phone's videos over the local Wi-Fi. A browser or TV opens the phone's address, asks to pair, and the phone owner approves it. It also casts to Chromecast and works as a remote for Fire TV, Android TV, Samsung, LG and ADB-enabled TVs.

## Commands

Run from the repo root (on Windows use `gradlew` / `gradlew.bat`).

- Unit tests: `./gradlew :app:testDebugUnitTest`
- One test class / method: `./gradlew :app:testDebugUnitTest --tests "mano.algo.caster.server.PairingManagerTest"` (add `.methodName` for a single test)
- Lint: `./gradlew :app:lintDebug`
- Debug build: `./gradlew :app:assembleDebug`
- Release APK: `./gradlew assembleRelease` (`build_apk.sh`; prints the output path)
- Install on a connected device: `./gradlew installDebug` (`run.sh`). It installs on every connected adb device; set `ANDROID_SERIAL` to pick one. `debug-on-device.bat` asks which device, installs, starts the app and tails its log; `connect_device.sh` connects over wireless adb.

"Recheck everything" in this project means: re-read the changes, then run unit tests + lint + a debug build.

Release signing reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) at the repo root. `app/build.gradle.kts` creates the signing config whenever that file exists, so `assembleRelease` fails with "Keystore file not found" if the key named there is missing (as it is now: `key/caster.jks` was deleted). Do not create or alter keys or secrets.

## Working rules for this repo

- The owner tests on their own phone and TVs. Write the code only: do not start, install to, or drive an emulator/device yourself. Compile, unit-test, lint and build checks are fine.
- When finishing, say plainly what was not tested on real hardware (TV protocols are mostly verified only against loopback fakes in the unit tests).

## Architecture

The app is a foreground service running an HTTP server, a Compose UI that controls it, and several independent "talk to a TV" clients. Everything is process-wide Kotlin `object` singletons exposing `StateFlow`s that the UI collects; there is no DI framework and no ViewModel layer.

**Sharing server (`server/`)**
- `MediaServerService` (foreground service, `mediaPlayback` type) owns the lifecycle: it binds the network, picks a port (with fallbacks), starts `LocalMediaServer` (NanoHTTPD), announces `<name>.local` via `MdnsResponder` (only on Android 16+, SDK >= BAKLAVA), and publishes `ServerState` (Stopped / Starting / Running / Error). It reconciles on network changes and has a watchdog and an auto-stop timer. Notification actions and shortcuts (`quick/QuickAction`, tile, widget, `WifiWatcher`/`WifiReceiver`) all go through its `start()`/`stop()`.
- `LocalMediaServer` serves the bundled web page (`docs/index.html`, added to the app's assets in `app/build.gradle.kts` and also the GitHub Pages site, so edit it there) plus a token-protected API (listing, thumbnails, `/video/{id}` with Range support, subtitles, `/api/remote`). Every media route checks the pairing token and the owner's sharing choice.
- Access control is `PairingManager` (pairing requests, codes, approvals, known screens, per-screen video limits and time limits, expiry) + `ShareSettings` (what is shared: all / folders / selected; port; name; extras). `RemoteControl` is the page-to-phone state/command channel (the page polls; commands are queued). `ResumeStore`, `WatchLog`, `ActivityTracker`, `SubtitleFinder` are small persistence/helper objects. Several of these keep data in SharedPreferences; any new preference file must also be excluded in `res/xml/backup_rules.xml` and `data_extraction_rules.xml` (keys and tokens must never be backed up).

**TV remote (`tv/`)** — `TvRemote` is one big state machine (`State`: Idle, Connecting, NeedsPin, Connected, Failed) choosing a `Link` (FireTv, AndroidTv, Samsung, Lg, Adb) after detecting the TV by probing ports. Protocols are hand-written, with no vendor SDKs:
- Fire TV: HTTPS + 4-digit PIN (`FireTvApi`); volume goes through ADB.
- Android TV Remote v2: hand-rolled protobuf over TLS with a client certificate, 6-character pairing code, voice (`AndroidTvApi`, `AndroidTvLink`, `VoiceRecorder`).
- Samsung and LG webOS: WebSocket (`WebSocketClient`, `WebSocketTvs`), keys/tokens stored per host. LG opens a second pointer-input socket for buttons.
- ADB via the `dadb` library. `LanNetwork` binds TV sockets to the Wi-Fi `Network` (needed when mobile data is on). `TvCommands` maps `TvKey` codes to each protocol's commands. Connection logic uses a generation counter (`generation` / `mine`) so a stale attempt can never overwrite a newer one: keep that pattern when adding flows.

**Casting (`cast/`)** — `CastController` wraps the Google Cast SDK + MediaRouter: the Chromecast fetches the file from the phone's server (so casting needs sharing on and the same Wi-Fi). It keeps a list of previously used Chromecasts. `ShareIntake` handles "share a video to Caster". `browser/` is a WebView browser that sniffs media URLs to cast.

**UI (`ui/`)** — single-page Compose app. `CasterApp` holds the app-wide state, permission launchers and navigation flags (`rememberSaveable`); there are no tabs: `HomeScreen` (+ `HomeSections`, `CastCard`) is the page, the TV remote (`RemoteScreen`) is a bottom sheet over it, and Settings, the pickers and history are full-screen pages. The phone's IP/port/`.local` address is shown only in Settings (and carried by the QR code in `QrCode.kt`); keep it out of other screens, notifications and messages.

**Cross-cutting** — `CrashLog` writes crash records and an "activity trail" (`CrashLog.note(...)`) shown in Settings > Diagnostics; use it to make hard-to-reproduce TV/connection failures traceable. PIN lock is in `security/PinLock`. The app targets Android 17, which requires the `ACCESS_LOCAL_NETWORK` permission before any LAN traffic.

## Tests

JUnit-only unit tests under `app/src/test/java/mano/algo/caster/` (`server/`: pairing, resume; `tv/`: command mapping and loopback fake TVs for Android TV and WebSocket TVs; `security/`: PIN). They avoid Android classes, so they run on the JVM. `app/src/androidTest` holds only instrumented scaffolding.
