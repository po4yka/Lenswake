# Connected audit run on the target Pixel 8 Pro — 2026-09-07

## Purpose

First `:app` module connected instrumentation run on the connected certification target, plus a
fresh `:data` connected run and a full host gate, as part of a deep audit of current `main`.
It records what was observed, including five failed tests and one instrumentation process crash,
with root causes established from source. It does not certify any rehearsal, profile, or release.

## Environment

```text
Device:              Google Pixel 8 Pro (husky)
Android:             17 / SDK 37
Build:               CP2A.260805.005 / 15828068:user/release-keys
Display:             1008 × 2244 @ 360 dpi
Locale:              en-US-u-fw-mon-mu-celsius
Pixel Camera:        10.4.117.936816638.14
Camera versionCode:  69481630
Lenswake commit:     fd6b64c (main), application version 0.1.0 debug build
Local APK SHA-256:   9491cf4bb7f58333fa7241183b24025892909017c6ef187435aa089203e07ed5
                     (app-debug.apk rebuilt by the connected task at 18:48 local; this is the
                     artifact Gradle installed. The connected run rebuilt outputs, so this hash
                     supersedes the pre-run 18:11 artifact 7777f17a16831950590a4dfb9005c4a0fc3e08
                     ff8d74011937ef0616c985891a.)
ADB selection:       explicit per-command `-s` / `ANDROID_SERIAL` pin; serial not recorded here
```

This is the August environment already admitted by
[pixel-8-pro-august-2026-build-admission.md](pixel-8-pro-august-2026-build-admission.md).

## Initial device state

Lenswake not installed; `enabled_accessibility_services` contained only Bitwarden; no Lenswake
alarms registered; display awake. Pixel Camera variants as above were installed and untouched.

## Actions

1. `./gradlew check assembleDebug --console=plain` on `main@fd6b64c` — **BUILD SUCCESSFUL**
   (incremental; all task inputs unchanged since the last green run).
2. `ANDROID_SERIAL=<pinned> ./gradlew :data:connectedDebugAndroidTest
   :app:connectedDebugAndroidTest --rerun-tasks --console=plain` — **BUILD FAILED in 39 s**.

## Results

### :data connected — 30 tests finished, 3 failed (27 passed)

| Test | Observed failure | Root cause |
| --- | --- | --- |
| `RoomRepositoriesTest.latestSuccessfulRehearsalRequiresAllVerificationProofs` | `AssertionError: expected:<ExecutionSession(...)> but was:<null>` | Stale fixture. The DAO predicate (matched to `RehearsalVerificationPolicy.hasDurableReceipt` in `2286072`) requires `record_action_at`, `stop_action_at`, and `rehearsal_verified_at` to be non-null; the `rehearsalSession` fixture sets none of them, so no fixture row qualifies and the query correctly returns `null`. |
| `RoomRepositoriesTest.corruptSessionAndEventRowsAreReportedWithoutTerminatingExecutionFlows` | `IllegalStateException: Check failed.` at `RoomRepositoriesTest.kt:301` | Stale fixture. The corrupt-session copy reuses the original session's `executionKey`; `execution_sessions` carries a UNIQUE index on `execution_key`, and `insertIgnoringConflict` (`OnConflictStrategy.IGNORE`) returns `-1`, tripping the test's own `check`. |
| `RoomRepositoriesTest.corruptScheduleRowIsReportedWithoutTerminatingScheduleFlow` | `SQLiteConstraintException: FOREIGN KEY constraint failed (code 787)` at `ScheduleDao.upsert` | Stale fixture. The test never persists the parent profile; the `schedules → automation_profiles` foreign key (present since schema v8) rejects the upsert of a schedule row whose `profile_id` has no parent. |

### :app connected — 47 of 137 tests finished, 2 failed, then the process crashed

| Test | Observed failure | Root cause |
| --- | --- | --- |
| `AlarmJournalReconcilerTest.exhaustedDeliveriesAreNotRearmedDuringRecovery` | `AssertionError: expected:<Rearmed(count=3, corruptEntries=[])> but was:<Rearmed(count=2, corruptEntries=[])>` | Fixture identity collision. `AlarmDeliveryWork.Schedule.markerId` deliberately excludes `deliveryAttempt`; the test's exhausted STOP shares the fixture schedule's marker, so `AlarmDeliveryJournal.read()` collapses both STOP rows into one winner (max attempt — the exhausted one), which the reconciler then correctly filters out. Test expectation violates the journal's winner-selection contract. |
| `AutomationExecutionServiceManifestTest.bootRecoveryReceiverRemainsSafeWithoutExactAlarmAccess` | `NullPointerException: ... BroadcastReceiver$PendingResult.finish() on a null object reference` at `AlarmReceivers.kt:184` | Test invokes `AlarmRecoveryReceiver().onReceive(...)` directly, outside framework dispatch, so `goAsync()` returns `null`; the coroutine `finally { pendingResult.finish() }` throws, the unhandled coroutine exception kills the app process, and the remaining ~90 tests never ran (`Test run failed to complete. Instrumentation run failed due to Process crashed.`). This is also a latent production fragility: `AlarmRecoveryReceiver.onReceive` does not guard against a `null` `goAsync()` result (platform type), despite the code's own "logged instead of crashing boot" intent. A real broadcast dispatch would not produce `null`, so no production crash path is reachable today; the guard is still required by the app's no-crash contract. |

The four data/fixture failures are deterministic constraint/policy mechanics and are not
device-specific; they would fail identically on any device or emulator. None of them is a Pixel
Camera, keyguard, alarm-transport, or environment-specific product defect. The ordinary connected
suites never reached Pixel Camera automation (no guarded fixture ran; the selector probe stayed
skipped as designed).

## Interpretation

1. The host gate and the connected failures are consistent: all five failing tests live only in
   androidTest sources and drifted from schema v10 / receipt-policy / journal-contract changes
   that landed after the last hosted CI instrumentation run (`8c1454e`, 2026-08-12, 121 app
   tests). Connected suites had not been run since, so the drift was invisible.
2. `AlarmReceivers.kt:184` is the single production-side finding: add a `goAsync()` null guard
   (run the recovery action synchronously or return) and rework the manifest test so it does not
   kill its own process under direct invocation.
3. A complete `:app` connected verdict is still open: 90 tests did not run because the receiver
   crash aborted the instrumentation process. A rerun after the receiver fix is required before
   any connected-evidence claim for `:app`.

## Cleanup performed and verified

- Gradle uninstalled both test and application APKs after the failed run; `pm list packages`
  shows no Lenswake package.
- `enabled_accessibility_services` unchanged (Bitwarden only).
- `dumpsys alarm` contains no Lenswake entries.
- Display state (awake) unchanged; no media was created; no schedules, permissions, or idle/battery
  overrides were introduced (the ordinary suites do not arm fixtures).

## Follow-up: fixes and verification (same day)

All findings were fixed on `main`:

1. `fix(app): guard the null goAsync result in AlarmRecoveryReceiver` — production null guard,
   an explicit rationale for the receiver's deliberate process-wide bootstrap scope, and a
   harness comment explaining the direct-invocation semantics of the manifest test.
2. `test(data): qualify rehearsal fixtures for the durable-receipt predicate` — the
   `rehearsalSession` fixture gained `stopActionAt`, and the qualifying fixtures now carry
   record/stop action timestamps and the rehearsal receipt.
3. `test(data): satisfy the schedule foreign key in the corrupt-row fixture`.
4. `test(data): give the corrupt session row a distinct execution key`.
5. `test(app): give the exhausted journal delivery its own schedule identity`.
6. `build(data): remove the unused datastore-preferences dependency`.

Verification on the same device and build. One intermediate rerun produced 34
`No compose hierarchies found` failures across the Compose UI classes; the device had dozed
mid-run and the keyguard was engaged, so the activity could not resume — an environment state,
not a product defect. After waking, unlocking (operator action; keyguard is never bypassed), and
holding the display awake with `svc power stayon usb` (reverted afterwards):

- Host gate `./gradlew check assembleDebug` — green.
- `:data:connectedDebugAndroidTest` — 30/30, 0 failed.
- `:app:connectedDebugAndroidTest` — 142 tests finished, 0 failed, 5 opt-in physical fixtures
  skipped. First complete green pass of the ordinary `:app` connected suite on this target.

## Conclusion

Current `main@fd6b64c` on the admitted August Pixel 8 Pro environment: full host gate green;
`:data` connected 27/30; `:app` connected incomplete (45 passed / 2 failed of 47 executed, process
crash aborted the rest). Four failing tests need fixture corrections, one test needs a harness
rework, and `AlarmRecoveryReceiver` needs a `goAsync()` null guard. No current-HEAD physical
reliability claim is made or changed by this record.
