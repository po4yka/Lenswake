# Lenswake architecture

**Status:** current implementation architecture

**Reviewed against:** `main@f33d15c` on 2026-08-12

**Target:** Pixel 8 Pro · Android 17 · Google Pixel Camera

This document describes the checked-in system, not a proposed generic camera platform. Dated
physical evidence is tracked separately in [STATUS.md](STATUS.md); implementation truth remains in
source, manifests, Gradle configuration, Room schemas, and tests.

## Invariants

1. Pixel Camera (`com.google.android.GoogleCamera`) owns the camera and media output.
2. Lenswake preserves keyguard and uses the secure camera path; it never injects credentials.
3. START and STOP are separate persisted workflows with independent exact alarms.
4. Dispatch is not success. Every external action requires an observed postcondition.
5. A current session completes only after recording, stopped, and saved-media evidence are verified.
6. Process death is normal; volatile process state cannot be the only owner of STOP responsibility.
7. Unknown, ambiguous, changed, or low-confidence Pixel Camera UI fails closed.
8. Accessibility is package-scoped and local history is not backed up or transferred device-to-device.
9. Lenswake stays dormant while Pixel Camera records; there is no multi-hour keeper service.
10. Physical reliability claims belong to one identified commit, APK, device, OS build, Camera variant,
    profile schema, initial state, and scenario.

## Modules and dependency direction

```text
                 ┌──────────────┐
                 │     :app     │
                 │ Android + UI │
                 └───┬───┬───┬──┘
                     │   │   │
          ┌──────────┘   │   └──────────┐
          ▼              ▼              ▼
  ┌─────────────┐  ┌──────────┐  ┌───────────┐
  │ :automation │  │  :core   │  │   :data   │
  │ state flow  │─▶│  domain  │◀─│ Room impl │
  └─────────────┘  └──────────┘  └───────────┘
```

| Module | Responsibility |
| --- | --- |
| `:core` | Platform-neutral schedules, capture contracts, profiles, sessions, failures, readiness, clocks, and repository/scheduler contracts |
| `:automation` | Platform-neutral START/STOP convergence engine, automation ports, selector matching, retries, timeouts, and postcondition logic |
| `:data` | Room v7 database, explicit migrations, schema exports, entities, mappings, repositories, session CAS, and environment history |
| `:app` | Compose/Navigation 3 UI, application workflows and graph, exact alarms, Android services, Accessibility, secure launch, wake, MediaStore, and preflight adapters |

`:core` and `:automation` do not import Android framework or Compose types. Room types stay internal
to `:data`; the UI observes domain-friendly state through the explicit `ApplicationGraph` composition
root rather than calling DAOs, AlarmManager, or Accessibility directly.

## Runtime components

| Component | Scope and responsibility |
| --- | --- |
| `MainActivity` | Compose host, Navigation 3, permission/remediation launchers, diagnostics share chooser |
| `PixelCameraAccessibilityService` | Package-scoped Pixel Camera events, bounded snapshots, semantic actions and gestures |
| `AutomationExecutionService` | Private bounded foreground-service handoff for scheduled and rehearsal alarm work |
| `AlarmWakeGatewayActivity` | Private, no-history wake-only full-screen-intent target; turns screen on without dismissing keyguard |
| `AlarmRecoveryReceiver` | Direct-boot-safe trigger intake for boot, unlock, time/timezone, package replacement, and exact-alarm access changes |
| `AlarmRecoveryService` | `JobService` that restores Room-backed alarms and journals after credential-protected state is available |

START, STOP, retry, rearm, and rehearsal STOP alarms are service-bound. The wake gateway is not an
alarm transport and never performs camera automation; it exists only for the `DEVICE_WAKE`
postcondition on modern background-launch restrictions.

## Persistence domains

Lenswake has three deliberately different persistence domains.

### Room v7: domain state

Five entities are stored in credential-protected `lenswake.db`:

- `schedules`;
- `automation_profiles`;
- `execution_sessions`;
- `execution_events`;
- `environment_snapshots`.

Rehearsals are execution sessions with `SessionKind.REHEARSAL`, not a separate table. Migrations
1→7 are explicit and schemas 1–7 are committed. Repository mappings isolate corrupt profile rows so
one bad entry is surfaced without terminating the whole profiles Flow.

Execution updates use a monotonically increasing revision and compare-and-set application. This
prevents concurrent START, STOP, recovery, and UI work from silently overwriting a later transition.

### Credential-protected alarm delivery journal

The private SharedPreferences journal is written before alarm work is accepted by the coordinator.
It preserves delivery intent across service/process failure and is reconciled against Room, which
remains the source of schedule/session truth. Corrupt journal records are surfaced and retained for
typed handling rather than silently discarded.

### Device-protected recovery state

Direct-boot receiver work cannot open the credential-protected Room database. Device-protected
checkpoints and durable transport-failure markers record why restoration is pending. A bounded
`JobService` retries after user unlock or another relevant system trigger and exposes terminal
transport incidents in Diagnostics.

All three domains are excluded from Android cloud backup and device transfer.

## Schedule transaction and alarm identity

Every schedule holds a stable ID, wall-time zone, absolute START/STOP instants, exact capture
configuration, profile ID, enabled state, and timestamps. The scheduler derives stable distinct
identities for START and STOP.

Create/update/enable follows a compensating transaction:

1. validate the schedule and capture/profile readiness;
2. persist a disabled staging form;
3. stage STOP, then START, so partial success cannot leave START without STOP;
4. persist the intended enabled schedule;
5. commit staged alarms;
6. on failure, cancel staged work and restore the prior persisted/alarm state.

Delete/disable likewise respect active camera ownership and cancel both identities. At delivery,
the coordinator reloads current state, rejects stale or released executions, reserves Pixel Camera
ownership, and proceeds idempotently.

## Scheduled execution

```text
exact START alarm
  → service-bound durable journal
  → validate schedule/profile/preflight and reserve camera ownership
  → create/load execution session + immutable environment snapshot
  → wake display when needed
  → dynamically resolve secure Pixel Camera
  → inspect and converge mode / lens / speed
  → capture MediaStore generation + volume-version baseline
  → persist Record write-ahead checkpoint
  → dispatch Record
  → verify recording postcondition
  → persist RECORDING and release Lenswake runtime work

exact STOP alarm
  → service-bound durable journal
  → load the owning session and preempt/join matching START work if needed
  → inspect device and Pixel Camera state
  → wake and restore secure Camera UI when needed
  → reconcile uncertain prior Stop dispatch
  → persist Stop write-ahead checkpoint
  → dispatch Stop only when safe
  → verify non-recording postcondition
  → query MediaStore after the saved baseline
  → require exactly one new, published, Pixel Camera-owned video
  → require positive size and duration
  → persist COMPLETED and release ownership
```

The MediaStore volume version must still match the baseline. No candidate, multiple candidates,
permission loss, or version drift produces a typed failure. Lenswake records evidence fields but
does not claim or expose ownership of the media URI/path.

Sessions migrated while a recording was already in flight may carry the explicit legacy
`mediaVerificationRequired=false` exception. New executions never use that exception.

## Concurrency and ownership

`AutomationExecutionService` can run unrelated delivery work independently; it is not a global
FIFO serializer. Correctness instead depends on:

- a repository-level reservation of the single Pixel Camera owner;
- session revision compare-and-set transitions;
- schedule-mutation and rehearsal mutexes at their application boundaries;
- matching STOP cancellation/join of in-flight START work;
- write-ahead Record/Stop checkpoints;
- idempotent alarm and recovery validation.

An uncertain Record or Stop call is never treated as rejected merely because the caller timed out
or died. Reconciliation inspects fresh Pixel Camera state before deciding whether to verify,
continue, retry, or fail. Explicit ownership release prevents a later START reconciliation from
reviving a reboot-interrupted or otherwise abandoned execution.

## Automation ports and interaction policy

The automation engine depends on three effective ports:

- `DeviceControlPort` for current device state and wake;
- `PixelCameraPort` for secure launch, state inspection, target actions, and typed dialogs;
- `RecordingMediaPort` for pre-Record baseline and post-STOP media evidence.

The app supplies Android implementations. Pixel Camera interaction follows this order:

1. Accessibility semantic action on a freshly resolved node;
2. Accessibility gesture using freshly resolved node bounds;
3. Accessibility gesture from a verified normalized profile fallback;
4. explicit rejection.

`PrivilegedBridge` is an architectural boundary but production currently wires
`UnavailablePrivilegedBridge`; Shizuku and privileged input are not implemented runtime steps.

### Fresh target identity

A selector match creates a semantic fingerprint from package, resource/role, text/description,
state, visibility, hierarchy context, and bounds. Immediately before action dispatch, the adapter
takes a fresh bounded snapshot and re-resolves that fingerprint. A stale node path cannot authorize
a click on a new control occupying the same hierarchy position.

### Profiles and selectors

A profile binds selectors and normalized gestures to:

- manufacturer/model;
- Android SDK and build fingerprint;
- Pixel Camera package/versionCode;
- locale;
- display dimensions and density;
- selector schema version.

Selectors are package-scoped, scored, and required to have meaningful discriminants. Ambiguous
top matches, insufficient score, environment drift, and schema drift fail closed. The current
schema is v4. The sole bundled profile exactly matches one Pixel 8 Pro environment and initially
installs as `NEEDS_REHEARSAL`.

The domain can represent Video, Time Lapse, Night Sight Time Lapse, five speeds, four lenses, and
zoom. Runtime authorization comes only from selectors, state signals, and a qualifying rehearsal.
The bundled profile currently authorizes Time Lapse 120× with the rear main lens only.

### Typed dialogs

The current profile describes duration-limit, 100 GB file-size-limit, storage-exhausted,
camera-disabled, and unknown Pixel Camera dialogs. Only the first two have an automatic `OK`
target. Recovery requires:

1. typed profile match;
2. fresh dialog-presence recheck;
3. fresh recovery-target resolution;
4. exactly one dispatch;
5. verified dialog disappearance.

Unknown, storage, policy, changed, and ambiguous dialogs are terminal for unattended automation.

## Rehearsal

Profile and schedule **Test now** use the production engine rather than a mock path. Before START,
the coordinator persists a rehearsal session and arms an independent exact, session-bound STOP
backstop. Requested recording time is ten seconds; the safety deadline also includes the bounded
START budget and margin.

A successful rehearsal must prove:

- exact profile/environment and unchanged profile definition;
- exact capture configuration;
- Record dispatch and verified recording;
- Stop dispatch and verified stopped state;
- required saved-media evidence;
- durable rehearsal verification receipt.

Only that capture/profile pair becomes eligible for an enabled schedule. If the initiating process
dies, the exact STOP backstop still converges the owned recording to a safe stopped state. Cleanup
after a failed START never fabricates successful rehearsal evidence.

## Wake and secure launch

`AndroidDeviceWakeController` first accepts an already interactive screen without requiring
full-screen-intent capability. Otherwise it requires notifications, a high-importance silent alarm
channel, and app full-screen-intent access. It posts an immutable intent to the private wake gateway,
waits a finite time for `PowerManager.isInteractive`, and cancels the notification on every terminal
path. The gateway uses `setShowWhenLocked(true)` and `setTurnScreenOn(true)` only.

`SecurePixelCameraLauncher` dynamically resolves the standard secure camera intent for the Pixel
Camera package. Activity class names are runtime observations, not production constants.

## Recovery semantics

Recovery responds to locked boot, normal boot, user unlock, time/timezone change, package
replacement, and exact-alarm access changes.

- Locked boot stores a device-protected checkpoint; it never opens Room or launches Pixel Camera.
- After unlock, future schedules and durable rehearsal STOP alarms are reconstructed.
- Overdue STOP delivery is retained and handed to reconciliation instead of deleted.
- Retry and rearm work is exact-alarm-bound and finite; loss of exact-alarm access moves recovery to
  the `JobService` path rather than starting a now-ineligible system-exempted foreground service.
- A reboot interrupts any in-flight scheduled camera ownership. Recovery terminalizes that session
  with a typed failure and explicit ownership release; it does not pretend Pixel Camera state survived.
- Ordinary process death is different: Room state, alarms, journal checkpoints, and Accessibility
  reconnection allow safe reconstruction without globally releasing a valid recording.

## Preflight and UI

The runtime preflight reports typed status, severity, message, and remediation for:

- exact alarms;
- notifications;
- video-library access;
- full-screen-intent access;
- Pixel Camera installation and secure intent resolution;
- device wake capability;
- Accessibility enabled and connected state;
- battery, charging, and storage observations;
- profile availability and exact compatibility;
- current qualifying rehearsal;
- optional privileged fallback status.

Unknown required state blocks enabling a schedule. Known low charging/storage states remain visible
warnings under current policy; battery below the configured application threshold blocks.

The current Navigation 3 surface has three top-level destinations—Schedules, Profiles, and
Diagnostics—with Setup nested from Schedules. Each top level has its own back stack and adaptive
navigation changes with window width. ViewModels expose durable `StateFlow` UI state; Compose does
not call Android services or repositories directly.

Diagnostics displays durable alarm incidents, profile persistence issues, and the ten most recent
sessions. Each session includes its ordered timeline, duration, retry/fallback counts, privileged
fallback count, selector match/confidence, attempts, interaction methods, and failures. The same
bounded view can be shared as plain text through Android's chooser; a structured full-history
archive is not implemented.

## Security and privacy boundaries

- No `CAMERA`, `RECORD_AUDIO`, or `INTERNET` permission is declared.
- Package visibility and Accessibility events are restricted to Pixel Camera.
- Pixel Camera output is queried only to verify a new external-primary video owned by its package.
- Keyguard, user credentials, privacy indicators, and thermal safeguards are never bypassed.
- `allowBackup=false` plus `dataExtractionRules` excludes every local storage domain from cloud
  backup and device transfer.
- No analytics, account, remote logging, advertising, or network dependency is present.

## Technology baseline

| Area | Version/choice |
| --- | --- |
| Language/toolchain | Kotlin 2.3.10, Java/Kotlin toolchain 17 |
| Build | Gradle 9.4.1, Android Gradle Plugin 9.2.1, KSP 2.3.11 |
| Android | minSdk 35, compileSdk/targetSdk 37 |
| UI | Jetpack Compose Material 3, Navigation 3, Lifecycle/StateFlow |
| Persistence | Room 2.8.4, database schema v7, Kotlin serialization |
| Concurrency | Kotlin coroutines 1.11.0 |
| Tests | JUnit 6 host tests, AndroidX Test/runner, Compose UI tests |
| Static analysis | Detekt on all modules; Android lint warnings-as-errors except three volatile dependency recommendations |

Exact dependency versions live in `gradle/libs.versions.toml`; do not duplicate them into agent
instructions.

## Known gaps

- interactive selector calibration and profile import/authoring;
- bundled selectors/rehearsal evidence for capture combinations beyond Time Lapse 120× rear main;
- Shizuku or another privileged provider;
- structured complete diagnostics archive;
- implemented thermal and orientation policy beyond current observable preflight data;
- current-HEAD Pixel 8 Pro acceptance, including real saved-media proof and induced typed dialogs;
- complete physical layout/IME/navigation-mode matrix on Pixel 8 Pro;
- hosted CI and managed-device matrix.

These are gaps, not implied capabilities. See [STATUS.md](STATUS.md) for the current evidence boundary.

## Canonical source map

| Contract | Canonical source |
| --- | --- |
| Modules/dependencies | `settings.gradle.kts`, module `build.gradle.kts` files |
| Versions | `gradle/libs.versions.toml`, Gradle wrapper |
| Permissions/components/privacy | `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/` |
| Domain/session/profile contracts | `core/src/main/kotlin/dev/po4yka/lenswake/core/` |
| State machine and ports | `automation/src/main/kotlin/dev/po4yka/lenswake/automation/` |
| Android workflows/adapters | `app/src/main/kotlin/dev/po4yka/lenswake/` |
| Database/migrations | `data/src/main/`, `data/schemas/` |
| Dated runtime evidence | `docs/research/` |
| Test/evidence policy | `docs/testing/PHYSICAL_PIXEL.md` |
