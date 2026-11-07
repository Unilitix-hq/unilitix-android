# Unilitix Android SDK

The 3-line analytics SDK built for African mobile apps.

## Install

Add to your app's `build.gradle.kts`:

```kotlin
implementation("com.unilitix:unilitix-android:1.11.1")
```

Add JitPack to your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

## Initialize

In your `Application` class:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Unilitix.init(this, "ul_live_xxx") {
            debugLogging = BuildConfig.DEBUG
        }
    }
}
```

That's it. No other code needed for screens, taps, crashes — all auto-captured.

`init` does almost nothing on the main thread (it installs the crash handler and
registers lifecycle callbacks); storage, the key store, the database and the native
library are opened on a background thread. Calls made before the SDK is ready —
including calls made before `init` — are buffered (up to 200, oldest dropped) and
replayed in order once the first session has started.

**Multi-process apps.** The SDK runs in the app's main process only. `init` called in
another process (a `:remote` service, a sync adapter) is ignored, so you can call it
unconditionally from `Application.onCreate`.

## Track

```kotlin
// Identify a user
Unilitix.identify("user_123", mapOf("email" to "user@example.com"))

// Track a custom event
Unilitix.trackEvent("purchase_completed", mapOf("amount" to 5000, "currency" to "NGN"))

// Manually track a screen (a repeat of the current screen is ignored)
Unilitix.trackScreen("CheckoutScreen")
```

`identify` sends `POST /v1/ingest/identify {anonymousId, customUserId, traits}` once
(held while consent is pending); later uploads carry the user id, which persists across
launches. `reset()` uploads what was captured under the old identity first, then gives the
device a new anonymous id and install id, drops the user id and traits, and starts a new
session.

Properties and traits are normalised as the SDK contract requires: at most 5 levels of
nesting (deeper maps and lists become `"[Truncated]"`), 100 entries per map or list,
strings (also event names, screen names and user ids) cut to 1024 UTF-16 units without
splitting an emoji, and `NaN`/`Infinity` sent as `null`.

## What's auto-captured

| Feature | Description |
|---|---|
| Screen views | Every Activity and Fragment resume |
| Taps | x/y in dp (logical px) from the window's top-left corner, view class/id |
| Rage taps | 3+ taps in 1 second within 100 dp of each other on one screen |
| Crashes | JVM: stack trace + last 10 breadcrumbs. Native (NDK/C/C++) crashes: the fatal signal and (1.10.0) the crashed thread's backtrace, reported on the next launch |
| ANRs | Live (1.11.0): a watchdog reports a main thread blocked for 5 s at once, with its stack. Android 11+: also the system's ANR record, on the next launch (never twice) |
| Network type | `WIFI`, `ETHERNET`, `5G`, `4G`, `3G`, `2G`, `CELLULAR` (generation unknown), `OFFLINE`, `UNKNOWN` (VPN-only, Bluetooth) |
| Device info | Manufacturer, model, OS version |
| Performance | Memory usage, CPU%, frame drops |

## Privacy

- All `EditText` fields with `inputType=password` are auto-masked — coordinates recorded as `[MASKED]`
- Session replay masks all text by default (`maskAllText = true`): screenshots paint over every
  `TextView`, `Button` and `EditText`; snapshots keep word shapes but replace characters with `•`.
  Images are shown unless `maskImages = true`. See [Replay privacy](#replay-privacy)
- Consent (below) controls what is captured and uploaded

### Consent

The SDK has three consent states (`UnilitixConsent`), shared by every Unilitix SDK:

| State | Capture | Upload | On-device queue |
|---|---|---|---|
| `GRANTED` (default) | yes | yes | sent as usual |
| `PENDING` | yes, to the on-device queue | nothing (events, snapshots, screenshots) | held until a decision |
| `DENIED` | nothing | nothing | deleted (events, snapshots, screenshots, pending crash) |

Apps that show a consent screen after launch (NDPR / GDPR) start in `PENDING`:

```kotlin
Unilitix.init(this, "ul_live_xxx", consent = UnilitixConsent.PENDING) {
    // other options
}

// after the user answers:
Unilitix.setConsent(UnilitixConsent.GRANTED)   // sends everything held while pending
Unilitix.setConsent(UnilitixConsent.DENIED)    // stops capture and deletes the queue

Unilitix.consent                               // current state
```

Java:

```java
Unilitix.init(app, "ul_live_xxx", UnilitixConsent.PENDING);
Unilitix.setConsent(UnilitixConsent.GRANTED);
UnilitixConsent current = Unilitix.getConsent();
```

The decision passed to `setConsent` is persisted: on later launches it wins over the
value passed to `init` (which only applies until the user has decided on this device).
`Unilitix.optOut()` and `Unilitix.optIn()` are shorthands for `setConsent(DENIED)` and
`setConsent(GRANTED)`; users who opted out with an earlier SDK version stay `DENIED`.
Snapshots held while pending are kept in memory only (at most 300) and are lost if
the process dies before consent is granted.

### Units

Everything positional the SDK sends is in dp (Android's logical pixels, the
SDK contract's "logical px", iOS points): tap and rage-tap `x`/`y`, snapshot
node bounds, snapshot and screenshot `viewportWidth`/`viewportHeight`, and the
device `screenWidth`/`screenHeight`. Positions are measured from the window's
top-left corner. `screenDensity` is the pixels-per-dp factor. Screenshot images
keep their pixel size (up to `screenshotMaxWidth`).

### Replay privacy

| Option | Default | Effect |
|---|---|---|
| `maskAllText` | `true` | Every `TextView` (incl. `Button`, `EditText`) is painted over in screenshots; snapshot text becomes `•••` |
| `maskImages` | `false` | Every `ImageView` and bitmap background is painted over; snapshot image nodes carry no content |
| `maskInputs` / `maskInputsInScreenshots` | `true` | Inputs masked (passwords always) |
| `maskedViewIds`, `@UnilitixPrivate` | — | Always masked, whatever else is set; in snapshots the view is one `masked` node (type and bounds only, no text, no children) |
| `WebView` (snapshots) | — | One node, never walked; `masked` while `maskAllText` or `maskInputs` is on (unless unmasked) |

Show views that carry no personal data (headings, button labels, navigation) with
`Unilitix.unmask(view)` — it applies to the view and everything inside it — or in XML:

```xml
<TextView android:id="@+id/title" ...>
    <tag android:id="@id/unilitix_unmasked" android:value="true" />
</TextView>
```

`unmask` never exempts inputs, `maskedViewIds` or `@UnilitixPrivate` views.

Hide a view (and everything inside it) from replay and from tap tracking in any of these ways:

```kotlin
@UnilitixPrivate class CardNumberView(context: Context) : AppCompatEditText(context)  // every instance

class CheckoutActivity : AppCompatActivity() {
    @UnilitixPrivate lateinit var cvv: EditText   // 1.11.0: the view this field holds (Activity or Fragment)
}

Unilitix.mask(view)                               // 1.11.0: any view instance
```

Fields are read when the Activity resumes and when a Fragment's view is created or
resumed. `maskedViewIds` still works. The SDK's consumer ProGuard/R8 rules keep the
annotation on annotated classes and fields in minified apps.

#### Jetpack Compose

From 1.8.0 Compose screens are masked by the same options. The SDK reads the semantics
tree of each `AndroidComposeView` (the tree accessibility services and Compose UI tests
read), on the main thread, at most 2000 nodes per Compose view:

| Semantics | Treated as |
|---|---|
| Text field (`EditableText` / `SetText`), `Password` | Input (`maskInputs`, passwords always) |
| `Role.Image` (`Image`, `Icon`) | Image (`maskImages`) |
| `Text`, or a `contentDescription` on anything else | Text (`maskAllText`) |

The SDK has no runtime dependency on Compose: apps without Compose are unaffected and
there is nothing to add. In Compose code, the equivalents of `unmask` and `@UnilitixPrivate`
are modifiers:

```kotlin
import io.unilitix.sdk.compose.unilitixPrivate
import io.unilitix.sdk.compose.unilitixUnmask

Text("Order summary", Modifier.unilitixUnmask())     // shown, with its subtree
Column(Modifier.unilitixPrivate()) { CardDetails() } // always masked, whatever else is set
```

Limits:

- The walk fails closed. If the semantics tree cannot be read (an unexpected Compose
  version, an error) or has more than 2000 nodes or 64 levels, the whole Compose view is
  painted over in screenshots (whenever `maskAllText`, `maskImages` or
  `maskInputsInScreenshots` is on), and in snapshots it is a single `masked` node
  without text. A warning is logged once.
- Only content in the semantics tree is found. Text drawn directly on a `Canvas`
  without a `contentDescription`, or hidden from semantics with
  `Modifier.clearAndSetSemantics { }`, is not seen and is not masked: mark it
  with `Modifier.unilitixPrivate()`.
- Snapshot nodes from Compose carry a type from their semantics (`Text`, `TextField`,
  `Button`, `Image`, …) and no colours.

Screenshots are change-driven: a frame is taken when the screen redraws and
`screenshotIntervalMs` has passed, at most `maxScreenshotsPerMinute` (20) in any rolling
minute, plus one heartbeat frame every `screenshotHeartbeatMs` (10 s; `0` turns it off) on a
static screen.

### Uploads

Events are queued on the device one by one (each with a client `eventId` and per-session
`seq`, so a retried batch is never counted twice) and uploaded gzip-compressed, in batches
of up to `flushBatchSize`, every `flushIntervalSeconds`, when the queue reaches
`flushBatchSize`, when the app goes to the background, and on `Unilitix.flush()`. Nothing
is attempted while offline. The rules are the SDK contract's:

| Response | What the SDK does |
|---|---|
| 2xx | Batch deleted. |
| no response, 408, 429, 5xx | Kept; backoff 1 s doubling to 5 min plus 0–20% jitter, or `Retry-After` (1–60 s). The backoff also holds `flush()`. |
| 401, 403 | Kept; uploads pause until the next launch (the key is wrong). One warning is logged. |
| 402 | Kept; uploads pause for `Retry-After` (1–3600 s) or 15 minutes (the plan's limit is reached). |
| other 4xx (400, 404, 409, 413, 422) | That batch is dropped. |

A batch that fails 10 times in a row is parked so newer data can go; parked events are
retried on the next three launches, then dropped. A batch is never larger than the API
accepts (it is split before sending). At most `maxOfflineEvents` events are kept, oldest
dropped first.

Replay screenshots and snapshots are stored with the session they were captured in and
uploaded once the server has that session; screenshot uploads declare each frame's exact
size, so the storage upload URL accepts nothing else.

### Backups

User ids, traits and the queue should not be restored onto another device. The SDK keeps
the user id encrypted with an Android Keystore key (keys are not backed up), and replaces
its ids when it finds its preferences restored on a different device. To keep the SDK's
files out of Auto Backup entirely, reference (or copy the rules from) the backup rules
the AAR ships:

```xml
<application
    android:fullBackupContent="@xml/unilitix_backup_rules"
    android:dataExtractionRules="@xml/unilitix_data_extraction_rules" ...>
```

If your app has its own rules, add the SDK's `<exclude>` lines to them.

## Configuration

```kotlin
Unilitix.init(this, "ul_live_xxx") {
    apiUrl = "https://api.unilitix.com"         // backend URL
    autoTrackScreens = true                    // auto-capture Activity/Fragment
    autoTrackTaps = true                       // auto-capture touch events
    autoTrackCrashes = true                    // auto-capture uncaught exceptions
    autoTrackRageTaps = true                   // detect repeated frustrated taps
    flushIntervalSeconds = 30                  // how often to send batches
    flushBatchSize = 100                       // send when buffer reaches this size
    maxOfflineEvents = 1000                    // max events stored offline
    sessionTimeoutSeconds = 30                 // background time before new session
    debugLogging = false                       // verbose SDK logs (logcat tag "Unilitix"); works in release builds
    consent = UnilitixConsent.GRANTED          // initial consent if the user has not decided yet
    maskInputs = true                          // mask password fields
    maskedViewIds = setOf(R.id.et_secret)      // additional views to mask
    maskAllText = true                         // mask all text in replay (Unilitix.unmask(view) to show safe views)
    maskImages = false                         // replace images with placeholders in replay
    maxScreenshotsPerSession = 1500            // screenshot cap per session
    maxSnapshotsPerSession = 1500              // view-tree snapshot cap per session
    maxScreenshotsPerMinute = 20               // rolling one-minute screenshot budget
    screenshotHeartbeatMs = 10_000             // a frame at least this often on a static screen (0 = off)
    sampleRate = 1.0                           // replay sampling: 0.5 = replay for 50% of sessions (events are always captured)
    anrWatchdogTimeoutMs = 5_000               // live ANR watchdog threshold (0 = off)
}
```

## Public API

```kotlin
Unilitix.init(application, apiKey)    // initialize (call once in Application.onCreate)
Unilitix.identify(userId, traits)     // associate events with a user
Unilitix.trackEvent(name, properties) // track a named event
Unilitix.trackScreen(screenName)      // manual screen tracking
Unilitix.startSession()               // force start a new session
Unilitix.endSession()                 // force end the current session
Unilitix.flush()                      // immediately send buffered events
Unilitix.init(application, apiKey, consent) // initialize with an initial consent state
Unilitix.setConsent(consent)          // GRANTED / PENDING / DENIED, persisted
Unilitix.consent                      // current consent state
Unilitix.optOut()                     // = setConsent(DENIED): stop tracking, delete queued data
Unilitix.unmask(view)                 // show a view (and its subtree) in replay despite maskAllText/maskImages
Unilitix.mask(view)                   // hide a view (and its subtree) in replay; taps on it are not recorded
Unilitix.optIn()                      // = setConsent(GRANTED)
Unilitix.reset()                      // clear identity, start fresh anonymous session
Unilitix.recordException(type, message, stackTrace, fatal)  // report a caught error (EXCEPTION) or, with fatal=true, a CRASH persisted before you re-throw
Unilitix.addBreadcrumb(message, category)                   // add to the trail attached to the next crash report (last 50 kept)
Unilitix.isInitialized                                      // true once init() has run
```

Crash and exception reports carry a stable `metadata.fingerprint` (exception type + top three frames, line numbers ignored), `title` and `fatal`, so the dashboard counts one bug once across devices. JVM uncaught exceptions are reported as fatal. Every method is safe on any thread and never throws.

## Wrapper SDKs

The Unilitix Flutter (3.0+) and React Native SDKs run on top of this one on Android. They
use these calls (all `@JvmStatic` on `Unilitix`, safe before `init`, never throwing):

```kotlin
Unilitix.setWrapper(name: String, version: String)
Unilitix.recordExternalError(type: String, message: String, stackTrace: String?, fatal: Boolean,
                             platform: String, fingerprint: String? = null, metadata: Map<String, Any?>? = null)
Unilitix.captureExternalScreenshot(image: ByteArray, width: Int, height: Int, timestampMs: Long, screen: String?)
Unilitix.addExternalSnapshot(json: String)
Unilitix.setExternalScreenCapture(enabled: Boolean)
Unilitix.recordExternalInteraction(type: String, x: Double?, y: Double?)
Unilitix.currentSessionId(): String?
Unilitix.isReplaySampled(): Boolean
```

| Call | Behaviour |
|---|---|
| `setWrapper("flutter", "3.0.0")` | Every session record carries `sdkWrapper` / `sdkWrapperVersion` (top level and in `sessionData`); the User-Agent becomes `Unilitix-Android/1.11.0 (flutter/3.0.0)`. `sdkVersion` stays this SDK's version. |
| `recordExternalError(...)` | `EXCEPTION`, or `CRASH` when `fatal`, with `metadata.platform` = `platform` (`flutter`, `react-native`), `metadata` merged in (e.g. `jsBundleId`, `release`, `dist`), and `metadata.fingerprint` = `fingerprint` when given, else the contract fingerprint of `type` + `stackTrace`. Per-session caps apply (3 per fingerprint, 50 per session). A fatal error marks the session crashed and is persisted and uploaded before the call returns, within 2 s. |
| `captureExternalScreenshot(...)` | A JPEG the wrapper captured and masked. Dropped unless consent allows capture, `captureScreenshots` is on and the session is sampled for replay; counted against `maxScreenshotsPerSession` and `maxScreenshotsPerMinute`; frames over 512 KB are dropped. Stored and uploaded like the SDK's own frames (`format: jpeg`), on Wi-Fi only when `uploadScreenshotsOnWifiOnly`. `width`/`height` are the viewport in logical pixels. |
| `addExternalSnapshot(json)` | A wireframe snapshot in the contract shape `{capturedAt, screenName, viewportWidth, viewportHeight, hierarchy}` (`capturedAt` RFC 3339 or ms since 1970). Same gates and `maxSnapshotsPerSession` cap; the SDK assigns the ordinal. |
| `setExternalScreenCapture(true)` | The SDK's own view-based screenshot and snapshot capture stand down while the wrapper supplies frames. Taps, screens and crashes are unaffected. |
| `recordExternalInteraction("TAP", x, y)` | A `TAP` or `RAGE_TAP` from the wrapper's gesture layer, stamped with the current screen. Pass null `x`/`y` inside a private area and no position is sent. |
| `currentSessionId()` / `isReplaySampled()` | The running session's id (null until the SDK has started) and its replay sampling decision (`sampleRate`, rolled once per session). |

Errors the wrapper reports itself before the platform re-throws them can be kept out of the
JVM crash handler with `Unilitix.setCrashFilter { t -> t !is JavascriptException }`.

## Native (NDK) crashes

From 1.7.0 the SDK also reports crashes in native code (your C/C++ libraries, or
third-party `.so` files), with `autoTrackCrashes` on (the default). Nothing to configure:
the AAR ships a small native library and the consumer ProGuard/R8 rules it needs.

| Android | How |
|---|---|
| 11+ (API 30+) | No native code runs. On the next launch the SDK reads the system's record of how earlier processes ended (`ApplicationExitInfo`, `REASON_CRASH_NATIVE` only) and the signal and crashed thread's backtrace from the tombstone (API 31+), or the signal from the exit description (API 30). |
| 5.0–10 (API 21–29) | `libunilitix-native.so` installs handlers for SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE and SIGTRAP. The handler writes a record to `files/unilitix/native_crash.json` (first the signal, then, from 1.10.0, the signal plus the crashed thread's backtrace, unwound in the handler; see below) and hands the signal to the previous handler (the system's crash reporter, Crashlytics, Sentry, …), so the process dies exactly as before. |

The crash is reported on the next launch as a fatal `CRASH` against the session that was
running when the process died (that session is marked crashed), with the same shape as
the iOS SDK: `exceptionType` `SIGNAL`, message `Fatal signal 11 (SIGSEGV)`, `metadata.signal`,
`metadata.platform` `android`. Consent applies as for every other event: pending keeps the
report on the device, denied drops it.

**Grouping (1.11.0, contract 1.4.0).** The fingerprint is `SIGNAL:<n>` plus the first three
frames of the backtrace that belong to your app's libraries: frames in system libraries
(`/system/`, `/apex/`, `libc.so`, `libart.so`, …), cross-platform engines (`libflutter.so`,
`libhermes.so`, React Native's libraries) and the SDK itself are skipped, and each frame is
`libshop.so cart::total(Cart const&)` (symbol, offset dropped) or `libshop.so+0x1a2b4`
(no symbol). A segfault in `memcpy` called from your code groups by your code; with no app
frame the fingerprint is `SIGNAL:<n>` alone, as before. The API groups crashes from older
SDKs the same way when they arrive.

**Native stack traces (1.10.0).** `stackTrace` is the crashed thread's backtrace (up to 64
frames) in Android's tombstone format, with each library's GNU build id:

```
#00 pc 000000000001a2b4  /data/app/~~Xy==/com.shop-Ab==/lib/arm64/libshop.so (BuildId: 9f2c…)
#01 pc 0000000000356f20  /apex/com.android.art/lib64/libart.so (art_quick_generic_jni_trampoline+144) (BuildId: 5e6f…)
```

Release libraries are stripped, so upload the unstripped ones and the dashboard shows
function, file and line for your frames (OS frames stay as printed). After
`assembleRelease`/`bundleRelease`, with the `unilitix-cli` uploader from the API repo
(`cmd/unilitix-cli`, see its `docs/symbolication.md`), in `app/build.gradle.kts`:

```kotlin
val uploadUnilitixNativeSymbols by tasks.registering(Exec::class) {
    val libs = layout.buildDirectory.dir("intermediates/merged_native_libs/release/out/lib")
    onlyIf { libs.get().asFile.exists() && System.getenv("UNILITIX_API_KEY") != null }
    commandLine(
        "unilitix-cli", "upload-symbols", "--type", "ndk",
        "--app-version", android.defaultConfig.versionName!!,
        "--build", android.defaultConfig.versionCode.toString(),
        "--path", libs.get().asFile.path,
    )
}
afterEvaluate {
    tasks.named("mergeReleaseNativeLibs") { finalizedBy(uploadUnilitixNativeSymbols) }
}
```

Or by hand: `unilitix-cli upload-symbols --type ndk --path app/build/intermediates/merged_native_libs/release/out/lib`.
Crashes are matched by build id, so the version flags only label the upload. Build release
libraries with `-g` for file and line numbers.

On Android 5–10 the handler unwinds with the toolchain's `_Unwind_Backtrace` and maps each
frame to its library via `/proc/self/maps` and the library's in-memory ELF headers, using
only static buffers. Unwinding is not strictly async-signal-safe, so it is fenced: the
signal-only record is written first, a memory fault while capturing is caught and abandons
the capture, and the record with the backtrace replaces the first one atomically (write to
`native_crash.json.tmp`, then `rename`). Whatever goes wrong, the report degrades to the
1.9.0 signal-only record, and the signal still reaches the previous handler.

Not reported: JVM crashes are already reported by the uncaught-exception handler (never
twice), and on Android 11+ exits from before the first launch with 1.7.0 are skipped.
ANRs are reported separately (below).

**Apps that also ship the Unilitix Flutter plugin.** From 1.8.0 (with the plugin 2.5.0+),
a native crash is reported once, by this SDK: the plugin sees
`Unilitix.nativeCrashCaptureActive()` and installs no signal handler of its own and sends
none of the system's exit records. Initialize this SDK in `Application.onCreate`, before
any Flutter engine starts, so the plugin finds it. Sessions and JVM crashes are still
recorded by both SDKs separately (each keeps its own sessions).

To try it: the sample app's crash screen has a "Trigger Native Crash" button
(`Process.sendSignal(Process.myPid(), 11)`), or from a shell
`adb shell run-as <your.package> kill -SEGV <pid>` (`adb shell pidof <your.package>`).
Relaunch the app and the crash appears with the next upload.

## ANRs (app not responding)

**Live watchdog (1.11.0), every Android version.** While the app is in the foreground a
background thread checks the main thread every second; when it has not responded for
`anrWatchdogTimeoutMs` (5 s, like the system's input timeout; `0` turns it off), the SDK
reports an `ANR` right away with the main thread's stack (`metadata.source` `watchdog`),
whether or not the app recovers. Not while a debugger is attached. One report per stall.

**System record.** From 1.9.0, on Android 11+ (API 30+), with `autoTrackCrashes` on (the default), the SDK
also reports ANRs that ended the process: the main thread did not handle input for 5 s, the system showed "*App* isn't
responding", and the process was killed (the user chose "Close app", or the system killed
it in the background). On the next launch the SDK reads the system's record of that exit
(`ApplicationExitInfo`, `REASON_ANR`, the same scan and watermark as native crashes, so
each ANR is reported once) and its thread dump (`getTraceInputStream()`), and sends an
`ANR` event:

- `exceptionType` `ANR`, `exceptionMessage` the system's description
  (`Input dispatching timed out (...)`);
- `stackTrace`: the main thread's block of the dump (thread header, managed and native
  frames, lock lines; the `|` property lines dropped), capped at 16 KB;
- `metadata.fingerprint`: the contract fingerprint of type `ANR` over that stack, so ANRs
  group by where the main thread was stuck; `metadata.importance` and
  `metadata.foreground` (the app was in front of the user).

An ANR is not a crash: it goes to the session that was running (which ends when the
process was killed) but does **not** mark it crashed, so crash-free rates do not change.
The dashboard lists ANRs as their own issue type ("Not responding"). With R8, upload the
mapping file and ANR stacks are deobfuscated like crash stacks. At most 5 ANRs are
reported per launch (the newest). Consent applies as for every other event: pending keeps
the report on the device, denied drops it.

Limitations:
- Android 10 and older (API < 30) have no exit record: ANRs there are reported by the
  watchdog only. An ANR the watchdog already reported is not reported again from the
  system record.
- The dump is the system's: if it was not written or cannot be read, the ANR is still
  reported, with an empty stack (all such ANRs share one issue).
- No breadcrumbs: the report is built from the system record on the next launch.

To try it: the sample app's crash screen has a "Trigger ANR" button (blocks the main
thread for 30 s). Tap the screen again, choose "Close app" when the dialog appears, and
relaunch the app.

## Debug logging

`debugLogging` is off by default. Turn it on to see what the SDK does in logcat (tag
`Unilitix`), in debug or release builds of your app:

```kotlin
Unilitix.init(this, "ul_live_xxx") {
    debugLogging = BuildConfig.DEBUG   // your app's BuildConfig
}
```

Debug logs never include event payloads, user IDs, traits or the API key (only its
`ul_live_` / `ul_test_` prefix). Warnings and errors are always logged.

## Offline Support

Events captured while offline are persisted in a local Room database and sent when
connectivity returns (the SDK listens for network changes; a WorkManager job with a network
constraint also runs when the app goes to the background). Up to `maxOfflineEvents`
(default 1000) events are stored; the oldest are dropped first.

## Performance

Budgets, how they are measured (instrumentation benchmarks on API 24 and 34 emulators in
CI, Firebase Test Lab on real devices) and what the SDK does on which thread:
[docs/performance.md](docs/performance.md).

## Contract conformance

`unilitix-sdk/src/test/.../contract/BehaviourFixturesTest` runs the shared behaviour
fixtures of the Unilitix SDK contract 1.4.0 (sessions, identity, consent, queue, retry,
network types, properties, screens, crashes) against the SDK's engine with a fake clock,
network, transport and storage; every case for Android runs (none skipped).

## Requirements

- Min SDK: 21 (Android 5.0 — covers ~99% of African Android devices)
- Building the SDK from source needs the Android NDK (27.1.12297006; override with
  `-Punilitix.ndkVersion=<version>`) and CMake 3.22.1. Apps that use the published AAR need neither.
- Target SDK: 34
- Kotlin 1.9+

## Sample App

The `sample-app/` module contains a full demo with:
- `MainActivity` — login, custom events, flush, crash navigation
- `ProductActivity` — add to cart, checkout navigation
- `CheckoutActivity` — auto-masked card form, purchase tracking
- `CrashActivity` — triggers a NullPointerException, or a native SIGSEGV, to test crash capture

For emulator testing, `App.kt` points to `http://10.0.2.2:4000` (your Mac's localhost). For physical device testing, replace with your Mac's LAN IP.
