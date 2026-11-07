# Performance

What the Unilitix Android SDK costs your app, how it is measured, and the
budgets CI enforces. Version 1.11.0.

## Budgets

Measured by the instrumentation benchmarks in
`unilitix-sdk/src/androidTest/java/io/unilitix/sdk/benchmark/` and enforced in
CI (`.github/workflows/ci.yml`, job `emulator`, API 24 and API 34 x86_64
emulators, budgets × 2 for shared runners) and on real devices nightly
(`.github/workflows/firebase-test-lab.yml`, budgets × 1.5). Each benchmark
prints `UNILITIX_BENCH name=… value=… budget=…`; CI copies those lines into the
job summary.

| Metric | Budget (device) | What is measured |
|---|---|---|
| `init_main_thread_ms` | 30 ms | Time `Unilitix.init` blocks the calling (main) thread: config, crash handler, lifecycle registrations, starting the background init thread. |
| `init_ready_ms` | 1500 ms | From `init` to the first session started: preferences, Keystore-backed encrypted preferences, Room database open and migration, native library load, previous-crash scan. All off the main thread; calls made meanwhile are buffered. |
| `track_caller_p50_us` / `track_caller_p95_us` | 30 µs / 250 µs | Cost of `trackEvent` on the calling thread (it only hands the call to the SDK's thread). |
| `track_persist_1000_ms` | 3000 ms | 1000 `trackEvent` calls normalised, serialised and written to the queue database. |
| `screenshot_main_thread_p50_ms` / `_p95_ms` | 8 ms / 16 ms | Main-thread part of one screenshot on a ~180-view screen: mask rectangles and starting the copy. |
| `screenshot_total_p50_ms` | 120 ms | Request to encoded WebP frame (PixelCopy or scaled draw, masks, encode), off the main thread except the part above. |
| `screenshot_bytes` | 150 KB | Encoded frame at the default 480 px width, quality 30. |

Run them locally on a connected device or emulator:

```
./gradlew :unilitix-sdk:connectedDebugAndroidTest
# slower device: scale every budget
./gradlew :unilitix-sdk:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.unilitixBudgetScale=2
adb logcat -d | grep UNILITIX_BENCH
```

## Where the work happens

| Thread | Work |
|---|---|
| Main (caller) | `init`: config, JVM crash handler, Activity/process lifecycle registrations. Capture: tap hit-testing, snapshot tree walk (at most once per `snapshotIntervalMs`, ≥ 1 s), screenshot mask rectangles. Public API calls only enqueue. |
| `unilitix-init` | Once: preferences, encrypted preferences (Keystore), Room open and migration, 1.10 queue conversion, previous native crash / ANR / JVM crash records, `libunilitix-native.so` load (API 21–29), engine start. |
| `unilitix-engine` | Sessions, identity, consent, property normalisation, the event queue (Room), upload decisions. One thread, calls in order. |
| OkHttp | Uploads (gzip), with a 40 s call timeout. |
| `unilitix-screenshot` | PixelCopy results (API 26+), mask painting, WebP encoding. |
| `unilitix-replay` | Screenshot and snapshot storage and uploads. |
| `unilitix-timer`, `unilitix-perf` | The flush interval; CPU sampling every 5 s **in the foreground only**. |
| `unilitix-anr-watchdog` | Pings the main thread every second **in the foreground only**. |

## Memory

- Screenshots are never full-resolution ARGB bitmaps: PixelCopy (API 26+) copies
  the window straight into a bitmap at `screenshotMaxWidth` (480 px: ~1.8 MB for
  a 480×960 frame), or below API 26 the view tree is drawn into one through a
  scaled canvas. One frame in flight at a time. An `OutOfMemoryError` while
  capturing turns screenshots off for the rest of the process.
- The event queue, screenshots and snapshots live on disk (Room), not in memory.
  The pre-init buffer holds at most 200 calls.

## Background behaviour

When the app goes to the background the SDK uploads what is queued, stops CPU
sampling, the battery receiver, frame-drop tracking and the ANR watchdog, and
schedules one WorkManager job (network constraint) as a safety net. Nothing
wakes the device periodically while the app is in the background, apart from
the flush interval timer while the process is alive.

## Battery and data

- Uploads are batched (`flushBatchSize`, `flushIntervalSeconds`) and gzipped;
  nothing is attempted offline or during a backoff window.
- Screenshots wait for Wi-Fi by default (`uploadScreenshotsOnWifiOnly`).
- Session replay is sampled per session (`sampleRate`); unsampled sessions take
  no screenshots or snapshots at all.
