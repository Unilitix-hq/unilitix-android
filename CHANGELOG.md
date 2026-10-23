# Changelog

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
