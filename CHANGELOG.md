# Changelog

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
