# Lenswake Architecture

## Status

```text
Project: Lenswake
Document: docs/ARCHITECTURE.md
Status: Living Architecture / Foundation Implemented
Primary target: Pixel 8 Pro
Primary OS: Android 17
Primary camera app: Google Pixel Camera
Camera package: com.google.android.GoogleCamera
Root required: No
```

This document defines the intended architecture of Lenswake.

It describes:

- system boundaries;
- Android components;
- domain model;
- scheduling;
- Pixel Camera launch;
- Accessibility automation;
- privileged fallback;
- automation state machines;
- persistence;
- recovery;
- diagnostics;
- testing;
- failure handling;
- compatibility strategy.

The document should evolve alongside implementation.

## Implemented baseline

The current implementation uses these concrete module boundaries:

```text
:app
    Compose presentation, explicit ApplicationGraph, exact-alarm foreground-service handoff,
    secure-camera resolution, AccessibilityService, Android adapters

:automation
    platform-neutral START/STOP convergence engines, selector matching,
    bounded operation-specific retries and timeouts, fail-closed recording ownership

:core
    schedules, ExecutionSession, profiles, failures, readiness,
    repository and scheduler contracts

:data
    Room v3 database, internal entities/DAOs, domain mappings,
    atomic session-transition/event persistence and immutable environment snapshots
```

The implemented graph deliberately fails closed when the target environment has no compatible
profile, Accessibility is disconnected, the selector result is ambiguous, or no verified wake path
exists. Physical reliability claims remain tied to the exact artifact and environment recorded in
`docs/research/pixel-8-pro-baseline-2026-08-09.md`; architecture alone is never acceptance proof.

---

# 1. Architecture Goals

Lenswake is an unattended automation system.

The architecture must therefore optimize for:

1. deterministic execution;
2. explicit state;
3. process-death tolerance;
4. state reconstruction;
5. reliable start and stop timing;
6. UI verification;
7. compatibility detection;
8. bounded recovery;
9. local observability;
10. minimal privilege.

The application is not a camera implementation.

Its central invariant is:

```text
Lenswake controls Pixel Camera.
Pixel Camera owns the camera.
```

Lenswake must never silently replace the native Pixel Camera capture pipeline with CameraX, Camera2, MediaRecorder, or a Lenswake-owned recording implementation.

---

# 2. System Context

Lenswake interacts with four major external systems:

```text
┌──────────────────────────────────────────────────────────┐
│                         Android                           │
│                                                          │
│  AlarmManager                                            │
│  PackageManager                                          │
│  PowerManager                                            │
│  KeyguardManager                                         │
│  AccessibilityManager                                    │
│  Battery / Storage / Thermal APIs                        │
│                                                          │
└───────────────┬──────────────────────────────┬───────────┘
                │                              │
                │                              │
                ▼                              ▼
┌─────────────────────────┐       ┌─────────────────────────┐
│        Lenswake         │       │      Pixel Camera       │
│                         │──────▶│                         │
│ scheduler               │       │ native camera pipeline  │
│ state machine           │◀──────│ accessibility-visible UI│
│ diagnostics             │       │ recording lifecycle     │
│ profiles                │       │ media output             │
└────────────┬────────────┘       └─────────────────────────┘
             │
             ▼
┌─────────────────────────┐
│ Optional Privileged     │
│ Bridge                  │
│                         │
│ Shizuku / compatible    │
│ shell-level operations  │
└─────────────────────────┘
```

The user configures Lenswake.

Lenswake schedules future work.

At execution time it wakes the device, launches Pixel Camera, drives the Pixel Camera UI, verifies success, then exits.

Pixel Camera continues performing the actual capture.

---

# 3. Primary Runtime Scenario

The baseline scenario is:

```text
Device:
Pixel 8 Pro

State:
screen off
keyguard locked

Schedule:
START 05:30
STOP 07:30

Capture:
Video → Time Lapse → 120×
```

Runtime:

```text
05:30 exact alarm
        ↓
private system-exempted AutomationExecutionService
        ↓
persist transport checkpoint
        ↓
load persisted schedule/session
        ↓
validate execution is still relevant
        ↓
capture environment snapshot
        ↓
wake device
        ↓
launch secure Pixel Camera
        ↓
wait for Pixel Camera
        ↓
inspect current camera state
        ↓
converge to Video
        ↓
converge to Time Lapse
        ↓
converge to rear main lens
        ↓
converge to 120×
        ↓
persist Record dispatch checkpoint
        ↓
press Record
        ↓
verify recording state
        ↓
persist RECORDING
        ↓
Lenswake becomes idle

07:30 exact alarm
        ↓
private system-exempted AutomationExecutionService
        ↓
persist transport checkpoint and STOP delivery
        ↓
load persisted active session
        ↓
inspect current device/camera state
        ↓
wake if required
        ↓
restore/locate Pixel Camera UI
        ↓
verify recording expected/active
        ↓
press Stop
        ↓
verify non-recording state
        ↓
persist COMPLETED
```

---

# 4. Architectural Layers

Lenswake uses four conceptual layers.

## 4.1 Presentation

Responsibilities:

- schedule editor;
- schedule list;
- automation profile status;
- diagnostics;
- rehearsal;
- setup;
- execution history.

Technology:

```text
Jetpack Compose
ViewModel
StateFlow
```

Presentation does not directly call:

- `AlarmManager`;
- Accessibility APIs;
- Shizuku;
- Room DAOs;
- shell commands;
- Pixel Camera intents.

---

## 4.2 Application

Responsibilities:

- use-case orchestration;
- create/update/cancel schedule;
- run preflight;
- trigger rehearsal;
- execute START workflow;
- execute STOP workflow;
- reconcile recovery state.

Examples:

```text
CreateScheduleUseCase
UpdateScheduleUseCase
CancelScheduleUseCase

RunPreflightUseCase
RunRehearsalUseCase

HandleStartTriggerUseCase
HandleStopTriggerUseCase

RestoreSchedulesAfterBootUseCase
```

The application layer coordinates domain services and infrastructure abstractions.

---

## 4.3 Domain

Contains platform-independent semantics:

```text
RecordingSchedule
ExecutionSession
CaptureConfiguration
AutomationProfile
PixelCameraState
AutomationState
AutomationFailure
PreflightReport
RetryPolicy
```

Domain must not know about:

```text
AccessibilityNodeInfo
PendingIntent
BroadcastReceiver
ShizukuBinder
Compose
Room Entity
```

---

## 4.4 Infrastructure

Implements Android-specific interfaces.

Examples:

```text
AlarmManagerRecordingScheduler
AndroidDeviceStateProvider
AndroidPixelCameraLauncher
LenswakeAccessibilityService
AccessibilityPixelCameraInspector
AccessibilityUiDriver
ShizukuPrivilegedBridge
RoomScheduleRepository
RoomSessionRepository
```

---

# 5. Component Diagram

```text
                          ┌──────────────────────┐
                          │      Compose UI      │
                          └──────────┬───────────┘
                                     │
                                     ▼
                          ┌──────────────────────┐
                          │      ViewModels      │
                          └──────────┬───────────┘
                                     │
                                     ▼
              ┌─────────────────────────────────────────┐
              │             Application Layer           │
              │                                         │
              │ ScheduleManager                         │
              │ PreflightCoordinator                    │
              │ AutomationCoordinator                   │
              │ RehearsalCoordinator                    │
              └───────────────┬─────────────────────────┘
                              │
              ┌───────────────┼───────────────────────┐
              │               │                       │
              ▼               ▼                       ▼
     ┌────────────────┐ ┌───────────────┐   ┌──────────────────┐
     │ Schedule Repo  │ │ Session Repo  │   │ Profile Repo     │
     └───────┬────────┘ └──────┬────────┘   └────────┬─────────┘
             │                 │                     │
             ▼                 ▼                     ▼
         ┌────────────────────────────────────────────────┐
         │                     Room                       │
         └────────────────────────────────────────────────┘

                              │
                              ▼
                  ┌─────────────────────────┐
                  │ Automation State Machine│
                  └────────────┬────────────┘
                               │
         ┌─────────────────────┼────────────────────────┐
         │                     │                        │
         ▼                     ▼                        ▼
┌────────────────┐   ┌────────────────────┐   ┌────────────────────┐
│ Pixel Camera   │   │ Accessibility     │   │ Privileged Bridge  │
│ Launcher       │   │ Automation        │   │                    │
└────────────────┘   └─────────┬──────────┘   └──────────┬─────────┘
                               │                         │
                               └────────────┬────────────┘
                                            ▼
                                 ┌─────────────────────┐
                                 │    Pixel Camera     │
                                 └─────────────────────┘
```

---

# 6. Android Runtime Components

Lenswake should use a deliberately small set of Android components.

## 6.1 MainActivity

Responsibilities:

- render Compose UI;
- user configuration;
- diagnostics;
- permission/setup flows;
- rehearsal entry point.

Must not execute unattended automation directly.

---

## 6.2 START Alarm Delivery

The exact START alarm targets `AutomationExecutionService` directly through a foreground-service
`PendingIntent`; there is no intermediate START broadcast receiver.

Responsibilities:

```text
persist transport trigger
        ↓
load and validate schedule/session
        ↓
delegate to application-level START handler
```

It must remain thin.

It must not contain Pixel Camera navigation logic.

---

## 6.3 STOP Alarm Delivery

The exact STOP alarm uses the same durable service path and an independently persisted trigger.

Responsibilities:

```text
persist transport trigger
        ↓
persist STOP delivery and validate current session
        ↓
delegate to STOP handler
```

---

## 6.4 AlarmRecoveryReceiver and AlarmRecoveryService

Triggered after reboot, clock/timezone changes, package replacement, or exact-alarm access changes.

Responsibilities:

```text
schedule a persisted recovery job
        ↓
load pending schedules
        ↓
restore future START alarms
        ↓
restore future STOP alarms if needed
        ↓
re-arm retained transport-journal entries
        ↓
invalidate runtime capabilities that no longer hold
```

It must not launch Pixel Camera.

`AlarmRecoveryService` is an expedited, persisted `JobService`, not a foreground service. Recovery
therefore remains eligible to inspect and invalidate durable alarm state after exact-alarm access
is lost. Only `AutomationExecutionService`, whose starts are delivered by accepted exact alarms,
uses `systemExempted`. The recovery job has its own 30-second work deadline. If Android stops it
before completion, the service requeues it through the persisted, two-attempt recovery coordinator.

---

## 6.5 LenswakeAccessibilityService

Responsibilities:

- observe Pixel Camera accessibility events;
- expose current Pixel Camera tree snapshot;
- provide semantic element search;
- perform `ACTION_CLICK`;
- perform `dispatchGesture()`;
- report relevant UI state transitions.

It should be scoped to:

```text
com.google.android.GoogleCamera
```

where possible.

---

## 6.6 Wake Gateway and Bounded Execution Service

Every automation exact alarm remains service-bound with an immutable
`PendingIntent.getForegroundService()`. START, schedule STOP, rehearsal STOP, delivery retry, and
journal rearm all target `AutomationExecutionService`, so exact-alarm delivery and durable journal
persistence do not depend on a background Activity launch.

When the engine observes a non-interactive display, `AndroidDeviceWakeController` posts a bounded,
high-importance alarm-category notification with an immutable full-screen intent. The private,
no-history `AlarmWakeGatewayActivity` is the full-screen target. It calls
`setShowWhenLocked(true)` and `setTurnScreenOn(true)`, but never dismisses the keyguard, acquires a
screen wake lock, accepts automation payloads, or owns durable work. The notification is silent,
times out at the system level, and is also cancelled after the finite wake check.

`USE_FULL_SCREEN_INTENT`, notification permission, app notification state, full-screen-intent
special access, and HIGH channel importance are all fail-closed readiness checks. The automation
engine still requires the independent `PowerManager.isInteractive` postcondition; posting the
notification or dispatching its pending intent is not treated as proof that the display woke.

```text
RTC_WAKEUP alarm
        ↓
AutomationExecutionService
        ↓ durable journal + domain revalidation
DeviceWakeController
        ↓ alarm-category full-screen notification
AlarmWakeGatewayActivity
        ↓ interactive postcondition
START or STOP automation
```

`AutomationExecutionService` is private, declares the `systemExempted` foreground-service type
permitted by exact-alarm access, starts foreground immediately, serializes triggers, and enforces a
finite per-trigger deadline. A private transport journal restores all accepted triggers after
process recreation; Room and the coordinator still revalidate the domain intent and remain the
source of truth.

The coordinator returns explicit `Accepted`, `TerminalRejected`, or `Retryable` outcomes. Retry is
never inferred from whether a `Throwable` happens to exist. Service start acceptance and work
completion share one synchronized lifecycle gate, so completion of older work cannot stop a newly
accepted trigger. `AlarmRecoveryService` is an independent, persisted, bounded job that restores
future schedules and re-arms journal transport; it never invokes Pixel Camera or the automation
engine.

Delivery retry and recovery retry are both bounded and persisted before requeue. Exhaustion,
missing exact-alarm capability, journal-update failure, or scheduling failure creates a durable
transport-failure marker while retaining the original delivery journal for a later recovery
opportunity. STOP markers explicitly warn that Pixel Camera may still be recording. A high-priority
notification is attempted, but it is not authoritative because Android 17 requires a runtime
`POST_NOTIFICATIONS` grant; permission setup and in-app marker presentation remain product work.

The service is used only for bounded START/STOP workflows. Structural availability proves that the
private gateway and all required notification capabilities are currently enabled; screen-off,
locked-keyguard, Doze, and secure Pixel Camera launch remain physical Pixel 8 Pro acceptance gates.

Lenswake should not maintain a permanent service during multi-hour Pixel Camera recording.

---

# 7. Scheduling Architecture

## 7.1 Alarm Model

Each schedule produces two independently registered alarms:

```text
START
STOP
```

Never derive STOP only from runtime duration.

Example:

```text
Schedule ID:
2f41...

START:
2026-09-12T05:30:00+04:00

STOP:
2026-09-12T07:30:00+04:00
```

---

## 7.2 Alarm Identity

PendingIntent identity must be deterministic.

Conceptual URI:

```text
lenswake://schedule/{scheduleId}/start
lenswake://schedule/{scheduleId}/stop
```

This prevents accidental collisions.

---

## 7.3 Scheduler Interface

```kotlin
interface RecordingScheduler {

    suspend fun scheduleStart(
        schedule: RecordingSchedule,
    ): Result<Unit>

    suspend fun scheduleStop(
        schedule: RecordingSchedule,
    ): Result<Unit>

    suspend fun cancel(
        scheduleId: ScheduleId,
    ): Result<Unit>

    suspend fun restoreAll(): Result<Unit>
}
```

Infrastructure implementation:

```text
AlarmManagerRecordingScheduler
```

---

## 7.4 Exact Alarm Behavior

For exact user-defined times, use exact wake-up alarms.

The implementation must handle:

- permission unavailable;
- exact alarms disabled;
- rescheduling;
- device reboot;
- time change;
- timezone change;
- schedule edit;
- schedule cancellation.

---

# 8. Schedule Domain Model

```kotlin
data class RecordingSchedule(
    val id: ScheduleId,

    val name: String,

    val startAt: Instant,
    val stopAt: Instant,

    val zoneId: String,

    val capture: CaptureConfiguration,

    val profileId: ProfileId,

    val enabled: Boolean,

    val createdAt: Instant,
    val updatedAt: Instant,
)
```

---

## 8.1 Capture Configuration

```kotlin
data class CaptureConfiguration(
    val mode: CaptureMode,
    val timeLapseSpeed: TimeLapseSpeed?,
    val lens: LensSelection?,
    val zoom: Float?,
)
```

Possible initial values:

```kotlin
enum class CaptureMode {
    VIDEO,
    TIME_LAPSE,
    NIGHT_SIGHT_TIME_LAPSE,
}
```

```kotlin
enum class TimeLapseSpeed {
    AUTO,
    X5,
    X10,
    X30,
    X120,
}
```

The actual supported set must be profile/device-dependent.

---

# 9. Session Model

A schedule describes user intent.

A session describes one execution attempt.

```kotlin
data class ExecutionSession(
    val id: SessionId,
    val scheduleId: ScheduleId,

    val expectedStartAt: Instant,
    val expectedStopAt: Instant,

    val status: SessionStatus,

    val currentAutomationState: AutomationStateName?,

    val startedAt: Instant?,
    val recordingVerifiedAt: Instant?,
    val stoppedAt: Instant?,

    val environmentSnapshotId: EnvironmentSnapshotId?,

    val failure: AutomationFailure?,
)
```

Possible statuses:

```kotlin
enum class SessionStatus {
    PENDING,
    STARTING,
    RECORDING,
    STOPPING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
```

---

# 10. START State Machine

The START workflow should be represented explicitly.

```text
START_TRIGGERED
       ↓
VALIDATING_SESSION
       ↓
CAPTURING_ENVIRONMENT
       ↓
CHECKING_PREREQUISITES
       ↓
WAKING_DEVICE
       ↓
LAUNCHING_SECURE_CAMERA
       ↓
WAITING_FOR_PIXEL_CAMERA
       ↓
INSPECTING_CAMERA_STATE
       ↓
CONVERGING_TO_VIDEO
       ↓
VERIFYING_VIDEO
       ↓
CONVERGING_TO_TIME_LAPSE
       ↓
VERIFYING_TIME_LAPSE
       ↓
SELECTING_REAR_MAIN_LENS
       ↓
VERIFYING_REAR_MAIN_LENS
       ↓
CONVERGING_TO_SPEED
       ↓
VERIFYING_SPEED
       ↓
CLOSING_TIME_LAPSE_SPEED_CONTROL
       ↓
VERIFYING_TIME_LAPSE_SPEED_CLOSED
       ↓
STARTING_RECORDING
       ↓
VERIFYING_RECORDING
       ↓
RECORDING
```

Failure at any stage may transition to:

```text
RETRYING
FAILED
```

---

# 11. START Sequence Diagram

```text
AlarmManager      Service     Coordinator     Device      PixelCamera     Accessibility
    │               │              │             │             │               │
    │ START         │              │             │             │               │
    ├──────────────▶│              │             │             │               │
    │               ├─────────────▶│             │             │               │
    │               │              │ load state  │             │               │
    │               │              │────────────▶│             │               │
    │               │              │             │             │               │
    │               │              │ wake        │             │               │
    │               │              ├────────────▶│             │               │
    │               │              │             │             │               │
    │               │              │ launch secure camera      │               │
    │               │              ├──────────────────────────▶│               │
    │               │              │             │             │               │
    │               │              │ await Pixel Camera        │               │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ inspect current state     │               │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ click/select Video        │               │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ verify Video              │               │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ select Time Lapse         │               │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ select rear main lens     │               │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ verify rear main lens     │               │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ select speed              │               │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ persist dispatch checkpoint              │
    │               │              │             │             │               │
    │               │              │ Record                     │              │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ verify recording          │               │
    │               │              ├──────────────────────────────────────────▶│
    │               │              │             │             │               │
    │               │              │ persist RECORDING         │               │
```

---

# 12. Desired-State Navigation

Automation should converge toward a desired semantic state.

Bad:

```text
tap Video
tap Time Lapse
tap 120×
```

Better:

```text
inspect current state
        ↓
already Video?
        ├── yes
        │
        └── no → switch to Video
                 ↓
verify

already Time Lapse?
        ├── yes
        │
        └── no → select Time Lapse
                 ↓
verify

already 120×?
        ├── yes
        │
        └── no → select 120×
                 ↓
verify
```

This improves:

- retry safety;
- recovery;
- idempotency;
- compatibility with unexpected initial states.

---

# 13. Pixel Camera Semantic State

Raw UI nodes should be translated into a semantic model.

```kotlin
sealed interface PixelCameraState {

    data object NotRunning : PixelCameraState

    data object Unknown : PixelCameraState

    data class Photo(
        val activeLens: LensSelection?,
    ) : PixelCameraState

    data class Video(
        val subMode: VideoSubMode?,
        val recording: Boolean,
    ) : PixelCameraState

    data class TimeLapse(
        val speed: TimeLapseSpeed?,
        val recording: Boolean,
    ) : PixelCameraState

    data class NightSight(
        val timeLapseAvailable: Boolean,
        val recording: Boolean,
    ) : PixelCameraState
}
```

Exact values should be derived from observed Pixel Camera behavior.

---

# 14. Pixel Camera Inspector

Interface:

```kotlin
interface PixelCameraInspector {

    suspend fun isPixelCameraForeground(): Boolean

    suspend fun currentState(): PixelCameraState

    suspend fun findTarget(
        action: AutomationAction,
        profile: PixelCameraProfile,
    ): UiMatch?
}
```

Initial implementation:

```text
AccessibilityPixelCameraInspector
```

The inspector must not mutate UI.

---

# 15. UI Driver

Mutation belongs to a separate abstraction.

```kotlin
interface UiDriver {

    suspend fun perform(
        action: AutomationAction,
        target: UiMatch,
    ): UiActionResult
}
```

Interaction priority:

```text
AccessibilityNode.ACTION_CLICK
        ↓ fallback
Accessibility dispatchGesture(node bounds)
        ↓ fallback
Accessibility dispatchGesture(profile bounds)
        ↓ fallback
PrivilegedBridge input
```

---

# 16. UI Match Model

```kotlin
data class UiMatch(
    val target: AutomationAction,

    val selectorId: String,

    val confidence: Float,

    val bounds: NormalizedBounds?,

    val interactionCapabilities: Set<InteractionCapability>,

    val matchedMetadata: UiMetadata,
)
```

---

# 17. Selector Architecture

Selectors should live in automation profiles, not hardcoded throughout business logic.

Example:

```kotlin
data class UiSelector(
    val resourceIds: Set<String>,
    val contentDescriptions: Set<String>,
    val texts: Set<String>,
    val expectedClasses: Set<String>,
    val expectedRegion: NormalizedBounds?,
    val requireClickable: Boolean?,
    val requireSelected: Boolean?,
)
```

Selectors may be composed.

---

# 18. Selector Scoring

A selector engine should support weighted matching.

Conceptual example:

```text
resource ID exact              +100
contentDescription exact        +60
text exact                      +30
class expected                  +20
clickable expected              +10
selected expected               +15
expected screen region          +10
wrong package                 -1000
not visible                    -100
```

Do not treat these values as stable API.

The important architectural property is that match confidence exists and is inspectable.

---

# 19. Accessibility Event Architecture

The Accessibility Service should produce a narrow event stream.

Example internal events:

```text
PixelCameraWindowChanged
PixelCameraContentChanged
PixelCameraNodeTreeUpdated
PixelCameraForegroundChanged
```

The automation engine may combine:

```text
event-driven observation
+
bounded polling
```

because some Pixel Camera UI transitions may not produce sufficiently reliable accessibility events.

---

# 20. Accessibility Tree Snapshot

Infrastructure model:

```kotlin
data class AccessibilityTreeSnapshot(
    val timestamp: Instant,
    val packageName: String,
    val root: AccessibilityNodeSnapshot?,
)
```

Snapshot representation should strip unnecessary sensitive content before persistence.

Normal production flow should not persist full trees.

Debug mode may optionally export sanitized snapshots.

---

# 21. Automation Profiles

Pixel Camera UI is not a public automation contract.

Therefore Lenswake uses compatibility profiles.

```kotlin
data class PixelCameraProfile(
    val id: ProfileId,

    val environment: PixelCameraEnvironment,

    val selectorSchemaVersion: Int,

    val targets: Map<AutomationAction, UiSelectorSet>,

    val fallbackGestures: Map<AutomationAction, GestureProfile>,

    val compatibility: ProfileCompatibility,

    val verifiedAt: Instant?,
)
```

---

# 22. Environment Identity

```kotlin
data class PixelCameraEnvironment(
    val deviceManufacturer: String,
    val deviceModel: String,

    val androidSdk: Int,
    val androidBuildFingerprint: String?,

    val cameraPackage: String,
    val cameraVersionCode: Long,

    val localeTag: String,

    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val densityDpi: Int,
)
```

Environment identity should be captured both:

- at profile calibration;
- at automation execution.

---

# 23. Profile Compatibility

```kotlin
enum class ProfileCompatibility {
    VERIFIED,
    PROBABLY_COMPATIBLE,
    NEEDS_REHEARSAL,
    INCOMPATIBLE,
}
```

Example policy:

```text
same Pixel Camera version
same device
same locale
same display config
        ↓
VERIFIED

new Pixel Camera version
same device/locale/display
        ↓
NEEDS_REHEARSAL

different device generation
        ↓
INCOMPATIBLE by default
```

Unattended execution accepts only a profile whose stored status is `VERIFIED`, whose
`verifiedAt` is present, whose selector schema equals `PixelCameraSelectorSchema.CURRENT_VERSION`,
and whose current runtime environment still evaluates to `VERIFIED`. A fingerprint-only drift is
`PROBABLY_COMPATIBLE` and must go through rehearsal rather than scheduled execution.

---

# 24. Calibration Architecture

Calibration is an environment-learning workflow.

Possible sequence:

```text
Start calibration
      ↓
launch Pixel Camera
      ↓
user manually performs:
Video
Time Lapse
120×
Record
Stop
      ↓
Accessibility service observes nodes/events
      ↓
Lenswake stores candidate selectors
      ↓
candidate profile generated
      ↓
rehearsal validates profile
      ↓
profile becomes VERIFIED
```

Calibration and verification are separate concepts.

A profile should not become trusted simply because nodes were recorded once.

---

# 25. Rehearsal Architecture

Rehearsal uses the same automation engine as production.

Do not create a separate shortcut implementation.

Input:

```kotlin
data class RehearsalRequest(
    val capture: CaptureConfiguration,
    val duration: Duration,
    val profileId: ProfileId,
)
```

Flow:

```text
preflight
  ↓
START automation
  ↓
verify RECORDING
  ↓
short bounded delay
  ↓
STOP automation
  ↓
verify stopped
  ↓
store rehearsal result
```

---

# 26. STOP State Machine

STOP requires its own state machine.

```text
STOP_TRIGGERED
      ↓
VALIDATING_ACTIVE_SESSION
      ↓
CAPTURING_ENVIRONMENT
      ↓
INSPECTING_DEVICE
      ↓
WAKING_IF_REQUIRED
      ↓
LOCATING_PIXEL_CAMERA
      ↓
RESTORING_CAMERA_UI_IF_REQUIRED
      ↓
INSPECTING_RECORDING_STATE
      ↓
STOPPING_RECORDING
      ↓
VERIFYING_STOPPED
      ↓
COMPLETED
```

Possible alternate outcomes:

```text
ALREADY_STOPPED
FAILED
RECOVERY_REQUIRED
```

---

# 27. STOP Sequence Diagram

```text
AlarmManager     Receiver     Coordinator      Device      PixelCamera     Accessibility
    │               │              │              │             │               │
    │ STOP          │              │              │             │               │
    ├──────────────▶│              │              │             │               │
    │               ├─────────────▶│              │             │               │
    │               │              │ load session │             │               │
    │               │              │              │             │               │
    │               │              │ inspect device              │              │
    │               │              ├─────────────▶│             │               │
    │               │              │              │             │               │
    │               │              │ wake if needed             │               │
    │               │              ├─────────────▶│             │               │
    │               │              │              │             │               │
    │               │              │ inspect Pixel Camera                       │
    │               │              ├───────────────────────────────────────────▶│
    │               │              │              │             │               │
    │               │              │ restore UI if needed                       │
    │               │              ├───────────────────────────▶│               │
    │               │              │              │             │               │
    │               │              │ locate STOP                                │
    │               │              ├───────────────────────────────────────────▶│
    │               │              │              │             │               │
    │               │              │ press STOP                                 │
    │               │              ├───────────────────────────────────────────▶│
    │               │              │              │             │               │
    │               │              │ verify stopped                            │
    │               │              ├───────────────────────────────────────────▶│
    │               │              │              │             │               │
    │               │              │ persist COMPLETED                          │
```

---

# 28. Recording Verification

START success requires evidence.

Possible signals:

```text
Stop control exists
record control disappeared
recording timer exists
recording timer advances
recording-specific accessibility state exists
```

Verification strategy should allow multiple signals.

Conceptual model:

```kotlin
data class RecordingEvidence(
    val stopControlVisible: Boolean,
    val recordControlVisible: Boolean?,
    val recordingTimerVisible: Boolean,
    val recordingTimerAdvanced: Boolean,
)
```

A verification policy maps evidence to confidence.

---

# 29. Stop Verification

STOP success similarly requires evidence.

Potential signals:

```text
Stop control disappeared
Record control reappeared
recording timer stopped/disappeared
Pixel Camera returns to idle Time Lapse state
```

Do not mark `COMPLETED` solely because a Stop interaction was dispatched.

---

# 30. Device State Provider

```kotlin
interface DeviceStateProvider {

    suspend fun snapshot(): DeviceState

    suspend fun isInteractive(): Boolean

    suspend fun isKeyguardLocked(): Boolean

    suspend fun foregroundPackage(): String?
}
```

Example snapshot:

```kotlin
data class DeviceState(
    val interactive: Boolean,
    val keyguardLocked: Boolean,
    val foregroundPackage: String?,
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalStatus: ThermalStatus,
    val availableStorageBytes: Long,
)
```

---

# 31. Pixel Camera Launcher

```kotlin
interface PixelCameraLauncher {

    suspend fun resolveSecureCamera(): CameraLaunchTarget?

    suspend fun launchSecureCamera(): Result<Unit>

    suspend fun isPixelCameraInstalled(): Boolean
}
```

Implementation should prefer dynamic activity resolution.

Do not hardcode an internal Pixel Camera Activity unless runtime discovery is impossible.

---

# 32. Secure Launch Strategy

Preferred:

```text
wake device
      ↓
secure camera Intent
      ↓
resolve activity
      ↓
verify target package == Pixel Camera
      ↓
launch
```

Desired outcome:

```text
keyguard remains locked
Pixel Camera visible
```

Unlocking the device is not part of Lenswake's architecture.

---

# 33. Wake Strategy

Wake behavior should be abstracted.

```kotlin
interface DeviceWakeController {
    suspend fun wake(): Result<Unit>
}
```

Possible implementation order:

```text
public Android-supported mechanism
        ↓ fallback
privileged bridge
```

The implementation must be empirically verified on Android 17 / Pixel 8 Pro.

---

# 34. Privileged Bridge

Shizuku is infrastructure, not architecture.

Interface:

```kotlin
interface PrivilegedBridge {

    suspend fun availability(): PrivilegedAvailability

    suspend fun wakeDevice(): Result<Unit>

    suspend fun launchActivity(
        request: ActivityLaunchRequest,
    ): Result<Unit>

    suspend fun tap(
        point: PixelPoint,
    ): Result<Unit>

    suspend fun swipe(
        gesture: PixelSwipe,
    ): Result<Unit>
}
```

Implementations may include:

```text
ShizukuPrivilegedBridge
NoOpPrivilegedBridge
```

Potential future compatible backends may be added without changing domain logic.

---

# 35. Privileged Capability Detection

Availability is not Boolean.

```kotlin
sealed interface PrivilegedAvailability {

    data object Available : PrivilegedAvailability

    data object NotInstalled : PrivilegedAvailability

    data object ServiceNotRunning : PrivilegedAvailability

    data object PermissionMissing : PrivilegedAvailability

    data class Broken(
        val reason: String,
    ) : PrivilegedAvailability
}
```

Run harmless capability probes when possible.

---

# 36. Fallback Policy

Automation interaction strategy:

```text
semantic Accessibility
        ↓
node-bounds Accessibility gesture
        ↓
profile gesture
        ↓
privileged input
        ↓
explicit failure
```

Fallback must be logged.

A production execution should record whether it required:

```text
ACCESSIBILITY_ACTION
ACCESSIBILITY_GESTURE
PROFILE_COORDINATE
PRIVILEGED_INPUT
```

---

# 37. Recovery Model

Unexpected state must not cause arbitrary clicks.

Recovery algorithm:

```text
inspect semantic camera state
        ↓
known state?
        ├── yes → converge to desired state
        │
        └── no
             ↓
        detect known dialogs/errors
             ↓
        recover if explicitly supported
             ↓
        otherwise FAIL
```

---

# 38. Known Recoverable Conditions

Examples that may eventually become explicit recovery strategies:

```text
Pixel Camera opened in Photo
Pixel Camera opened in Video
Pixel Camera already in Time Lapse
Pixel Camera already recording
mode drawer currently open
speed selector currently open
screen asleep at STOP
Pixel Camera not foreground
```

---

# 39. Unknown State Safety

When confidence is insufficient:

```text
DO NOT TAP RANDOMLY
```

Collect safe diagnostics and terminate execution.

This is preferable to accidentally:

- switching lenses;
- changing exposure;
- leaving Time Lapse;
- opening settings;
- stopping an unrelated recording;
- dismissing unknown dialogs.

---

# 40. Failure Taxonomy

```kotlin
enum class AutomationFailureCode {

    EXACT_ALARM_UNAVAILABLE,

    ACCESSIBILITY_DISABLED,

    PIXEL_CAMERA_NOT_INSTALLED,
    PIXEL_CAMERA_RESOLUTION_FAILED,
    PIXEL_CAMERA_LAUNCH_FAILED,
    PIXEL_CAMERA_NOT_FOREGROUND,

    PROFILE_NOT_FOUND,
    PROFILE_INCOMPATIBLE,
    PROFILE_REQUIRES_REHEARSAL,

    PRIVILEGED_BRIDGE_UNAVAILABLE,

    WAKE_FAILED,

    CAMERA_STATE_UNKNOWN,

    VIDEO_MODE_NOT_FOUND,
    VIDEO_MODE_NOT_VERIFIED,

    TIME_LAPSE_MODE_NOT_FOUND,
    TIME_LAPSE_MODE_NOT_VERIFIED,

    TIME_LAPSE_SPEED_NOT_FOUND,
    TIME_LAPSE_SPEED_NOT_VERIFIED,

    RECORD_CONTROL_NOT_FOUND,
    RECORD_ACTION_FAILED,
    RECORDING_NOT_CONFIRMED,

    STOP_CONTROL_NOT_FOUND,
    STOP_ACTION_FAILED,
    STOP_NOT_CONFIRMED,

    UNEXPECTED_CAMERA_DIALOG,

    AUTOMATION_TIMEOUT,

    SESSION_STATE_CONFLICT,

    AUTOMATION_CANCELLED,

    UNKNOWN,
}
```

---

# 41. Retry Architecture

Retries are operation-specific.

```kotlin
data class RetryPolicy(
    val maxAttempts: Int,
    val initialDelay: Duration,
    val maxDelay: Duration,
    val multiplier: Double,
)
```

Potential policies:

```text
camera launch:
longer timeout
few retries

selector discovery:
short polling
more attempts

interaction:
small retry count

verification:
bounded observation window
```

---

# 42. Timeout Architecture

Centralize timeouts.

```kotlin
data class AutomationTimeouts(
    val wake: Duration,
    val cameraLaunch: Duration,
    val cameraStateDetection: Duration,
    val selectorDiscovery: Duration,
    val modeVerification: Duration,
    val recordingVerification: Duration,
    val stopVerification: Duration,
    val privilegedOperation: Duration,
)
```

No external operation may wait indefinitely.

---

# 43. Persistence

Recommended:

```text
Room
```

Primary tables:

```text
schedules
sessions
automation_profiles
environment_snapshots
execution_events
rehearsal_results
```

---

# 44. Schedule Persistence

Possible Room entity:

```text
ScheduleEntity

id
name
start_epoch_ms
stop_epoch_ms
zone_id
capture_mode
timelapse_speed
profile_id
enabled
created_at
updated_at
```

Domain mapping must remain separate from persistence representation.

---

# 45. Session Persistence

Session persistence exists for recovery.

Store enough information to answer:

```text
Was a recording expected to be active?
Which profile was used?
When should it stop?
Did START verification succeed?
Was STOP executed?
Why did it fail?
```

---

# 46. Execution Events

Store compact structured events.

Example:

```kotlin
data class AutomationEvent(
    val id: EventId,
    val sessionId: SessionId,

    val timestamp: Instant,

    val state: AutomationStateName,
    val operation: AutomationOperation?,

    val outcome: AutomationOutcome,

    val interactionMethod: InteractionMethod?,

    val durationMs: Long?,

    val metadata: Map<String, String>,
)
```

Metadata should be bounded.

Do not store arbitrary Accessibility contents.

---

# 47. Environment Snapshot

Every automation execution should capture:

```text
Lenswake version
device manufacturer/model
Android SDK
build fingerprint
Pixel Camera package/version
locale
screen dimensions
density
keyguard state
interactive state
foreground package
Accessibility enabled
privileged bridge status
battery percentage
charging status
thermal state
available storage
```

This snapshot is immutable for the session.

---

# 48. Preflight Architecture

Preflight is an explicit domain concept.

```kotlin
data class PreflightReport(
    val checks: List<PreflightCheck>,
)
```

```kotlin
data class PreflightCheck(
    val type: PreflightCheckType,
    val severity: PreflightSeverity,
    val result: PreflightResult,
    val message: String,
)
```

Severity:

```text
INFO
WARNING
BLOCKING
```

---

# 49. Preflight Checks

Initial checks:

```text
exact alarms available
Pixel Camera installed
secure camera resolves
Accessibility enabled
profile exists
profile compatible
rehearsal current
privileged fallback availability
battery threshold
charging requirement
storage threshold
```

---

# 50. Readiness State

Do not expose only `ready: Boolean`.

Suggested:

```kotlin
sealed interface ScheduleReadiness {

    data object Ready : ScheduleReadiness

    data class ReadyWithWarnings(
        val warnings: List<PreflightCheck>,
    ) : ScheduleReadiness

    data class Blocked(
        val blockers: List<PreflightCheck>,
    ) : ScheduleReadiness
}
```

---

# 51. Process Death

Lenswake must assume process death is normal.

Correct architecture:

```text
START alarm
    ↓
process created
    ↓
automation START
    ↓
recording verified
    ↓
state persisted
    ↓
process may die

Pixel Camera continues independently

STOP alarm
    ↓
process created again
    ↓
session reconstructed
    ↓
STOP automation
```

No multi-hour Lenswake process must be required.

---

# 52. Reconciliation

On application startup or alarm execution:

```text
read persisted session
      ↓
read current wall clock
      ↓
inspect Pixel Camera if necessary
      ↓
compare expected vs actual state
      ↓
reconcile
```

Examples:

```text
expected RECORDING
but Pixel Camera not recording
→ mark unexpected termination / failure

expected PENDING
but recording detected
→ investigate session conflict

expected COMPLETED
→ ignore stale alarm
```

---

# 53. Alarm Race Handling

Possible race:

```text
START scheduled 05:30
STOP scheduled 05:31

START still verifying at 05:31
STOP alarm arrives
```

Automation coordinator must serialize conflicting operations for the same schedule/session.

Possible mechanism:

```text
per-session Mutex
+
persisted session state validation
```

Do not rely on in-memory locking alone for correctness.

---

# 54. Concurrency Model

One authoritative automation execution per session.

Recommended:

```text
AutomationCoordinator
      ↓
SessionExecutionLock
      ↓
START or STOP state machine
```

Avoid multiple independent coroutines mutating session state.

---

# 55. Clock Abstraction

Domain code should depend on:

```kotlin
interface LenswakeClock {
    fun now(): Instant
}
```

Infrastructure:

```text
SystemLenswakeClock
```

Tests:

```text
FakeLenswakeClock
```

---

# 56. Logging Architecture

Use structured logging.

Potential categories:

```text
schedule
alarm
preflight
automation
pixelcamera
accessibility
selector
privileged
recovery
session
```

Example:

```text
automation.record.start_dispatched
automation.record.start_verified

automation.selector.match
automation.selector.low_confidence

automation.stop.started
automation.stop.verified
```

---

# 57. Diagnostics Export

A session report may be exportable as JSON or text.

Example:

```json
{
  "sessionId": "...",
  "scheduleId": "...",
  "expectedStart": "...",
  "actualAlarm": "...",
  "cameraVisibleAt": "...",
  "recordVerifiedAt": "...",
  "expectedStop": "...",
  "stopVerifiedAt": "...",
  "result": "SUCCESS"
}
```

Do not include unrelated screen contents.

---

# 58. Observability Metrics

Useful metrics:

```text
alarm_start_latency
camera_launch_latency
mode_selection_latency
record_verification_latency
recording_start_deviation

alarm_stop_latency
stop_verification_latency
recording_stop_deviation

selector_retry_count
interaction_fallback_count
privileged_fallback_count
session_success_rate
```

All remain local.

---

# 59. Battery Architecture

Lenswake should not hold resources during the entire Pixel Camera recording.

After recording verification:

```text
persist RECORDING
release temporary resources
exit automation execution
```

Pixel Camera remains responsible for the capture.

---

# 60. Storage Architecture

Lenswake does not own media output.

Pixel Camera writes media using its own storage behavior.

The architecture must not assume a direct output URI exists.

A future feature may correlate a session with media using:

```text
capture time window
MediaStore query
Pixel Camera ownership/path metadata
```

but this is outside the initial core.

---

# 61. Thermal Architecture

Lenswake may inspect thermal state before START.

It must not bypass Pixel thermal protections.

Potential preflight:

```text
NORMAL → proceed
MODERATE → proceed
SEVERE → warning
CRITICAL → optional block
```

Exact policy should remain user-configurable.

---

# 62. UI Architecture

Suggested screens:

```text
Schedules
Schedule Editor
Profiles
Profile Detail
Rehearsal
Diagnostics
Execution History
Settings
```

Navigation should remain shallow.

The automation engine is more important than visual complexity.

---

# 63. Schedule UI State

Example:

```kotlin
data class ScheduleUiState(
    val schedules: List<ScheduleItemUiModel>,
    val setupState: SetupState,
    val activeSession: ActiveSessionUiModel?,
)
```

The UI should surface readiness prominently.

---

# 64. Profile UI

Profile view should show:

```text
Device
Android version
Pixel Camera version
Locale
Display configuration
Compatibility state
Last successful rehearsal
Fallback usage
```

---

# 65. Diagnostics UI

Show:

```text
Exact alarms
Accessibility
Pixel Camera
secure launch
profile compatibility
Shizuku / privileged bridge
battery
storage
thermal
latest automation failure
```

---

# 66. Dependency Injection

The baseline uses a small explicit process-wide `ApplicationGraph`. This keeps availability and
failure wiring visible and avoids a code-generation framework before the graph requires one.

Important principle:

```text
interfaces in domain/application
implementations in infrastructure
wiring at application boundary
```

---

# 67. Recommended Technology Stack

```text
Language:
Kotlin 2.3.10

Build:
AGP 9.2.1
Gradle 9.4.1
Java bytecode 17
compileSdk / targetSdk 37

UI:
Jetpack Compose BOM 2026.06.01
Material 3
Navigation 3

Async:
Kotlin Coroutines
Flow

Persistence:
Room 2.8.4

Scheduling:
AlarmManager

Automation:
AccessibilityService

Privileged operations:
optional PrivilegedBridge; Shizuku adapter not implemented yet

Serialization:
kotlinx.serialization

Testing:
JUnit 6 for JVM tests
kotlinx-coroutines-test
Android instrumentation
physical Pixel validation
```

---

# 68. Project Structure

```text
:app
:automation
:core
:data
```

Conceptual feature and infrastructure boundaries remain packages until a separate Gradle module
provides a concrete dependency or build-time benefit.

---

# 69. Gradle Modularization Strategy

Do not immediately create a module per package.

Suggested early structure:

```text
:app
:core
:automation
:data
```

Split further only when justified.

Possible mature layout:

```text
:app

:core:model
:core:common

:data:database

:domain:schedule
:domain:automation

:platform:accessibility
:platform:pixelcamera
:platform:shizuku
:platform:scheduler

:feature:schedules
:feature:profiles
:feature:diagnostics
```

---

# 70. Testing Architecture

Three main levels.

## 70.1 Unit Tests

Target:

```text
state machine
retry policy
selector scoring
profile compatibility
schedule validation
preflight
failure mapping
reconciliation
```

All should run without Android device dependencies where possible.

---

## 70.2 Android Integration Tests

Target:

```text
Room
AlarmManager wrapper
receivers
boot restoration
permission state
package resolution
device state adapters
```

---

## 70.3 Physical Pixel Tests

Mandatory for:

```text
secure Pixel Camera launch
locked-screen behavior
Pixel Camera accessibility hierarchy
semantic selectors
Time Lapse navigation
record verification
stop verification
Shizuku fallback
Doze
process death
Android 17 behavior
```

---

# 71. Test Doubles

Interfaces should permit:

```text
FakePixelCameraInspector
FakeUiDriver
FakePrivilegedBridge
FakeDeviceStateProvider
FakeRecordingScheduler
FakeLenswakeClock
```

This enables deterministic state-machine tests.

---

# 72. State Machine Testing Example

```text
Given:
Pixel Camera state = Video

Desired:
Time Lapse 120×

When:
START automation runs

Expected actions:
skip Video transition
select Time Lapse
verify
select 120×
verify
record
verify
```

---

# 73. Recovery Testing Example

```text
Given:
Record action dispatched

But:
Stop control never appears

Then:
retry recording verification
bounded attempts
do not mark RECORDING
persist RECORDING_NOT_CONFIRMED
```

---

# 74. Device Research Workflow

Before implementing undocumented behavior:

```text
observe
reproduce
record device environment
run ADB inspection
document result
implement behind abstraction
add fallback
test physically
```

Research files should live under:

```text
docs/research/
```

---

# 75. Recommended Research Documents

```text
docs/research/
    secure-camera-launch.md
    pixel-camera-activities.md
    pixel-camera-accessibility.md
    timelapse-navigation.md
    timelapse-recording-state.md
    recording-stop-state.md
    android-17-lockscreen.md
    android-17-shizuku.md
```

---

# 76. Architectural Decision Records

Recommended ADRs:

```text
docs/adr/
    0001-native-pixel-camera-only.md
    0002-accessibility-first-automation.md
    0003-exact-start-and-stop-alarms.md
    0004-shizuku-as-optional-fallback.md
    0005-profile-based-pixel-camera-compatibility.md
    0006-process-death-tolerant-session-model.md
```

---

# 77. Compatibility Strategy

Lenswake does not claim generic compatibility.

Compatibility matrix should eventually look like:

```text
Pixel 8 Pro
Android 17
Pixel Camera version X
Status: VERIFIED

Pixel 8 Pro
Android 17
Pixel Camera version Y
Status: NEEDS_REHEARSAL

Pixel 9 Pro
Android 17
Status: EXPERIMENTAL

Samsung
Status: UNSUPPORTED
```

---

# 78. Pixel Camera Update Handling

On app startup or before scheduling:

```text
read installed Pixel Camera version
        ↓
compare to profile
        ↓
same?
    ├── yes → continue
    └── no
          ↓
    mark NEEDS_REHEARSAL
```

A schedule depending on an unverified profile should clearly communicate risk.

---

# 79. Locale Handling

Text-based selectors are inherently locale-sensitive.

Profiles should include:

```text
localeTag
```

Preferred selector priority should minimize dependence on visible text.

If the locale changes:

```text
profile compatibility must be re-evaluated
```

---

# 80. Display Configuration Handling

Coordinate fallback depends on:

```text
resolution
density
orientation
display scaling
```

Use normalized coordinates.

Do not assume default Pixel display resolution forever.

---

# 81. Orientation

Initial supported mode should preferably be fixed/documented.

If automation is validated only in portrait:

```text
profile.orientation = PORTRAIT
```

If device orientation differs at execution time, preflight may block or require a separate profile.

---

# 82. Security Boundaries

Lenswake must not:

```text
unlock the user's keyguard
disable authentication
inject PIN codes
hide Android privacy indicators
disable Pixel Camera security UI
bypass camera permissions
```

The architecture is intentionally compatible with a locked device.

---

# 83. Network Boundary

Core Lenswake requires no network backend.

Default:

```text
INTERNET permission not required
```

If future optional functionality introduces network access, it should remain separate from automation correctness.

---

# 84. Failure Notification

On unattended failure:

```text
persist structured failure
        ↓
show user notification
        ↓
include schedule/session
        ↓
include failed stage
        ↓
include actionable reason
```

Example:

```text
Lenswake failed to start "Sunrise"

Stage:
Select Time Lapse

Reason:
No compatible Time Lapse control found.

Pixel Camera was updated since the last rehearsal.
```

---

# 85. Session Success Criteria

A session is successful only when both:

```text
START verified
AND
STOP verified
```

Potential session result:

```kotlin
enum class SessionResult {
    SUCCESS,
    START_FAILED,
    RECORDING_INTERRUPTED,
    STOP_FAILED,
    CANCELLED,
}
```

---

# 86. START Timing Metrics

Track:

```text
expectedStartAt
alarmDeliveredAt
automationStartedAt
cameraVisibleAt
recordActionAt
recordVerifiedAt
```

Key metric:

```text
recordingStartDeviation =
recordVerifiedAt - expectedStartAt
```

---

# 87. STOP Timing Metrics

Track:

```text
expectedStopAt
stopAlarmDeliveredAt
stopActionAt
stopVerifiedAt
```

Key metric:

```text
recordingStopDeviation =
stopVerifiedAt - expectedStopAt
```

---

# 88. Reliability Goal

For one explicitly verified environment:

```text
Pixel 8 Pro
Android 17
specific Pixel Camera version
verified profile
```

target eventually:

```text
START success ≥ 99%
STOP success ≥ 99%
```

This is an engineering target, not a promise.

Measure it through repeated device tests.

---

# 89. Initial Implementation Milestones

## Milestone 1

Establish Android application and diagnostics.

---

## Milestone 2

Resolve and manually launch secure Pixel Camera.

---

## Milestone 3

Build Accessibility explorer.

---

## Milestone 4

Infer Pixel Camera semantic state.

---

## Milestone 5

Automate:

```text
Photo → Video
```

with verification.

---

## Milestone 6

Automate:

```text
Video → Time Lapse
```

with verification.

---

## Milestone 7

Automate speed selection.

---

## Milestone 8

Automate Record and verify recording.

---

## Milestone 9

Automate Stop and verify stopped.

---

## Milestone 10

Build full explicit START/STOP state machines.

---

## Milestone 11

Support screen-off + locked execution.

---

## Milestone 12

Add exact alarm scheduling.

---

## Milestone 13

Add persistent sessions and reboot restoration.

---

## Milestone 14

Add privileged fallback.

---

## Milestone 15

Add calibration profiles.

---

## Milestone 16

Add rehearsal and compatibility validation.

---

## Milestone 17

Reliability hardening.

---

# 90. Architectural Invariants

The following rules should remain true unless changed by an explicit architecture decision.

### Invariant 1

```text
Pixel Camera owns capture.
```

### Invariant 2

```text
START and STOP are independently scheduled.
```

### Invariant 3

```text
Action dispatch is not equivalent to success.
```

### Invariant 4

```text
Automation uses explicit semantic states.
```

### Invariant 5

```text
Pixel Camera UI compatibility is profile/version aware.
```

### Invariant 6

```text
Process death must not invalidate future STOP.
```

### Invariant 7

```text
Shizuku is optional infrastructure, not domain architecture.
```

### Invariant 8

```text
Unknown UI state must fail safely.
```

### Invariant 9

```text
Unattended execution must be observable after the fact.
```

### Invariant 10

```text
Root access is not required.
```

---

# 91. End-State Architecture

The intended mature runtime looks like this:

```text
                           ┌─────────────────────┐
                           │       User          │
                           │ creates schedule    │
                           └─────────┬───────────┘
                                     │
                                     ▼
                           ┌─────────────────────┐
                           │ ScheduleRepository  │
                           └─────────┬───────────┘
                                     │
                                     ▼
                           ┌─────────────────────┐
                           │   AlarmManager      │
                           │ START + STOP        │
                           └─────────┬───────────┘
                                     │
                         ┌───────────┴───────────┐
                         │                       │
                         ▼                       ▼
                ┌─────────────────┐     ┌─────────────────┐
                │ START Workflow  │     │ STOP Workflow   │
                └────────┬────────┘     └────────┬────────┘
                         │                       │
                         └───────────┬───────────┘
                                     ▼
                          ┌──────────────────────┐
                          │ AutomationCoordinator│
                          └──────────┬───────────┘
                                     │
                                     ▼
                          ┌──────────────────────┐
                          │ Semantic State       │
                          │ Machine              │
                          └──────────┬───────────┘
                                     │
                    ┌────────────────┼────────────────┐
                    │                │                │
                    ▼                ▼                ▼
           ┌────────────────┐ ┌──────────────┐ ┌────────────────┐
           │ Pixel Camera   │ │ Accessibility│ │ Privileged     │
           │ Launcher       │ │ Driver       │ │ Bridge         │
           └────────┬───────┘ └──────┬───────┘ └────────┬───────┘
                    │                │                   │
                    └────────────────┼───────────────────┘
                                     ▼
                          ┌──────────────────────┐
                          │ Google Pixel Camera  │
                          │ native capture stack │
                          └──────────┬───────────┘
                                     │
                                     ▼
                          ┌──────────────────────┐
                          │ Native Pixel media   │
                          │ output               │
                          └──────────────────────┘
```

---

# 92. Architectural Philosophy

Lenswake should remain small at the product surface but rigorous internally.

The user-facing concept is simple:

```text
Wake.
Open Pixel Camera.
Configure.
Record.
Stop.
```

The engineering problem is not simple because the operation must happen:

- unattended;
- at an exact time;
- while locked;
- through a third-party UI;
- across Android lifecycle events;
- across process death;
- across Pixel Camera updates.

The architecture therefore deliberately favors:

```text
semantic state
over macros

verification
over assumptions

runtime discovery
over hardcoded internals

persistence
over process memory

bounded retries
over infinite waiting

explicit failure
over silent misbehavior

profiles
over pretending undocumented UI is stable

physical-device evidence
over theoretical compatibility
```

That is the core architectural direction of Lenswake.
