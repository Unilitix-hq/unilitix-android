# Changelog

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
