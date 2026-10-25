# Unilitix Android SDK

The 3-line analytics SDK built for African mobile apps.

## Install

Add to your app's `build.gradle.kts`:

```kotlin
implementation("com.unilitix:unilitix-android:1.10.1")
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

## Track

```kotlin
// Identify a user
Unilitix.identify("user_123", mapOf("email" to "user@example.com"))

// Track a custom event
Unilitix.trackEvent("purchase_completed", mapOf("amount" to 5000, "currency" to "NGN"))

// Manually track a screen
Unilitix.trackScreen("CheckoutScreen")
```

## What's auto-captured

| Feature | Description |
|---|---|
| Screen views | Every Activity and Fragment resume |
| Taps | x/y in dp (logical px) from the window's top-left corner, view class/id |
| Rage taps | 3+ taps in 1 second within 100 dp of each other on one screen |
| Crashes | JVM: stack trace + last 10 breadcrumbs. Native (NDK/C/C++) crashes: the fatal signal and (1.10.0) the crashed thread's backtrace, reported on the next launch |
| ANRs (Android 11+) | "App isn't responding" kills: the main thread's stack from the system's ANR dump, reported on the next launch as an ANR (not a crash) |
| Network type | 2G / 3G / 4G / 5G / WiFi |
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

Each flush sends every request once. Network errors, 408, 429 and 5xx keep the data on the
device and back off (1 s doubling to 5 min, plus jitter; `Retry-After` honoured, 1–60 s).
A 401/403 means the API key is wrong: uploads pause until the app restarts, data is kept
and one warning is logged.

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
    sampleRate = 1.0                           // 0.5 = track only 50% of users
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
Unilitix.optIn()                      // = setConsent(GRANTED)
Unilitix.reset()                      // clear identity, start fresh anonymous session
Unilitix.recordException(type, message, stackTrace, fatal)  // report a caught error (EXCEPTION) or, with fatal=true, a CRASH persisted before you re-throw
Unilitix.addBreadcrumb(message, category)                   // add to the trail attached to the next crash report (last 50 kept)
Unilitix.isInitialized                                      // true once init() has run
```

Crash and exception reports carry a stable `metadata.fingerprint` (exception type + top three frames, line numbers ignored), `title` and `fatal`, so the dashboard counts one bug once across devices. JVM uncaught exceptions are reported as fatal.

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
`metadata.platform` `android`, and fingerprint `SIGNAL:<n>` (one group per signal). Consent
applies as for every other event: pending keeps the report on the device, denied drops it.

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
libraries with `-g` for file and line numbers. Grouping is unchanged: the fingerprint stays
`SIGNAL:<n>` and never includes the frames.

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

From 1.9.0, on Android 11+ (API 30+), with `autoTrackCrashes` on (the default), the SDK
reports ANRs: the main thread did not handle input for 5 s, the system showed "*App* isn't
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
- Android 10 and older (API < 30) have no exit record: ANRs there are not reported. There
  is no in-process watchdog thread (as Sentry has), so an ANR the app recovers from (the
  user chose "Wait") is not reported on any version.
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

Events captured while offline are persisted in a local Room database and automatically sent when connectivity is restored via WorkManager. Up to `maxOfflineEvents` (default 1000) events are stored; oldest events are dropped when the limit is exceeded.

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
