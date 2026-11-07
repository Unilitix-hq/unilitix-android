# Changelog

## 1.11.1

### Fixed
- **Replay: a frame could be skipped when two were requested back to back.**
  The capture slot was freed only after the finished frame had been handed
  on, so a capture started from that hand-off found the SDK still busy and
  was dropped. Apps rarely hit this (frames are at least a second apart).

### Tests and CI
- The screenshot benchmark launches its screen directly and wakes the
  emulator first (on Android 14 emulators `ActivityScenario` waited forever),
  uses its own frame budget, and reports the device state if the screen never
  appears. Benchmarks fail by name after 2 minutes instead of stalling CI;
  emulator budgets are scaled x3.

## 1.11.0

Brings the SDK in line with the Unilitix SDK contract 1.4.0 (all of its shared
behaviour tests pass), adds the API the Flutter 3.0 and React Native SDKs build
on, and makes start-up, replay and crash reporting faster and more reliable.
Drop-in for most apps; read "Changed" before updating.

### Added
- **Calls before `init` are kept.** Events, screens, identify, consent and other
  calls made before the SDK has started (up to 200, oldest dropped first) are
  replayed in order once the first session starts. They used to be dropped.
- **Live ANR detection on every Android version.** When the main thread is
  blocked for 5 seconds while your app is in front, an `ANR` is reported right
  away with the main thread's stack, even if the app recovers. Set the
  threshold with `anrWatchdogTimeoutMs` (0 turns it off). On Android 11+ the
  system's ANR record is still read on the next launch, and the same ANR is
  never reported twice.
- **`Unilitix.mask(view)`, and `@UnilitixPrivate` on fields.** Annotating a
  View field of an Activity or Fragment now hides that view in replay and stops
  tap recording on it, like annotating a View class. `mask(view)` does the same
  for any view.
- **API for wrapper SDKs** (Flutter, React Native): `setWrapper`,
  `recordExternalError`, `captureExternalScreenshot`, `addExternalSnapshot`,
  `setExternalScreenCapture`, `currentSessionId`, `isReplaySampled`. See
  "Wrapper SDKs" in the README.
- **Backup rules.** The AAR ships `@xml/unilitix_backup_rules` and
  `@xml/unilitix_data_extraction_rules` for your manifest, and the SDK replaces
  its ids if it finds them restored from another device's backup.
- **Performance budgets in CI.** Init cost, `trackEvent` cost and screenshot
  cost are measured on Android 7 and 14 emulators on every pull request, and on
  real devices nightly (Firebase Test Lab). See `docs/performance.md`.
- **Signed build provenance.** Each published AAR comes with a GitHub build
  provenance attestation (`gh attestation verify`).

### Changed
- **`identify` sends its own request.** It now posts the user id and traits to
  `/v1/ingest/identify` once (held while consent is pending), instead of
  attaching the traits to every session. The user id still travels with every
  upload and is kept across launches.
- **`reset()` also replaces the install id** and uploads what was already
  captured under the old identity first. Opting out deletes the install id.
- **`sampleRate` samples session replay only.** It used to drop custom events
  at random. Now every event is captured, and `sampleRate` decides once per
  session whether screenshots and snapshots are taken.
- **Custom event properties are sent as `properties`**, not mixed into
  `metadata`, and are normalised: at most 5 levels deep, 100 entries per map or
  list, strings (and event names, screen names, user ids) cut at 1024
  characters without breaking an emoji, `NaN` and `Infinity` sent as `null`.
  A `NaN` can no longer break a whole upload.
- **Every event has an `eventId` and a sequence number**, and each session
  starts with a `SESSION_START` event, so a retried upload is never counted
  twice.
- **Network types follow the contract:** `ETHERNET` is no longer reported as
  `WIFI`, and the session's network type is no longer `OFFLINE` on Ethernet or
  an unknown network. New values: `ETHERNET`, `5G` (also on sessions),
  `CELLULAR` (generation unknown) and `UNKNOWN`.
- **A 402 (plan limit reached) keeps your data** and pauses uploads for the
  time the server asks (1 second to 1 hour, 15 minutes by default). It used to
  drop the batch.
- **Calling `trackScreen` with the screen that is already showing does nothing.**
- **Native crashes group by your code.** A native crash's fingerprint now
  includes the first three frames from your app's own libraries (system
  libraries, Flutter, React Native and the SDK are skipped), so different
  native bugs are no longer one issue per signal. The API regroups older
  reports the same way.
- **`@UnilitixPrivate` can no longer target Kotlin properties**, only classes and
  fields (Kotlin applies it to the backing field). A property without a
  backing field, which the SDK could never read, no longer compiles with it.
- **The SDK runs in your app's main process only.** `init` in another process
  is ignored.
- `recordException` now sends the message as given; the platform goes to
  `metadata.platform` instead of a `[android]` prefix on the message.

### Fixed
- **Screenshot uploads against the current API.** The SDK looked for the
  upload slots under `uploads`; the API answers `slots`, so frames stayed on the
  device. Both names are read now, each frame is matched to its slot by number,
  and its exact size is declared so the storage accepts only that frame.
- **Snapshots could be uploaded under the wrong session.** They were held in
  memory and sent with whichever session was uploaded next. They are now
  stored on the device with their own session and uploaded under it.
- **`init` no longer blocks the main thread on disk or the key store.** Only
  the crash handler is installed right away; the database, encrypted storage
  and native library open on a background thread.
- **Screenshots are taken off the main thread** with PixelCopy on Android 8+,
  directly at the upload size, which also captures GPU-drawn content (hardware
  bitmaps) that the old method could not. Older Android versions still draw
  the screen at the upload size.
- **A batch the server keeps failing no longer blocks the queue.** After 10
  failed attempts it is set aside so newer data goes, retried on the next three
  launches, then dropped. A batch is never larger than the API accepts.
- **No timers in the background.** CPU sampling, the battery receiver and the
  ANR watchdog stop when the app leaves the screen.
- **Crash reports during start-up are no longer lost.** A JVM crash before the
  SDK has finished starting is written to disk and reported on the next launch.
- Requests time out after 40 seconds in total, as the contract specifies.

### Upgrading from 1.10
- Events still queued from 1.10 are converted and uploaded under their sessions
  on the first launch; the install id and user id are kept.
- If your app reads `UnilitixConfig.sampleRate` to estimate event volume, note
  that events are no longer sampled.

## 1.10.2

Host-app safety fixes from the go-live audit. No API changes; a drop-in
update from 1.10.1.

### Fixed
- **`Unilitix.init` can no longer crash the app.** Anything that fails while
  the SDK starts (for example the network callback that some Android 11
  devices refuse with a `SecurityException`, or an app that already holds too
  many network callbacks) is caught. The SDK logs one warning, undoes what it
  had set up and stays switched off for that launch; calling `init` again
  later retries. Before, a failed init also blocked every later attempt.
- **The network-change listener is optional.** If it cannot be registered the
  SDK carries on; the network type is still read for every event.
- **Out-of-range configuration values are corrected instead of failing.** A
  `flushIntervalSeconds` of 0 or less (which used to make init throw),
  `screenshotQuality` outside 0–100, a non-positive `screenshotMaxWidth`,
  batch size, or a `sampleRate` outside 0–1 are clamped to the nearest valid
  value.
- **Screenshots use far less memory and can no longer cause an
  out-of-memory crash.** Each frame is now drawn directly at the upload size
  (`screenshotMaxWidth`, 480 px by default) instead of at full screen
  resolution and then copied twice, about 5–10× less memory per capture.
  Any failure while capturing or encoding, including running out of memory,
  drops that frame. After an out-of-memory error, screenshots stay off for
  the rest of the process. Snapshot (wireframe) capture errors are contained
  in the same way.
- **`@UnilitixPrivate` survives R8.** The SDK's consumer rules now keep the
  annotation on annotated View classes in minified apps (and stop R8 from
  merging those classes), so they stay masked and excluded from taps.
- **Narrower ProGuard/R8 rules.** The SDK no longer keeps every Room database,
  entity and DAO in your app, and no longer turns off R8 warnings for all of
  OkHttp, Okio and coroutines. It keeps only its own classes.
- **Frame-drop tracking only runs while your app is on screen.** It used to
  wake the main thread on every frame for the life of the process, including
  in the background. The `frameDrops` count now restarts with each session,
  and the gap while the app was in the background no longer counts as a drop.
- **Taps are never recorded twice and never break touch handling.** Calling
  `Unilitix.attachToActivity` on an Activity the SDK already tracks no longer
  wraps its touch handler a second time. Any error while recording a tap is
  caught before it can reach your app's `dispatchTouchEvent`.
- **Sessions report the right `sdkVersion`.** 1.10.1 reported itself as
  1.10.0. The version now has a single source (`unilitix.version` in
  `gradle.properties`) for both the published artifact and the reported value.

### Documentation
- `@UnilitixPrivate` applies to View classes only. Annotating a field or
  property compiles but has no effect; use `maskedViewIds` for single views.

### Build
- Pull requests and pushes to `main` now run unit tests, lint and the release
  build in CI (`.github/workflows/ci.yml`). The publish workflow also runs
  lint, and retries the NDK install.

## 1.10.1

Safety and privacy fixes from the 24 October 2026 audit. No API removals.

### Fixed
- **The SDK can no longer crash the host app on a storage error.** SDK
  coroutines run under an exception handler, and persisting a batch catches
  database failures (disk full, I/O errors), dropping that batch instead of
  throwing. A database written by a newer SDK (an app downgrade) is recreated
  instead of failing to open.
- **WebViews are masked in screenshots.** A WebView's page (logins, KYC and
  payment forms) cannot be walked for its fields, so it is painted over whole
  whenever text or inputs are masked, as the iOS SDK does.
- **Windows marked `FLAG_SECURE` are never captured**, in screenshots or
  wireframe snapshots.
- **`reset()` now unlinks users.** The anonymous id is a random UUID (it used
  to be a hash of ANDROID_ID, package and install time, so reset produced the
  same id). Existing installs keep their stored id until the next reset.
- **The crash handler waits at most 2 s** (the contract's fatal flush budget)
  for the queue write, instead of always sleeping 3 s before passing the crash
  to the next handler.
- **Init off the main thread is safe.** `Unilitix.init` called from another
  thread (a React Native bridge) now runs on the main thread, where lifecycle
  observers must be registered, instead of throwing.

### Added
- `Unilitix.attachToActivity(activity)`: after a late init (React Native,
  Flutter add-to-app), attaches taps, screen tracking and replay to the
  Activity that already exists, instead of waiting for the next one.
- `Unilitix.setCrashFilter { throwable -> Boolean }`: lets a wrapper SDK that
  already reported a crash (React Native's fatal JS error) have the JVM
  exception passed on unrecorded, so it is not counted twice.

## 1.10.0

### Added
- **Native crash stack traces.** Native (NDK) `CRASH` reports now carry the
  crashed thread's backtrace as `stackTrace`, in Android tombstone format
  (`#00 pc <rel_pc>  <library path> (function+offset) (BuildId: <hex>)`, up to
  64 frames), so the API can symbolicate it with the app's unstripped
  libraries (`unilitix-cli upload-symbols --type ndk`, API with migration
  000061).
  - Android 12+ (API 31+): read from the tombstone `ApplicationExitInfo`
    returns (crashing thread's `current_backtrace`, pure-Kotlin protobuf
    reader; up to 1 MB of the trace is read). API 30 keeps the signal-only
    report.
  - Android 5–10 (API 21–29): the signal handler unwinds the crashed thread
    (`_Unwind_Backtrace`, at most 64 frames) and resolves each frame's library,
    `rel_pc` and build id from `/proc/self/maps` and the in-memory ELF
    headers, with static buffers only. It first writes the 1.9.0 signal-only
    record; memory faults while capturing are caught (SIGSEGV/SIGBUS unblocked
    for that thread, `siglongjmp` back) and abandon the capture; the full
    record replaces the first via `native_crash.json.tmp` + `rename`.
- README: Gradle task to upload native symbols after the release build.

### Unchanged
- Grouping: a `SIGNAL` crash's fingerprint is still `SIGNAL:<n>` (`SIGNAL`
  when unknown) and never includes the frames; `SIGNAL:11` is still
  `8f1fc15bfa06be00`. Event shape is otherwise the same (no new metadata).

### Notes
- Frames in OS libraries stay as the device printed them; only the app's
  libraries need uploads. Function names in the device's frames are the
  library's exported names (often mangled C++); the server's symbolicated view
  names functions from DWARF.
- `libunilitix-native.so` grows by a few KB per ABI (unwinder and the new
  capture code); it is still loaded only on API 21–29. Still one C file
  (`unilitix_native.c`, no header), so the Flutter plugin's
  `scripts/sync-android-native.sh` vendors it unchanged.

## 1.9.0

### Added
- **ANR reporting (Android 11+).** `REASON_ANR` exits found by the existing
  `ApplicationExitInfo` scan (same watermark as native crashes, so each is
  reported once) are sent on the next launch as `ANR` events (SDK contract
  1.3.0): `exceptionType` `ANR`, the system's description as the message, the
  main thread's block of the ANR thread dump as `stackTrace` (pure-Kotlin
  parser, `|` property lines dropped, capped at 16 KB), and `metadata` with
  the contract fingerprint (type `ANR` over the main thread, so ANRs group by
  where the main thread was stuck), `platform`, `importance` and `foreground`.
  At most 5 per launch (newest kept); the dump is read only for those.
- The ANR goes to the session that was running (via the session history, as
  for native crashes); that session ends when the process was killed but is
  **not** marked crashed: an ANR is its own issue type, so crash-free rates
  keep their meaning. Pending consent holds the report, denied drops it;
  `autoTrackCrashes = false` turns it off.
- Sample app: "Trigger ANR" on the crash screen.

### Notes
- Not reported: ANRs on Android 10 and older (no exit record), and ANRs the
  app recovered from ("Wait"); there is no in-process watchdog thread.
- Needs the API with contract 1.3.0 (ANR event type); an older API drops
  unknown event types.
- Contract constants (`SdkContract.kt`) updated to 1.3.0, including the ANR
  fingerprint vectors.

## 1.8.0

### Added
- **Jetpack Compose masking in session replay.** `maskAllText`, `maskInputs` /
  `maskInputsInScreenshots` and `maskImages` now cover Compose. For each
  `AndroidComposeView` the screenshot and snapshot walks read the unmerged
  semantics tree (`RootForTest.semanticsOwner`, public Compose UI API, no
  reflection), on the main thread, capped at 2000 nodes and 64 levels per
  Compose view. Text fields (`EditableText` / `SetText`) and `Password` nodes
  are inputs, `Role.Image` nodes images, and `Text` or a `contentDescription`
  on anything else is text. Screenshots paint over each node's visible
  (clipped) bounds; snapshots get one node per semantics node, typed from its
  semantics (`Text`, `TextField`, `Button`, `Image`, `ComposeNode`), with text
  redacted or `[MASKED]` exactly as for views.
- `Modifier.unilitixUnmask()` and `Modifier.unilitixPrivate()`
  (`io.unilitix.sdk.compose`): the Compose versions of `Unilitix.unmask(view)`
  and `@UnilitixPrivate`, as custom semantics properties.
- `Unilitix.nativeCrashCaptureActive()`: true when this SDK captures native
  crashes in the process (initialized with `autoTrackCrashes`; on API 21-29
  its signal handler installed). The Unilitix Flutter plugin 2.5.0+ reads it
  and then leaves native crashes to this SDK, so an app that ships both
  reports each native crash once instead of twice.

### Fail closed
- If a Compose view's semantics cannot be read (unknown Compose version, an
  exception) or exceed the caps, the whole Compose view is painted over in
  screenshots (when `maskAllText`, `maskImages` or `maskInputsInScreenshots`
  is on) and is a single `masked` node with no text in snapshots. Logged once.

### Notes
- No new runtime dependency: Compose UI is `compileOnly` (1.6.8) and only
  touched after it is found at runtime; apps without Compose are unaffected.
  The consumer R8 rules add `-dontwarn androidx.compose.**` for them.
- Not found, so not masked: text drawn on a `Canvas` without semantics, or
  hidden with `clearAndSetSemantics { }`. Use `Modifier.unilitixPrivate()`.
- The "Compose text is not masked" caveat of 1.6.0 is lifted.

## 1.7.0

Native (NDK) crash capture, matching UnilitixCore (iOS) 0.5.0: a crash in C/C++
code is reported on the next launch as a fatal `CRASH` against the session that
was running when the process died. On by default with `autoTrackCrashes`; no app
changes or configuration.

### Added
- **Android 11+ (API 30+): no native code.** At init the SDK reads
  `ActivityManager.getHistoricalProcessExitReasons` and reports exits with
  `REASON_CRASH_NATIVE` newer than a watermark kept in the SDK's preferences
  (at most 5 per launch, newest first). The signal comes from the tombstone
  (API 31+, `Tombstone.signal_info.number`, read with a tiny protobuf reader),
  else from the exit description, else it is unknown. `REASON_CRASH` (JVM) is
  never reported here: the uncaught-exception handler already reported it.
  `REASON_ANR` is selected but not sent (the contract has no ANR type yet).
  - **First launch with 1.7.0:** the watermark is set to now and nothing is
    reported, so exits from before the upgrade (already-reported JVM crashes,
    native crashes of sessions uploaded long ago, possibly before a consent
    decision) never arrive as a flood of stale crashes. A native crash in that
    first 1.7.0 process is reported on the launch after it.
- **Android 5.0–10 (API 21–29): `libunilitix-native.so`.** A C signal handler
  (SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP: the iOS list) on an
  alternate stack writes one record to `files/unilitix/native_crash.json`
  using only async-signal-safe calls, then chains to the previous handler
  (debuggerd, Crashlytics, Sentry, …) or the default action, so the process
  dies as before. Not installed on API 30+. If the library fails to load, the
  SDK logs a warning and carries on without it.
- **Same record and event as every Unilitix SDK.** The file holds
  `{"platform":"android","exceptionType":"SIGNAL","signal":11,"timestamp":<ms>}`
  (UnilitixCore `CrashFile` format). The event: `exceptionType` `SIGNAL`,
  `exceptionMessage` `Fatal signal 11 (SIGSEGV)`, `title`
  `SIGNAL: Fatal signal 11 (SIGSEGV)`, `fatal`, timestamp = time of death,
  `metadata` `{fingerprint, platform: "android", signal}`. Fingerprint key
  `SIGNAL:<n>` (`SIGNAL:11` = `8f1fc15bfa06be00`, as on iOS and Flutter); an
  unknown signal is keyed `SIGNAL` with no `metadata.signal`.
- **Attribution.** The SDK keeps the last 8 sessions' records on the device.
  The crash goes to the stored session with the latest start at or before the
  crash time + 1 s; that session is re-sent marked `crashed`, with
  `endedAt`/`durationMs` ending at the crash. No match: the current session,
  which is then marked crashed. Reports are queued before the new session's
  first batch.
- **No double reports.** The JVM crash handler (and fatal
  `recordException`) tells the native handler the exception is handled, so a
  SIGABRT that follows it is not recorded again. The record file is read and
  deleted at init, before this process's handler is installed: exactly once.
- **Consent.** Pending keeps native reports on the device like other events;
  denied drops them (the file is deleted and the watermark advanced, so they
  are never sent later) and also clears the stored session records.
- Sample app: "Trigger Native Crash (SIGSEGV)" on the crash screen.

### Size
- The AAR now contains `jni/{armeabi-v7a,arm64-v8a,x86,x86_64}/libunilitix-native.so`,
  plain C (no libc++, no liblog), `-Os`, hidden visibility, stripped: about
  6–10 KB per ABI uncompressed, roughly 15–25 KB added to the AAR. An app
  shipped as an App Bundle or with ABI splits gets one ABI, so about 10 KB per
  install. Linked with 16 KB max page size (Android 15+ devices, Play
  requirement).

### Build
- The SDK module now builds native code: NDK `27.1.12297006` (override with
  `-Punilitix.ndkVersion=<version>`), CMake 3.22.1 (AGP default). The publish
  workflow installs both with `sdkmanager` and fails if any ABI's `.so` is
  missing from the release AAR. Apps using the published AAR need no NDK.
- `consumer-rules.pro` keeps the JNI methods of
  `io.unilitix.sdk.capture.NativeSignalHandler` (bound by name).

### How to test
- Sample app: crash screen → "Trigger Native Crash (SIGSEGV)", relaunch.
- Any debuggable app: `adb shell run-as <package> kill -SEGV $(adb shell pidof <package>)`,
  relaunch. Or a JNI null dereference in your own native code.
- Expect one `CRASH` `SIGNAL: Fatal signal 11 (SIGSEGV)` with fingerprint
  `8f1fc15bfa06be00` on the session that was running. With
  `debugLogging = true`, logcat (tag `Unilitix`) shows
  `native crash … reported against session …`.

## 1.6.1

View-tree snapshots now match UnilitixCore (iOS) 0.4.0 and common
session-replay practice, and every position or size the SDK sends is in
logical pixels, as the SDK contract specifies.

### Changed — read before upgrading
- **Taps, screen size and snapshot coordinates are in dp** (the contract's
  "logical px"; iOS sends points, Flutter logical pixels). Pixels ÷
  `displayMetrics.density`, rounded to whole dp, measured from the window's
  top-left corner (on API 30+ the window's own bounds, so split screen is
  correct). They used to be physical pixels.
  - `TAP` and `RAGE_TAP` `x`/`y` (whole numbers now; the rage-tap position
    is the cluster centre, rounded).
  - Snapshot node `bounds` (`x`, `y`, `w`, `h`) and snapshot
    `viewportWidth` / `viewportHeight`.
  - Screenshot `viewportWidth` / `viewportHeight` (the space taps are
    overlaid in). The image itself is unchanged.
  - Device `screenWidth` / `screenHeight`. `screenDensity` is unchanged
    (pixels per dp), so pixels can still be recovered.
  The rage-tap radius is 100 dp applied directly (it was already 100 dp,
  scaled to pixels). Replay frames are drawn scaled by their own viewport, so
  1.6.0 and 1.6.1 replays both render, and taps now line up with snapshot
  frames. **Heatmaps or reports that mix Android sessions from before 1.6.1
  with newer ones will show the older taps at pixel scale** (2–4× too far
  from the top-left corner on most phones); filter by SDK version if that
  matters. Anything else that reads raw Android positions must expect dp from
  1.6.1 on.
- **Private views stay in the tree as placeholders.** `@UnilitixPrivate`
  views and views in `maskedViewIds` are sent as a node with `type`,
  `bounds` and `"masked": true` only: no text, no other fields, no children
  (`@UnilitixPrivate` views used to be dropped, leaving a hole in the
  layout; `maskedViewIds` views used to keep their children).
- **`WebView`s are one leaf node**, `"masked": true` while `maskAllText` or
  `maskInputs` is on (unless inside an `unmask`ed subtree), as on iOS.
- **Unchanged screens are not re-sent.** The tree is walked once per
  `snapshotIntervalMs`, but a walk identical to the last snapshot kept in
  the session (same screen, viewport and tree) is dropped, so a static
  screen costs one snapshot instead of one per second. Ordinals count kept
  snapshots only, so they stay contiguous; `maxSnapshotsPerSession` counts
  kept snapshots too.
- **Navigation is captured promptly.** After an activity resumes (a new
  screen or a return from the background) the tree is walked at the next
  250 ms tick, as soon as `snapshotIntervalMs` has passed since the last
  kept snapshot, instead of a full interval later.
- **Size caps:** a snapshot holds at most 2000 nodes and 64 levels below the
  root (the iOS limits). Past them, nodes are left out; nothing marks the
  truncation.

### Fixed
- **The view tree is read on the main thread.** It used to be walked on a
  background thread, racing layout and text changes (wrong bounds, torn
  text, occasional exceptions). The walk now builds an immutable tree on
  the main thread; only JSON encoding and the hand-off run on the IO
  dispatcher. Reflection lookups for background colours are cached to keep
  the walk cheap.

Masking rules, consent, sampling and `maxSnapshotsPerSession` defaults are
unchanged.

## 1.6.0

Brings session replay, upload retry and crash reporting in line with the
shared SDK contract (v1.2.0). Closes every Android divergence the contract
lists.

### Changed — read before upgrading
- **`maskAllText` is on by default.** Screenshots now paint over every
  visible `TextView` (including `Button`, `EditText` and other subclasses),
  not only inputs; snapshots keep each text's word shapes and length but
  replace every character with `•` (`"redacted": true` on the node).
  **Replays will look more masked after upgrading.** To show views that
  carry no personal data (headings, button labels, navigation), unmask them:
  ```kotlin
  Unilitix.unmask(toolbarTitle)          // this view and everything inside it
  ```
  ```xml
  <TextView ...>
      <tag android:id="@id/unilitix_unmasked" android:value="true" />
  </TextView>
  ```
  or turn the option off with `maskAllText = false` to get the 1.5 behaviour
  (inputs only). `unmask` never exempts inputs: they follow `maskInputs` /
  `maskInputsInScreenshots` as before, and `@UnilitixPrivate` and
  `maskedViewIds` always win. Jetpack Compose text is not covered (the SDK
  does not walk Compose semantics): put sensitive Compose content in a host
  view listed in `maskedViewIds` or annotated `@UnilitixPrivate`.
- **Session caps raised to the contract defaults:** `maxScreenshotsPerSession`
  300 → **1500** and `maxSnapshotsPerSession` 200 → **1500**. The per-minute
  budget below keeps a normal session far below them. `maxSnapshotsPerSession`
  is now a real per-session cap (it used to only size the in-memory buffer);
  snapshots waiting for upload are held in memory up to 300.
- **The on-device screenshot queue is bounded by bytes as well as count**
  (40 MB, and `maxScreenshotsPerSession` frames), oldest dropped first, so
  frames waiting for Wi-Fi cannot grow without limit.
- **Upload retry follows the contract** (ported from the Flutter SDK 2.1.1).
  Each flush cycle makes every request once — the old up-to-5 blocking
  retries inside one flush are gone.
  - Network error, 408, 429, 5xx: the data stays queued and a backoff window
    opens: `min(1 s × 2^n, 300 s)` plus 0–20% jitter after the n-th
    consecutive failure; flushes are skipped until it closes and a delayed
    flush runs when it does. A `Retry-After` header (seconds) replaces the
    window, clamped to 1–60 s (it was unclamped, 5 s when absent). One
    success resets it.
  - 401/403: **uploads pause until the app restarts** (the key is wrong or
    revoked) and one warning is logged (never the key). Nothing is deleted:
    data is sent on a later launch once the key is fixed. 1.5 dropped these
    batches like any other 4xx.
  - Other 4xx: that batch is dropped, no backoff (as before).
  - Snapshots that fail to upload with a retryable error are kept for the
    next attempt instead of being lost.
  - The WorkManager worker no longer returns `Result.retry()`; WorkManager's
    own exponential backoff no longer stacks on top of the SDK's.
- `maskedViewIds` now masks in screenshots even with
  `maskInputsInScreenshots = false` (it was skipped then).

### Added
- `maskAllText` (default `true`) and `maskImages` (default `false`) in
  `UnilitixConfig.Builder`, with Java setters. `maskImages` paints over every
  `ImageView` with a drawable and every leaf view with a bitmap background in
  screenshots, and marks image nodes `masked` in snapshots.
- `Unilitix.unmask(view)` / `Unilitix.unmask(view, false)` and the
  `@id/unilitix_unmasked` view tag.
- **Change-driven screenshots with a per-minute budget and a heartbeat**
  (`maxScreenshotsPerMinute`, default 20; `screenshotHeartbeatMs`, default
  10000, 0 turns it off). A frame is taken when the window redrew (or a new
  screen resumed) and `screenshotIntervalMs` has passed, at most
  `maxScreenshotsPerMinute` in any rolling minute, plus one heartbeat frame
  per `screenshotHeartbeatMs` on a static screen. The first frame of each
  session is taken at once. Same rules as the iOS and Flutter SDKs; 1.5 took
  a frame every interval whether or not anything changed.
- **Per-session crash dedup** (`maxReportsPerFingerprint` = 3,
  `maxErrorReportsPerSession` = 50): the same fingerprint is reported at most
  3 times per session and at most 50 CRASH + EXCEPTION reports are sent per
  session, for automatic crashes and `recordException` alike. Counts reset
  when a new session starts.

## 1.5.2

### Removed
- **Certificate pinning.** Release builds set up an OkHttp pinner with
  placeholder pins for `unilitix-api-production.up.railway.app`. It never
  took effect (the SDK talks to `api.unilitix.com`), but anyone pointing
  `apiUrl` at that host would have had every request fail. The API's
  certificates rotate (Let's Encrypt, including intermediates), and a pin in
  an SDK cannot be updated in apps already installed, so pinning is gone
  rather than filled in. Apps that want pinning can add it for
  `api.unilitix.com` in their own `network_security_config.xml`.

## 1.5.1 — 2026-10-18

### Fixed
- **`debugLogging` works in release builds.** It was applied only when the
  library's own `BuildConfig.DEBUG` was true, which is never the case in the
  published AAR, so SDK logs could not be turned on in an app. The default is
  now `false` (it was the library's `BuildConfig.DEBUG`), as the shared SDK
  contract specifies; set `debugLogging = true` to see SDK logs. Warnings and
  errors are logged as before.
- **Debug logs no longer contain personal data or secrets.** The ingest
  payload (which can contain user IDs, traits and emails) is no longer logged,
  only its size; `identify()` no longer logs the user ID; the init line shows
  only the API key's type prefix (`ul_live_…`). The debug-build HTTP logger
  redacts the `x-unilitix-key` header.

### Added
- **Consent states** (contract: `granted | pending | denied`, default
  `granted`): `enum class UnilitixConsent { GRANTED, PENDING, DENIED }`,
  `Unilitix.setConsent(consent)`, `Unilitix.consent`,
  `Unilitix.init(app, apiKey, consent) { … }` and
  `UnilitixConfig.Builder.consent`.
  - `PENDING`: events, snapshots and screenshots are captured to the
    on-device queue but nothing is uploaded until a decision.
  - `GRANTED`: uploads normally, including everything held while pending.
  - `DENIED`: capture stops and everything queued on the device (event
    batches, in-memory snapshots, screenshots, a pending crash report,
    breadcrumbs) is deleted.
  - The decision is persisted; on later launches it wins over the value
    passed to `init`. The WorkManager flush worker and the snapshot and
    screenshot uploaders check consent before every upload.

### Changed
- `optOut()` / `optIn()` are now aliases for `setConsent(DENIED)` /
  `setConsent(GRANTED)`. **`optOut()` now also deletes data already queued on
  the device** (it used to only stop new capture), and crashes are no longer
  recorded while opted out. The 1.5.0 persisted opt-out flag is migrated:
  opted-out users stay `DENIED`.
- `Unilitix.init(app, apiKey)` without a configuration lambda is now callable
  from Java.

## 1.5.0 — 2026-10-18

Conforms to more of the shared SDK contract (v1.0.0).

### Fixed
- **Default `apiUrl` is `https://api.unilitix.com`.** It was
  `https://api.unilitix.io`; apps that did not set `apiUrl` explicitly were
  sending to the wrong host. React Native apps were not affected (the RN SDK
  always passes the URL).
- **Crash fingerprints no longer include the error message** for React Native
  (Hermes) errors whose message ends in parentheses, e.g.
  `(evaluating 'cart.items')`. Such lines were mistaken for stack frames, so
  one bug produced many fingerprints. JVM frames must now start with `at`.
- **Rage-tap radius is 100 dp** on every screen. It was 100 physical pixels,
  which shrank to ~33 dp on a 3x display and missed most rage taps.

### Added
- `SdkContractTest`: config defaults, event types, limits and the crash
  fingerprint test vectors are checked against the generated contract
  (`src/test/.../contract/SdkContract.kt`). Known gaps (retry scheduler,
  pending consent, per-minute screenshot budget, text/image masking,
  per-session crash dedup, replay caps) are listed in the contract and
  skipped until they ship.

## 1.4.2 — 2026-10-17

### Added
- `Unilitix.recordException(type, message, stackTrace, fatal, platform)`: report an
  error the app (or a bridge such as React Native) caught itself. `fatal = false`
  emits a non-fatal `EXCEPTION`; `fatal = true` emits a `CRASH`, marks the session
  crashed and persists the report to the on-device queue before returning
  (bounded to 2 s) so the caller can safely let the process die.
- `Unilitix.addBreadcrumb(message, category)` and `Unilitix.isInitialized`.
- Every crash and exception report carries `fatal`, `title` and
  `metadata.fingerprint` (exception type + top three frames with line numbers
  removed, same hash as the Flutter SDK) so one bug is counted once across devices.
- `EXCEPTION` is a first-class event type on the wire.

### Changed
- Breadcrumb trail grows from 10 to 50 entries and can carry a message.
- JVM uncaught exceptions are reported with `fatal: true`; stack traces are
  capped at 16 KB.
