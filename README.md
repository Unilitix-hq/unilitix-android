# Unilitix Android SDK

The 3-line analytics SDK built for African mobile apps.

## Install

Add to your app's `build.gradle.kts`:

```kotlin
implementation("com.unilitix:unilitix-android:1.5.1")
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
| Taps | x/y coordinates, view class/id |
| Rage taps | 3+ taps in 1 second on same area |
| Crashes | Stack trace + last 10 breadcrumbs |
| Network type | 2G / 3G / 4G / 5G / WiFi |
| Device info | Manufacturer, model, OS version |
| Performance | Memory usage, CPU%, frame drops |

## Privacy

- All `EditText` fields with `inputType=password` are auto-masked — coordinates recorded as `[MASKED]`
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
Snapshots held while pending are kept in memory only (bounded by
`maxSnapshotsPerSession`) and are lost if the process dies before consent is granted.

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
Unilitix.optIn()                      // = setConsent(GRANTED)
Unilitix.reset()                      // clear identity, start fresh anonymous session
Unilitix.recordException(type, message, stackTrace, fatal)  // report a caught error (EXCEPTION) or, with fatal=true, a CRASH persisted before you re-throw
Unilitix.addBreadcrumb(message, category)                   // add to the trail attached to the next crash report (last 50 kept)
Unilitix.isInitialized                                      // true once init() has run
```

Crash and exception reports carry a stable `metadata.fingerprint` (exception type + top three frames, line numbers ignored), `title` and `fatal`, so the dashboard counts one bug once across devices. JVM uncaught exceptions are reported as fatal.

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
- Target SDK: 34
- Kotlin 1.9+

## Sample App

The `sample-app/` module contains a full demo with:
- `MainActivity` — login, custom events, flush, crash navigation
- `ProductActivity` — add to cart, checkout navigation
- `CheckoutActivity` — auto-masked card form, purchase tracking
- `CrashActivity` — triggers a NullPointerException to test crash capture

For emulator testing, `App.kt` points to `http://10.0.2.2:4000` (your Mac's localhost). For physical device testing, replace with your Mac's LAN IP.
