# Behaviour cases skipped by unilitix-android

Cases of the shared behaviour fixtures (`behaviour/`, contract 1.4.0) that
`BehaviourFixturesTest` does not run on Android, each with the reason, for
the API team to review. A row is `| \`case.id\` | reason |`; the test fails
if a row names a case that does not exist for Android. Delete the row with
the fix.

| Case | Why it cannot run on Android |
|---|---|

None: every case whose `platforms` include `android` runs (1.11.0).
