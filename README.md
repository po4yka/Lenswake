# Lenswake

**Scheduled automation for the native Google Pixel Camera.**

Lenswake is a personal Android automation tool for unattended photography and time-lapse recording on Google Pixel devices.

It can wake a locked phone at a scheduled time, launch the **native Pixel Camera**, navigate its UI to the requested capture mode, start recording, and stop the recording automatically at another scheduled time.

Unlike conventional camera automation apps, Lenswake does **not** implement its own camera pipeline using CameraX or Camera2. Its purpose is specifically to automate Google's stock Pixel Camera so that recordings retain Pixel Camera's native processing, capture modes, stabilization, Night Sight behavior, time-lapse implementation, and other proprietary features.

> [!WARNING]
> Lenswake is intended for **personal use on devices you own and control**.
>
> It is not designed for Google Play distribution and intentionally relies on Android capabilities such as Accessibility Services, exact alarms, and optionally Shizuku.

---

## Current implementation baseline

The repository contains a buildable Android 17 foundation split into four Gradle modules:

```text
:app        Compose UI, application wiring, alarms, Android and Accessibility adapters
:automation pure Kotlin START/STOP state machines, retries, selectors, verification
:core       domain models, repository contracts, failures, readiness, time
:data       Room database, mappings, repositories, execution history
```

Implemented now:

- independent exact START and STOP alarms with stale-trigger rejection and bounded reboot/time-change restoration;
- direct exact-alarm handoff to a bounded, restartable foreground service with a private transport journal, typed retry decisions, journal re-arming, and durable terminal failure markers;
- persisted schedules, environment-bound automation profiles, execution sessions, structured events, and immutable execution-environment snapshots;
- explicit START and STOP workflows that distinguish action dispatch from verified camera state;
- write-ahead recording ownership, operation-specific timeouts, safe reconciliation after uncertain Record dispatch, and safe STOP recovery;
- a pre-Record MediaStore generation baseline and post-STOP saved-file verification: only a newly generated, published Pixel Camera-owned external video with positive size and duration completes a session;
- profile-driven capture capabilities, selector scoring, meaningful-discriminant enforcement, ambiguity rejection,
  observable state signals, per-speed targets, and verified lens selection;
- selector-schema and environment compatibility checks that require a current, timestamped `VERIFIED` profile for unattended execution;
- dynamically resolved secure Pixel Camera launch and a package-scoped, bounded Accessibility adapter;
- create/edit/enable/disable/delete schedule workflows with profile-constrained mode, Time Lapse speed, and lens
  configuration, transactional alarm rollback, and active-camera ownership guards;
- a durable production-stack rehearsal with a session-bound exact STOP backstop and profile promotion only after verified start and stop;
- setup remediation actions, local alarm-failure diagnostics, and an honest Compose status UI that never invents readiness;
- a bounded full-screen-notification `DEVICE_WAKE` implementation that preserves keyguard and fails closed when its capability is unavailable.

Still intentionally outside the implemented baseline:

- interactive selector calibration for arbitrary Pixel Camera environments;
- diagnostics archive export;
- optional Shizuku integration;
- compatibility claims beyond the exact Pixel 8 Pro / Android 17 / Pixel Camera environment recorded in
  `docs/research/pixel-8-pro-baseline-2026-08-09.md`.

The bundled physical-device profile currently exposes only the capture combination whose selectors and postconditions
were calibrated on that exact environment. Scheduling is additionally constrained to the exact capture combination
from the profile's latest successful production rehearsal; configured selectors alone never authorize unattended use.

The debug APK can be built with:

```bash
./gradlew check assembleDebug
```

Physical-device tests are an additional gate; a successful JVM build is not Pixel Camera reliability evidence.

---

## Why Lenswake?

Android provides excellent camera APIs, but they cannot reproduce every feature of the native Pixel Camera.

In particular, Pixel Camera contains proprietary functionality such as:

- Pixel-specific image processing
- native Time Lapse modes
- Night Sight Time Lapse
- automatic time-lapse speed selection
- Pixel-specific stabilization
- Google camera tuning
- computational photography pipelines
- device-specific camera behavior

For use cases such as sunrise, sunset, clouds, city movement, construction progress, night scenes, or unattended tripod recording, it is useful to prepare a Pixel in advance and tell it:

> At 05:30, wake up, open Pixel Camera, switch to Time Lapse, select 120×, start recording, and stop at 07:30.

Lenswake exists to make this possible.

---

# Core Goals

Lenswake should be able to:

1. Schedule a Pixel Camera recording for an exact future time.
2. Wake the device even when the screen is off.
3. Operate while the device remains locked.
4. Launch the native Pixel Camera in its lock-screen-compatible mode.
5. Navigate the Pixel Camera UI automatically.
6. Select a configured capture mode.
7. Select Time Lapse when requested.
8. Select a configured time-lapse speed.
9. Start recording.
10. Verify that recording actually started.
11. Keep the recording running without requiring Lenswake to remain visible.
12. Wake the device again when necessary.
13. Stop recording at the configured time.
14. Verify that Pixel Camera stopped and that MediaStore exposes its newly published saved video.
15. Recover gracefully from unexpected Pixel Camera UI states.

Reliability is more important than speed.

Lenswake must behave as a stateful automation system rather than a sequence of blind screen taps.

---

# Primary Use Case

Example:

```text
Device
Pixel 8 Pro

Start
05:30

Stop
07:30

Camera
Rear main camera

Mode
Video → Time Lapse

Speed
120×

Device state before start
Locked
Screen off
Mounted on tripod
```

Expected execution:

```text
05:30
  ↓
Exact alarm fires
  ↓
Device wakes
  ↓
Secure Pixel Camera launches
  ↓
Lenswake identifies the Pixel Camera UI
  ↓
Video selected
  ↓
Time Lapse selected
  ↓
120× selected
  ↓
Record pressed
  ↓
Recording state verified
  ↓
Pixel Camera records normally
  ↓
07:30
  ↓
Device wakes if necessary
  ↓
Stop control located
  ↓
Stop pressed
  ↓
Stopped state verified
  ↓
Session marked successful
```

---

# Non-Goals

Lenswake is intentionally **not**:

- a CameraX application
- a Camera2 application
- a replacement camera
- a video encoder
- a custom time-lapse implementation
- a remote surveillance platform
- a cloud camera service
- a general-purpose Android UI automation framework
- a Tasker replacement
- dependent on root access

Lenswake should automate Pixel Camera instead of recreating it.

---

# Supported Platform

Initial target:

```text
Device: Google Pixel 8 Pro
Android: Android 17
Camera app: Google Pixel Camera
Root: Not required
Distribution: Personal sideloaded APK
```

The architecture should avoid unnecessarily coupling itself to Pixel 8 Pro so that other recent Pixel devices can be supported later.

Potential future targets include:

- Pixel 8
- Pixel 8 Pro
- Pixel 9 family
- Pixel 10 family
- later Pixel generations

Each device and Pixel Camera version may require calibration or verification.

---

# High-Level Architecture

Lenswake consists of four major subsystems:

```text
┌──────────────────────────────────────┐
│               Lenswake               │
│                                      │
│ Schedule / configuration / status UI │
└──────────────────┬───────────────────┘
                   │
                   ▼
          ┌─────────────────┐
          │ Scheduler Engine │
          │  AlarmManager    │
          └────────┬─────────┘
                   │
                   ▼
        ┌──────────────────────┐
        │ Automation Controller │
        │      State Machine    │
        └───────────┬──────────┘
                    │
          ┌─────────┴──────────┐
          ▼                    ▼
┌───────────────────┐  ┌───────────────────┐
│ Accessibility     │  │ Privileged Bridge │
│ Automation Driver │  │ Shizuku / fallback│
└─────────┬─────────┘  └─────────┬─────────┘
          │                       │
          └───────────┬───────────┘
                      ▼
             ┌──────────────────┐
             │   Pixel Camera   │
             │ GoogleCamera APK │
             └──────────────────┘
```

---

# Design Principle: Automate, Don't Reimplement

The central design constraint of Lenswake is:

```text
Lenswake never owns the camera.
Pixel Camera owns the camera.
Lenswake only controls Pixel Camera.
```

Therefore Lenswake should generally not require:

```text
android.permission.CAMERA
android.permission.RECORD_AUDIO
FOREGROUND_SERVICE_CAMERA
```

Pixel Camera already owns and manages those capabilities.

Lenswake instead needs the permissions and system integrations required to schedule and orchestrate another application.

---

# Automation Strategy

Lenswake uses a layered automation strategy.

## Layer 1 — Semantic Accessibility Automation

Preferred mechanism:

```text
AccessibilityService
```

Lenswake observes only the Pixel Camera UI and attempts to identify controls using semantic information exposed through the Android accessibility hierarchy.

Selector preference:

```text
1. package name
2. resource ID
3. class / role
4. content description
5. visible text
6. bounds
7. calibrated coordinates
```

Example conceptual target:

```kotlin
UiTarget(
    action = Action.START_RECORDING,
    resourceId = "...",
    contentDescription = "Record",
    fallbackBounds = ...
)
```

Semantic interaction is preferred over absolute coordinates.

---

## Layer 2 — Accessibility Gestures

Some Pixel Camera controls may not expose a usable accessibility action.

In this case Lenswake may use:

```text
AccessibilityService.dispatchGesture()
```

to emulate the corresponding touch operation.

Coordinates should preferably be based on normalized screen positions rather than raw pixels.

Example:

```text
x = 0.50 × screenWidth
y = 0.88 × screenHeight
```

instead of:

```text
x = 672
y = 2571
```

---

## Layer 3 — Privileged Input

When Accessibility cannot perform the required operation, Lenswake may use a privileged bridge backed by Shizuku or a compatible implementation.

Potential operations include equivalents of:

```bash
input keyevent ...
input tap ...
input swipe ...
am start ...
```

The application must abstract this capability behind an interface instead of coupling the codebase directly to one Shizuku implementation.

Example:

```kotlin
interface PrivilegedBridge {
    suspend fun isAvailable(): Boolean

    suspend fun wakeDevice(): Result<Unit>

    suspend fun launchSecureCamera(): Result<Unit>

    suspend fun tap(
        x: Int,
        y: Int,
    ): Result<Unit>

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Result<Unit>
}
```

Possible implementations:

```text
ShizukuPrivilegedBridge
ShellPrivilegedBridge
NoOpPrivilegedBridge
```

The rest of Lenswake should not need to know which backend is active.

---

# Secure Pixel Camera Launch

Lenswake should avoid unlocking the device.

Instead, it should attempt to launch Pixel Camera using Android's secure camera flow, equivalent to opening the camera from the lock screen.

Conceptually:

```text
Screen off
    ↓
Wake
    ↓
Keyguard remains locked
    ↓
Secure camera intent
    ↓
Pixel Camera appears over lock screen
```

The preferred action is based on:

```text
android.media.action.STILL_IMAGE_CAMERA_SECURE
```

The exact Pixel Camera activity must not be hardcoded if Android can resolve the appropriate activity dynamically.

Pixel Camera package:

```text
com.google.android.GoogleCamera
```

The launcher should verify that the resolved activity belongs to this package.

---

# Scheduling

Lenswake should use:

```text
AlarmManager
```

with exact alarms.

A recording session has at least two alarms:

```text
START_CAPTURE
STOP_CAPTURE
```

Example:

```text
05:30 START_CAPTURE
07:30 STOP_CAPTURE
```

The stop alarm should be scheduled independently at the same time the session is created.

Do not rely solely on:

```text
delay(duration)
```

inside a long-running process.

The Android process may be terminated at any time.

---

# Recording Session Model

A possible domain model:

```kotlin
data class RecordingSchedule(
    val id: UUID,

    val startAt: Instant,
    val stopAt: Instant,

    val mode: CaptureMode,
    val timeLapseSpeed: TimeLapseSpeed?,

    val lens: CameraLens?,
    val zoom: Float?,

    val automationProfileId: UUID,

    val status: ScheduleStatus,
)
```

Example capture modes:

```kotlin
enum class CaptureMode {
    VIDEO,
    TIME_LAPSE,
    NIGHT_SIGHT_TIME_LAPSE,
}
```

Example speeds:

```kotlin
enum class TimeLapseSpeed {
    AUTO,
    X5,
    X10,
    X30,
    X120,
}
```

Actual available values should be derived from Pixel Camera capabilities and calibration rather than assumed globally.

---

# Automation State Machine

Lenswake must not implement automation as:

```kotlin
delay(1000)
tap(...)
delay(500)
tap(...)
delay(500)
tap(...)
```

Such automation is too fragile.

Instead every recording session runs through an explicit state machine.

Example:

```text
SCHEDULED
    ↓
START_TRIGGERED
    ↓
WAKING_DEVICE
    ↓
LAUNCHING_CAMERA
    ↓
WAITING_FOR_CAMERA
    ↓
SELECTING_VIDEO
    ↓
VERIFYING_VIDEO
    ↓
SELECTING_TIME_LAPSE
    ↓
VERIFYING_TIME_LAPSE
    ↓
OPENING_TIME_LAPSE_SPEED_CONTROL
    ↓
VERIFYING_TIME_LAPSE_SPEED_CONTROL
    ↓
SELECTING_SPEED
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
    ↓
STOP_TRIGGERED
    ↓
PREPARING_STOP
    ↓
STOPPING_RECORDING
    ↓
VERIFYING_STOPPED
    ↓
COMPLETED
```

Any state may transition to:

```text
RETRYING
FAILED
CANCELLED
```

---

# Verification Is Mandatory

Every important action must have an observable postcondition.

For example:

```text
Action:
Press Record

Expected result:
Record control disappears or changes
AND/OR
Stop control appears
AND/OR
recording timer begins
```

Lenswake must not assume that a click succeeded merely because the gesture was dispatched.

Similarly:

```text
Action:
Press Stop

Expected result:
Stop control disappears
Record control returns
recording timer stops
```

Only then should the session be marked as successfully completed.

---

# Retry Strategy

Temporary UI failures are expected.

Examples:

- Pixel Camera takes longer to launch
- camera initialization is delayed
- animation temporarily blocks interaction
- requested control is not yet in the hierarchy
- device is under thermal load
- Pixel Camera displays an unexpected dialog

A bounded retry policy should be used.

Example:

```text
Find Video control
    ↓
not found
    ↓
wait 250 ms
    ↓
retry
    ↓
maximum 5 seconds
```

Retries must be:

- bounded
- state-aware
- logged
- cancellable

Never retry indefinitely.

---

# Recovery

Whenever possible, automation should recover from unknown states.

Instead of assuming Pixel Camera always starts in Photo mode:

```text
Inspect current state
      ↓
Already Time Lapse?
      ├─ yes → continue
      ↓ no
Already Video?
      ├─ yes → select Time Lapse
      ↓ no
Navigate to Video
```

The desired final state matters more than reproducing one exact sequence of gestures.

Automation should therefore be **idempotent where practical**.

---

# Pixel Camera Calibration

Pixel Camera is not a public automation API.

Its UI may change between:

- Pixel Camera releases
- Android versions
- Pixel generations
- locales
- screen densities
- feature rollouts
- A/B experiments

Lenswake should therefore support calibration.

## Calibration Mode

The user performs the desired flow manually:

```text
Pixel Camera
    ↓
Video
    ↓
Time Lapse
    ↓
120×
    ↓
Record
    ↓
Stop
```

Lenswake observes available accessibility metadata and stores useful selectors.

Example:

```kotlin
data class UiTarget(
    val action: AutomationAction,

    val resourceId: String?,
    val className: String?,
    val contentDescription: String?,
    val text: String?,

    val normalizedBounds: RectF?,
)
```

The result becomes a Pixel Camera automation profile.

---

# Automation Profiles

Profiles should be associated with the environment in which they were recorded.

Example:

```kotlin
data class PixelCameraProfile(
    val id: UUID,

    val packageName: String,
    val versionCode: Long,

    val deviceModel: String,
    val androidSdk: Int,

    val locale: String,

    val displayWidth: Int,
    val displayHeight: Int,
    val densityDpi: Int,

    val targets: Map<AutomationAction, UiTarget>,
)
```

Example profile identity:

```text
Device:
Pixel 8 Pro

Android:
17

Pixel Camera:
com.google.android.GoogleCamera

Pixel Camera version:
<version code>

Locale:
en-US

Display:
1344 × 2992
```

---

# Pixel Camera Updates

Lenswake should detect Pixel Camera updates.

Example:

```text
Stored profile:
versionCode = 123456

Installed Pixel Camera:
versionCode = 123812
```

The profile then becomes:

```text
NEEDS_VERIFICATION
```

Lenswake may attempt a rehearsal with the existing selectors, but unattended schedules should not silently assume compatibility after significant environment changes.

---

# Rehearsal Mode

Every schedule should support:

```text
Test now
```

A rehearsal performs the full automation immediately with a short recording duration.

Example:

```text
Wake device
    ↓
Launch Pixel Camera
    ↓
Select Video
    ↓
Select Time Lapse
    ↓
Select 120×
    ↓
Record
    ↓
Wait 10 seconds
    ↓
Stop
```

The result should contain a diagnostic trace:

```text
PASS  Device wake                    183 ms
PASS  Pixel Camera launch            741 ms
PASS  Video mode                     328 ms
PASS  Time Lapse                     411 ms
PASS  120×                           172 ms
PASS  Recording detected             687 ms
PASS  Stop detected                  294 ms

Automation profile: READY
```

A failed rehearsal should clearly identify the stage that failed.

---

# Preflight Checks

Before Lenswake accepts an unattended schedule, it should validate the environment.

Example:

```text
Preflight

✓ Pixel Camera installed
✓ supported package detected
✓ Accessibility Service enabled
✓ Exact alarms allowed
✓ automation profile available
✓ profile compatible with current camera version
✓ secure camera launch available
✓ privileged bridge available or fallback configured
✓ sufficient storage
✓ battery above configured threshold
```

Possible warnings:

```text
⚠ Shizuku unavailable
  Lenswake will use the non-privileged launch path.

⚠ Pixel Camera was updated
  Run a rehearsal before relying on unattended capture.

⚠ Battery is below 20%
  Long recording may not complete.
```

Critical failures should prevent creation of an unreliable schedule unless explicitly overridden.

---

# Accessibility Service

The Accessibility Service should be scoped as narrowly as possible.

Primary target:

```text
com.google.android.GoogleCamera
```

It should:

- observe Pixel Camera accessibility events
- inspect the active window
- locate configured controls
- perform accessibility actions
- dispatch gestures where necessary
- report UI state to the automation state machine

It should not become a generic system-wide automation engine.

Conceptual configuration:

```xml
<accessibility-service
    android:accessibilityEventTypes="typeAllMask"
    android:packageNames="com.google.android.GoogleCamera"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true" />
```

---

# Shizuku

Shizuku is an optional privileged backend for Lenswake.

It may assist with:

- waking the device
- launching activities
- executing shell-level input operations
- handling automation edge cases unavailable through regular application APIs

Lenswake must **not** make the entire domain layer depend directly on Shizuku.

Use an abstraction such as:

```text
PrivilegedBridge
```

instead.

This is particularly important because Shizuku compatibility may vary between Android releases.

For Android 17 specifically, Lenswake should treat privileged bridge availability as a runtime capability rather than an assumption.

---

# No Root Requirement

Root access is intentionally not required.

Target environment:

```text
Bootloader: may remain locked
Root: no
Magisk: no
Custom ROM: no
Stock Pixel firmware: supported
```

Optional Shizuku functionality should work through Android's Wireless Debugging model.

---

# Permissions

The exact manifest will evolve during development, but Lenswake is expected to need capabilities in the following categories.

## Exact alarms

Used for precise start and stop triggers.

```text
SCHEDULE_EXACT_ALARM
```

## Boot handling

Used to restore scheduled alarms after device restart.

```text
RECEIVE_BOOT_COMPLETED
```

## Accessibility

Declared through an:

```text
AccessibilityService
```

requiring user activation in Android Settings.

## Video media read access

Used only to verify a post-STOP, Pixel Camera-owned external video through MediaStore; Lenswake
does not use it to create, rename, delete, or otherwise own camera output.

```text
READ_MEDIA_VIDEO
READ_MEDIA_VISUAL_USER_SELECTED (Android 14+ access-state handling)
```

Unattended verification requires the full `READ_MEDIA_VIDEO` grant. The selected-media permission
lets setup identify and remediate a partial grant; partial access alone never passes preflight.

## Notifications

Used for diagnostics, session state, errors, and potentially alarm-related fallback flows.

## Shizuku

Optional Shizuku API integration.

Lenswake should avoid requesting unrelated permissions.

In particular, the main architecture does not require Lenswake itself to access the camera.

---

# Reboot Handling

`AlarmManager` schedules do not survive reboot.

Lenswake should persist schedules locally and register a boot receiver.

After reboot:

```text
BOOT_COMPLETED
      ↓
Read pending schedules
      ↓
Ignore expired schedules
      ↓
Re-register future START alarms
      ↓
Re-register future STOP alarms
      ↓
Check Shizuku availability
      ↓
Mark affected schedules if necessary
```

The boot receiver must not attempt to start Pixel Camera directly.

---

# Session Persistence

All state required for recovery should live in persistent storage.

Suggested stack:

```text
Room
```

Persist:

- schedules
- automation profiles
- session execution history
- failures
- state transitions
- environment snapshots
- Pixel Camera versions
- rehearsal results

Do not rely on singleton process state.

---

# Execution History

Every session should produce a structured execution log.

Example:

```text
Session
2026-08-09 Sunrise

Scheduled start
05:30:00

Actual alarm
05:30:00.181

Pixel Camera visible
05:30:01.024

Time Lapse selected
05:30:02.103

Recording started
05:30:02.917

Scheduled stop
07:30:00

Stop pressed
07:30:00.402

Recording stopped
07:30:00.881

Result
SUCCESS
```

This makes reliability measurable rather than subjective.

---

# Diagnostics

Diagnostics should answer:

> Why did this scheduled recording fail?

Useful diagnostic data includes:

```text
Android version
device model
Pixel Camera version
Lenswake version
Accessibility enabled
Shizuku status
screen state
keyguard state
current foreground package
automation state
matched selector
selector confidence
retry count
gesture fallback usage
timestamps
exception details
```

Do not capture camera imagery or sensitive screen contents unnecessarily.

---

# Logging

Use structured logging rather than free-form strings wherever practical.

Example:

```kotlin
AutomationEvent(
    sessionId = sessionId,
    timestamp = clock.now(),
    state = AutomationState.SELECTING_TIME_LAPSE,
    result = AutomationResult.SUCCESS,
    durationMs = 312,
)
```

Logs should be exportable for debugging.

Sensitive information should be minimized.

---

# Reliability Targets

A useful initial target:

```text
Supported device:
Pixel 8 Pro

Supported environment:
known Android 17 build
known Pixel Camera version
validated calibration profile

Goal:
≥ 99% successful scheduled starts
≥ 99% successful scheduled stops
```

Failures should never silently appear as successful executions.

The project should optimize for deterministic behavior on explicitly supported configurations rather than pretending to support every Android device.

---

# Failure Modes

Lenswake should explicitly model failures such as:

```text
EXACT_ALARM_UNAVAILABLE

ACCESSIBILITY_DISABLED

PIXEL_CAMERA_NOT_INSTALLED

PIXEL_CAMERA_VERSION_CHANGED

PRIVILEGED_BRIDGE_UNAVAILABLE

DEVICE_WAKE_FAILED

CAMERA_LAUNCH_FAILED

PIXEL_CAMERA_NOT_FOREGROUND

VIDEO_MODE_NOT_FOUND

TIME_LAPSE_MODE_NOT_FOUND

TIME_LAPSE_SPEED_NOT_FOUND

RECORD_CONTROL_NOT_FOUND

RECORDING_NOT_CONFIRMED

STOP_CONTROL_NOT_FOUND

STOP_NOT_CONFIRMED

UNEXPECTED_CAMERA_DIALOG

AUTOMATION_TIMEOUT
```

Each should result in actionable diagnostics.

---

# Safety Timeouts

Every operation must have a timeout.

Example:

```text
Wake device:            3 s
Launch Pixel Camera:    10 s
Find Video:             5 s
Find Time Lapse:        5 s
Select speed:           5 s
Verify recording:       10 s
Verify stop:            10 s
```

Exact values should be configurable during development.

No automation state may block forever.

---

# Session Watchdog

Once recording has started, Lenswake should maintain enough metadata to determine that a session is supposed to be active.

For example:

```text
expectedState = RECORDING
expectedStopAt = 07:30
```

If Lenswake becomes active again before that time, it may inspect Pixel Camera and reconcile the actual state.

The scheduled STOP alarm remains the authoritative termination trigger.

---

# Saved-file verification

Lenswake does not create or own the recording file. To verify that Pixel Camera saved the
recording, it captures and persists a MediaStore generation baseline before dispatching `Record`.
After STOP is independently verified, Lenswake queries external video media using full-library
`READ_MEDIA_VIDEO` access and accepts only an unambiguous candidate that:

- belongs to the Pixel Camera package;
- has `GENERATION_ADDED` greater than the persisted baseline;
- is published (`IS_PENDING = 0`); and
- reports a positive size and duration.

The baseline also persists the opaque MediaStore volume version; generations are compared only
while that version remains unchanged. Partial selected-video access is rejected because it cannot
guarantee visibility of a future unattended Pixel Camera output. Multiple qualifying candidates
are treated as ambiguous and fail closed.

The durable session checkpoints are `mediaBaselineGeneration`, `mediaStoreVersion`,
`mediaSavedVerifiedAt`, and `savedMediaGeneration`, so a restarted Lenswake process can retain and
resume the correlation outcome.
Structured execution evidence also records the observed generation, size, and duration. A missing
baseline, unreadable media, or no qualifying published video is an explicit failure, not a
completed recording.

Executions with a persisted Record dispatch already in flight when the Room v4→v5 migration runs
are the sole exception: their STOP is still verified and completed without inventing retroactive
media evidence, and a dedicated
`automation.record.stop_verified_media_unavailable_legacy` event records that limitation. Such a
rehearsal cannot verify or promote a profile.

This is correlation evidence only: Lenswake neither guesses a filename nor claims ownership of a
media URI, path, or Pixel Camera output.

---

# Battery Considerations

Lenswake itself should consume minimal energy while Pixel Camera records.

After successful recording start, the automation application should avoid holding unnecessary:

- wake locks
- foreground activities
- polling loops
- high-frequency timers

Pixel Camera should be allowed to manage its own recording lifecycle normally.

The screen should be allowed to turn off if Pixel Camera and Android permit recording to continue in that state.

---

# Storage Checks

Before a recording begins, Lenswake may estimate whether enough storage is available.

For long sessions this should preferably be a warning rather than an unreliable exact prediction, since final bitrate and Pixel Camera behavior are controlled by Google's application.

Example:

```text
Available storage: 18.4 GB
Planned duration: 2h
Status: OK
```

---

# Battery Checks

Schedules may optionally define:

```text
minimumBatteryPercent
requireCharging
```

Example:

```text
Start only if:
battery >= 30%

Recommended:
charging = true
```

For important unattended sessions, external power is strongly recommended.

---

# Thermal State

Long recordings can generate substantial heat.

Lenswake may record Android thermal status as part of execution diagnostics.

Possible policy:

```text
THERMAL_STATUS_SEVERE
    ↓
warn

THERMAL_STATUS_CRITICAL
    ↓
optionally abort before recording
```

Pixel Camera remains responsible for its own thermal protection behavior.

---

# User Interface

The first version of Lenswake should stay intentionally small.

Suggested navigation:

```text
Schedules
Profiles
Diagnostics
Settings
```

## Schedules

Shows:

```text
Sunrise
Tomorrow · 05:30 → 07:30
Time Lapse · 120×
READY
```

Actions:

```text
Edit
Test now
Disable
Delete
```

## Profiles

Shows Pixel Camera automation environments.

Example:

```text
Pixel 8 Pro
Android 17
Pixel Camera 10.x
en-US

Verified
Last rehearsal: PASS
```

## Diagnostics

Shows:

- permission state
- exact alarm state
- Accessibility status
- Pixel Camera version
- Shizuku status
- secure camera resolution
- most recent failures

## Settings

Potential settings:

```text
Default time-lapse speed
Retry policy
Battery threshold
Require charging
Debug logging
Privileged backend
Failure notification behavior
```

---

# Recommended Android Stack

Suggested implementation stack:

```text
Language
Kotlin

UI
Jetpack Compose

Architecture
Unidirectional data flow

Async
Kotlin Coroutines
Flow

Persistence
Room

Scheduling
AlarmManager

Automation
AccessibilityService

Privileged operations
PrivilegedBridge (Shizuku adapter planned)

Dependency injection
explicit application composition root

Serialization
kotlinx.serialization

Logging
structured internal logger
```

Avoid introducing unnecessary framework complexity in the early stages.

---

# Suggested Module Structure

```text
app/

core/
    common/
    model/
    logging/
    permissions/
    system/

data/
    database/
    repository/

feature/
    schedules/
    profiles/
    diagnostics/
    settings/

automation/
    engine/
    state/
    accessibility/
    selectors/
    calibration/
    recovery/

scheduler/
    alarm/
    receiver/

pixelcamera/
    launcher/
    model/
    profile/
    detector/

privileged/
    api/
    shizuku/
```

An alternative Gradle structure may be introduced only when module boundaries provide clear value.

---

# Important Interfaces

## Camera Launcher

```kotlin
interface PixelCameraLauncher {
    suspend fun launchSecureCamera(): Result<Unit>
}
```

## UI Driver

```kotlin
interface UiDriver {

    suspend fun find(
        target: UiTarget,
    ): UiElement?

    suspend fun click(
        target: UiTarget,
    ): Result<Unit>

    suspend fun gesture(
        gesture: UiGesture,
    ): Result<Unit>
}
```

## Pixel Camera Inspector

```kotlin
interface PixelCameraInspector {

    suspend fun currentState(): PixelCameraState

    suspend fun isRecording(): Boolean
}
```

## Automation Engine

```kotlin
interface AutomationEngine {

    suspend fun start(
        schedule: RecordingSchedule,
    ): AutomationResult

    suspend fun stop(
        scheduleId: UUID,
    ): AutomationResult
}
```

## Scheduler

```kotlin
interface RecordingScheduler {

    suspend fun schedule(
        schedule: RecordingSchedule,
    )

    suspend fun cancel(
        scheduleId: UUID,
    )

    suspend fun restore()
}
```

---

# Testing Strategy

Lenswake needs several testing layers.

## Unit Tests

Test:

- state machine transitions
- retry policies
- timeout behavior
- schedule validation
- profile compatibility
- selector scoring
- environment validation

## Instrumentation Tests

Test:

- AlarmManager integration
- receivers
- Accessibility Service lifecycle
- persistent schedule recovery
- Android permission state handling

## Device Automation Tests

The most important tests run on a physical Pixel.

Scenarios:

```text
screen on / unlocked

screen on / locked

screen off / locked

Doze

battery saver

Pixel Camera already open

Pixel Camera in unexpected mode

Pixel Camera killed

Lenswake process killed

Pixel Camera updated

device rebooted
```

## End-to-End Rehearsal

The final test is always:

```text
schedule
→ lock device
→ leave device untouched
→ verify recording starts
→ verify recording stops
```

---

# Development Workflow

Before implementing automated UI interaction, manually characterize Pixel Camera using ADB.

Useful categories of investigation include:

```bash
adb shell dumpsys window
adb shell dumpsys activity
adb shell uiautomator dump
adb shell cmd package resolve-activity ...
adb shell am start ...
adb shell input keyevent ...
```

The objective is to understand:

- secure camera launch behavior
- foreground activities
- accessibility hierarchy
- available resource IDs
- content descriptions
- screen state transitions
- behavior during recording
- behavior while locked

Do not hardcode assumptions before validating them against the target Pixel Camera version.

---

# Initial Development Milestones

## Phase 1 — Manual Pixel Camera Research

Validate:

```text
locked device
→ wake
→ secure Pixel Camera
```

Document Pixel Camera UI states.

---

## Phase 2 — Accessibility Explorer

Build a development screen capable of displaying relevant nodes from:

```text
com.google.android.GoogleCamera
```

Capture:

- resource IDs
- descriptions
- text
- bounds
- clickability
- selected state

---

## Phase 3 — Manual Automation

Implement:

```text
Open Pixel Camera
→ Video
→ Time Lapse
→ 120×
→ Record
→ Stop
```

initiated while Lenswake is already running.

---

## Phase 4 — State Machine

Replace direct action chains with deterministic state transitions and verification.

---

## Phase 5 — Lock-Screen Launch

Automate:

```text
screen off + locked
→ wake
→ secure camera
→ recording
```

---

## Phase 6 — Exact Scheduling

Add:

```text
START alarm
STOP alarm
```

and persistent schedules.

---

## Phase 7 — Shizuku Integration

Add optional privileged bridge for operations unavailable or unreliable through standard APIs.

---

## Phase 8 — Calibration Profiles

Allow selectors to adapt to Pixel Camera versions.

---

## Phase 9 — Rehearsal and Diagnostics

Make unattended recordings measurable and testable.

---

## Phase 10 — Hardening

Test:

- Doze
- reboot
- process death
- Pixel Camera updates
- delayed camera startup
- dialogs
- low battery
- thermal throttling
- long-duration sessions

---

# Security and Privacy

Lenswake should remain fully local.

No backend is required.

No cloud account is required.

No telemetry is required.

Recommended defaults:

```text
Network access: none
Analytics: none
Cloud sync: none
Remote control: none
```

Schedules, automation profiles, and logs should remain on the device.

Android backup and device-to-device extraction rules exclude every credential- and
device-protected storage domain. Room history, alarm journals, recovery checkpoints, and
SharedPreferences state must not leave the originating device through cloud backup or Android
device-to-device migration.

Accessibility data should be processed only for the Pixel Camera package and should not be persisted unless explicitly required for debugging.

---

# Disclaimer

Pixel Camera is a Google application and does not expose a public API for selecting internal modes such as Time Lapse programmatically.

Lenswake therefore depends on UI automation.

Google may change:

- layouts
- resource IDs
- accessibility metadata
- navigation
- mode naming
- available modes
- secure-camera behavior

at any time.

Lenswake should detect such changes whenever possible and fail explicitly rather than executing uncontrolled input.

This project is not affiliated with or endorsed by Google.

Google, Pixel, Android, and Pixel Camera are trademarks of their respective owners.

---

# Project Status

```text
Status: Experimental / Research
Foundation: Implemented and buildable
Target automation: Physically verified for the recorded target environment
Target: Pixel 8 Pro + Android 17
Distribution: Personal use
Root required: No
Pixel Camera required: Yes
Accessibility required: Yes
Shizuku: Optional
```

The first milestone is not broad device support.

The first milestone is:

> Reliably start and stop a native Pixel Camera Time Lapse on a locked Pixel 8 Pro at scheduled times.

Once that scenario is deterministic, additional Pixel Camera modes and devices can be added incrementally.

---

# Name

**Lenswake**

The name reflects the core behavior of the project:

```text
lens + wake
```

Wake the Pixel, wake the camera, capture the moment.
