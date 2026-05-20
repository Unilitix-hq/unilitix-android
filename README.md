# Unilitix Android SDK

The 3-line analytics SDK built for African mobile apps.

## Install

Add to your app's `build.gradle.kts`:

```kotlin
implementation("io.unilitix:unilitix-android:1.0.0")
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
- Call `Unilitix.optOut()` to disable all tracking (persists across app restarts)
- Call `Unilitix.optIn()` to re-enable tracking

## Configuration

```kotlin
Unilitix.init(this, "ul_live_xxx") {
    apiUrl = "https://api.unilitix.io"          // backend URL
    autoTrackScreens = true                    // auto-capture Activity/Fragment
    autoTrackTaps = true                       // auto-capture touch events
    autoTrackCrashes = true                    // auto-capture uncaught exceptions
    autoTrackRageTaps = true                   // detect repeated frustrated taps
    flushIntervalSeconds = 30                  // how often to send batches
    flushBatchSize = 100                       // send when buffer reaches this size
    maxOfflineEvents = 1000                    // max events stored offline
    sessionTimeoutSeconds = 30                 // background time before new session
    debugLogging = false                       // verbose OkHttp + SDK logs
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
Unilitix.optOut()                     // disable all tracking
Unilitix.optIn()                      // re-enable tracking
Unilitix.reset()                      // clear identity, start fresh anonymous session
```

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
