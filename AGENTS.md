# AGENTS.md

## Project: Lenswake

Lenswake is a personal Android automation application for scheduling unattended recordings in the **native Google Pixel Camera**.

The primary target is:

```text
Device:       Google Pixel 8 Pro
OS:           Android 17
Camera app:   Google Pixel Camera
Package:      com.google.android.GoogleCamera
Root:         Not available / not required
Distribution: Personal sideloaded APK
```

The core use case is:

```text
User creates a schedule
        ↓
Pixel is left locked with screen off
        ↓
At the configured start time:
    wake device
    launch native Pixel Camera
    navigate Pixel Camera UI
    select Video
    select Time Lapse
    select configured speed
    start recording
    verify recording started
        ↓
At the configured stop time:
    wake device if necessary
    locate Pixel Camera recording UI
    stop recording
    verify recording stopped
```

Lenswake must automate **Pixel Camera itself**.

It must not replace Pixel Camera with CameraX, Camera2, MediaRecorder, or another capture implementation.

---

# 1. Agent Mission

When working on Lenswake, optimize for:

1. deterministic behavior;
2. reliability on the explicitly supported Pixel configuration;
3. observability;
4. recoverability;
5. minimal privilege;
6. explicit failure rather than silent incorrect behavior;
7. maintainable automation selectors;
8. compatibility with process death and Android lifecycle constraints.

Do not optimize prematurely for:

- broad Android device compatibility;
- Google Play distribution;
- generic automation capabilities;
- elaborate UI;
- arbitrary camera applications;
- speculative future devices.

The initial success criterion is narrow:

> Reliably start and stop a native Pixel Camera Time Lapse on a locked Pixel 8 Pro at scheduled times.

---

# 2. Critical Product Constraint

This constraint overrides architectural convenience:

```text
PIXEL CAMERA MUST OWN THE CAMERA.
```

Lenswake is an automation/orchestration application.

Do not implement capture using:

- CameraX;
- Camera2;
- MediaRecorder;
- OpenCV camera access;
- custom video encoding;
- custom time-lapse rendering;
- a Lenswake-owned camera foreground service.

If a task appears easier by bypassing Pixel Camera, do not do it.

The value of Lenswake comes specifically from retaining Pixel Camera's native behavior and proprietary processing.

---

# 3. Expected Development Style

Agents should behave like engineers working on a reliability-sensitive Android automation system.

Prefer:

- small verified changes;
- explicit state;
- instrumentation;
- physical-device validation;
- deterministic experiments;
- documented findings;
- narrow abstractions;
- evidence from runtime behavior.

Avoid:

- speculative rewrites;
- giant unverified patches;
- assumptions about undocumented Pixel Camera internals;
- blind coordinate macros;
- `delay()`-driven workflows;
- swallowing exceptions;
- implicit global state;
- cleverness that decreases diagnosability.

When an Android or Pixel Camera behavior is unknown, investigate it before encoding it as architecture.

---

# 4. Source of Truth

Use the following precedence when determining system behavior:

```text
1. Observed behavior on the target Pixel 8 Pro
2. Current Android platform documentation
3. AOSP source / Android compatibility documentation
4. Shizuku official documentation/source
5. Reproducible ADB investigation
6. Existing Lenswake documentation
7. Assumptions
```

An undocumented Pixel Camera implementation detail is never considered stable merely because it worked once.

Record important findings.

---

# 5. Do Not Guess Pixel Camera Internals

Google Pixel Camera is not a public automation API.

Do not invent:

- Activity class names;
- resource IDs;
- Intent extras;
- time-lapse mode identifiers;
- hidden Pixel Camera APIs;
- stable accessibility labels;
- stable UI geometry.

If an internal identifier is required:

1. inspect the installed application/environment;
2. verify it on the target device;
3. document how it was discovered;
4. wrap it behind an appropriate abstraction;
5. provide fallback behavior where practical.

Never hardcode undocumented Pixel Camera internals without an accompanying compatibility strategy.

---

# 6. Target Architecture

The intended high-level architecture is:

```text
UI / Configuration
        │
        ▼
Schedule Repository
        │
        ▼
Alarm Scheduler
        │
        ▼
Automation Coordinator
        │
        ▼
Automation State Machine
        │
        ├───────────────────────┐
        ▼                       ▼
Pixel Camera Inspector     Device/System Control
        │                       │
Accessibility Driver      Privileged Bridge
        │                       │
        └──────────┬────────────┘
                   ▼
             Pixel Camera
```

The domain layer should not depend directly on:

- Android `AccessibilityNodeInfo`;
- Shizuku classes;
- raw shell commands;
- Compose;
- Activities;
- BroadcastReceivers.

Keep Android-specific implementation details at infrastructure boundaries.

---

# 7. Suggested Package / Module Boundaries

Do not over-modularize immediately, but preserve these conceptual boundaries:

```text
app/
    application composition
    navigation
    DI setup

core/
    common/
    model/
    time/
    logging/
    result/

schedule/
    model/
    repository/
    validation/
    alarm/

automation/
    engine/
    state/
    action/
    retry/
    timeout/
    recovery/

pixelcamera/
    launcher/
    inspector/
    model/
    selectors/
    profile/
    compatibility/

accessibility/
    service/
    tree/
    matching/
    gesture/

privileged/
    api/
    shizuku/
    shell/

diagnostics/
    events/
    history/
    export/

feature/
    schedules/
    profiles/
    diagnostics/
    settings/
```

Introduce separate Gradle modules only when they provide concrete build or dependency advantages.

Package boundaries are more important initially than module count.

---

# 8. Dependency Direction

Preferred dependency direction:

```text
UI
 ↓
application/use cases
 ↓
domain
 ↑
infrastructure implementations
```

Domain types must not import Android framework classes unless unavoidable.

For example, prefer:

```kotlin
data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
```

over storing:

```kotlin
android.graphics.Rect
```

inside the domain model.

---

# 9. State Machine Is Mandatory

Pixel Camera automation must be implemented as an explicit state machine.

Never reduce the main flow to:

```kotlin
launchCamera()
delay(1000)
tapVideo()
delay(500)
tapTimeLapse()
delay(500)
tapRecord()
```

This is prohibited for production automation.

Use explicit states such as:

```kotlin
sealed interface AutomationState {

    data object Scheduled : AutomationState

    data object StartTriggered : AutomationState

    data object WakingDevice : AutomationState

    data object LaunchingCamera : AutomationState

    data object WaitingForCamera : AutomationState

    data object InspectingCameraState : AutomationState

    data object SelectingVideo : AutomationState

    data object VerifyingVideo : AutomationState

    data object SelectingTimeLapse : AutomationState

    data object VerifyingTimeLapse : AutomationState

    data object SelectingSpeed : AutomationState

    data object VerifyingSpeed : AutomationState

    data object StartingRecording : AutomationState

    data object VerifyingRecording : AutomationState

    data object Recording : AutomationState

    data object StopTriggered : AutomationState

    data object PreparingStop : AutomationState

    data object StoppingRecording : AutomationState

    data object VerifyingStopped : AutomationState

    data object Completed : AutomationState

    data class Retrying(
        val operation: AutomationOperation,
        val attempt: Int,
    ) : AutomationState

    data class Failed(
        val failure: AutomationFailure,
    ) : AutomationState

    data object Cancelled : AutomationState
}
```

Exact names may evolve.

The principle may not.

---

# 10. State Transitions Must Be Observable

Every meaningful action needs a postcondition.

Incorrect:

```kotlin
click(recordButton)
state = Recording
```

Correct:

```text
click record
    ↓
observe Pixel Camera
    ↓
recording UI detected?
    ├─ YES → Recording
    └─ NO  → retry / fail
```

Examples of possible recording evidence:

- Stop button appears;
- Record button transforms/disappears;
- recording timer appears;
- recording timer advances;
- accessibility tree exposes a recording state;
- another verified Pixel Camera state changes.

Prefer multiple weak signals over one unreliable signal where practical.

---

# 11. Never Confuse Action Dispatch With Action Success

These concepts must remain distinct:

```text
gesture dispatched
≠
button activated
≠
mode changed
≠
recording started
```

Every automation action should ideally return information similar to:

```kotlin
data class ActionExecution(
    val dispatched: Boolean,
    val method: InteractionMethod,
    val target: UiTarget?,
)
```

while state verification determines whether the desired outcome happened.

---

# 12. UI Interaction Priority

Use this interaction hierarchy:

```text
1. Accessibility semantic action
2. Accessibility gesture using discovered node bounds
3. Accessibility gesture using calibrated normalized bounds
4. Privileged input fallback
5. Fail explicitly
```

Do not immediately use shell `input tap`.

Semantic interaction is preferred because it survives more UI changes.

---

# 13. Selector Priority

When identifying a Pixel Camera UI element, prefer:

```text
1. packageName
2. stable resource ID, if empirically verified
3. role / class
4. contentDescription
5. text
6. selected/checkable/clickable state
7. hierarchy context
8. relative bounds
9. calibrated absolute/normalized fallback
```

Do not select a node solely because:

```text
text == "Time Lapse"
```

when a more robust selector can be constructed.

---

# 14. Selector Scoring

When useful, model target detection as scoring rather than binary matching.

Conceptually:

```text
resource ID exact            +100
content description exact     +60
expected class                +20
clickable                     +10
expected screen region        +10
text exact                    +30
selected state expected       +15
unexpected package           -1000
```

The exact algorithm is implementation-dependent.

If selector confidence is below a safe threshold, do not blindly click.

---

# 15. UI Hierarchy Context Matters

Pixel Camera may expose duplicate labels.

For example, a text or description may appear:

- in the active mode selector;
- in a drawer;
- in a settings sheet;
- off-screen;
- in an invisible node.

Use:

- ancestor information;
- node bounds;
- visibility;
- clickability;
- sibling context;
- selected state;
- screen region.

Never assume the first matching node is correct.

---

# 16. Accessibility Scope

Lenswake's Accessibility Service must be narrowly scoped.

Primary package:

```text
com.google.android.GoogleCamera
```

Do not build generic surveillance of other applications.

Process accessibility data only when required for:

- Pixel Camera detection;
- Pixel Camera state inference;
- Pixel Camera interaction;
- calibration;
- diagnostics explicitly needed for development.

Do not persist arbitrary accessibility trees by default.

---

# 17. Calibration Profiles

Pixel Camera UI automation must support environment-specific profiles.

Profile identity should include at least:

```text
Pixel device model
Android SDK/build information
Pixel Camera package
Pixel Camera versionCode
locale
display dimensions
density
Lenswake selector schema version
```

Example:

```kotlin
data class PixelCameraEnvironment(
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

Do not assume a profile calibrated for one environment is valid globally.

---

# 18. Coordinate Fallbacks

If coordinates must be stored, prefer normalized coordinates:

```kotlin
data class NormalizedPoint(
    val x: Float, // 0.0 .. 1.0
    val y: Float, // 0.0 .. 1.0
)
```

Runtime conversion:

```kotlin
val pixelX = normalized.x * screenWidth
val pixelY = normalized.y * screenHeight
```

Never scatter raw Pixel 8 Pro screen coordinates throughout production code.

All coordinate fallbacks belong to profile/configuration data.

---

# 19. Pixel Camera Version Changes

The installed Pixel Camera `versionCode` must be checked.

If:

```text
calibratedVersion != installedVersion
```

mark the profile as needing verification.

Do not silently claim full compatibility.

Potential states:

```kotlin
enum class ProfileCompatibility {
    VERIFIED,
    PROBABLY_COMPATIBLE,
    NEEDS_REHEARSAL,
    INCOMPATIBLE,
}
```

A previous profile may still be tested through rehearsal.

---

# 20. Secure Camera Launch

The preferred lock-screen path should use Android's secure camera behavior rather than attempting to unlock the device.

Desired flow:

```text
screen off
keyguard locked
      ↓
alarm
      ↓
wake
      ↓
secure Pixel Camera launch
      ↓
Pixel Camera visible
keyguard still locked
```

Prefer dynamic intent resolution.

Do not hardcode Pixel Camera Activity class names unless no alternative exists.

If a class name must be used, isolate it in compatibility/configuration code.

---

# 21. Do Not Bypass Device Security

Lenswake should not:

- disable the lock screen;
- remove credentials;
- inject passwords or PINs;
- circumvent biometric authentication;
- weaken Android security settings.

The intended workflow explicitly leaves the device locked.

Use lock-screen-compatible camera functionality.

---

# 22. Scheduling Rules

Use `AlarmManager` exact alarms for user-defined exact start/stop times.

Each recording session must schedule start and stop independently.

Correct:

```text
start alarm → 05:30
stop alarm  → 07:30
```

Incorrect as sole mechanism:

```kotlin
startRecording()
delay(2.hours)
stopRecording()
```

Process death must not lose the intended stop trigger.

---

# 23. Persist Before Scheduling

When creating or modifying a session:

```text
validate
  ↓
persist schedule
  ↓
register alarms
  ↓
verify registration state if possible
```

Do not create transient scheduled operations that exist only in memory.

---

# 24. Alarm Idempotency

Alarm receivers may encounter:

- duplicates;
- stale intents;
- rescheduled sessions;
- cancelled sessions;
- already-completed sessions.

Every alarm must include a stable schedule/session identifier.

Example:

```text
lenswake://schedule/{id}/start
lenswake://schedule/{id}/stop
```

Before executing an alarm:

1. load current persisted schedule;
2. validate its status;
3. reject stale execution;
4. proceed idempotently.

---

# 25. Reboot Recovery

Future alarms must be restored after reboot.

`BOOT_COMPLETED` handling should:

```text
load schedules
  ↓
filter enabled future schedules
  ↓
re-register exact alarms
  ↓
check environment dependencies
  ↓
update readiness status
```

Do not launch Pixel Camera from `BOOT_COMPLETED`.

Do not start capture merely because the device rebooted.

---

# 26. Time Handling

Represent schedule times using robust time APIs.

Prefer:

```text
Instant
ZoneId
ZonedDateTime
```

Avoid ad hoc epoch calculations scattered through UI code.

Distinguish:

```text
scheduled local wall time
actual trigger Instant
```

Record both where useful.

Handle timezone changes explicitly.

---

# 27. Start and Stop Are Separate Automation Problems

Do not assume stop is simply the reverse of start.

Start may involve:

```text
wake
launch secure camera
select modes
select speed
start
verify
```

Stop may occur in a different device/UI condition.

At stop time:

1. inspect device state;
2. wake if necessary;
3. locate/restore Pixel Camera recording UI;
4. verify that recording is active;
5. stop;
6. verify stop.

Design separate workflows sharing common primitives.

---

# 28. Do Not Assume Pixel Camera Remains Foreground

During a long capture Android or Pixel Camera behavior may differ.

At stop time do not blindly issue a screen coordinate.

First determine:

```text
current foreground package
Pixel Camera state
expected recording state
```

Recovery may be necessary before the stop action.

---

# 29. Process Death Is Normal

Android may terminate Lenswake while Pixel Camera continues recording.

Therefore:

- active schedule state must be persisted;
- expected stop time must be persisted;
- alarm registration must not depend on process lifetime;
- session state must be reconstructable.

Never make correctness depend on a singleton or a long-lived coroutine.

---

# 30. Avoid Unnecessary Long-Lived Services

Do not create a permanently running Lenswake foreground service merely to keep the application alive.

The desired model is event-driven:

```text
schedule
→ sleep
→ exact alarm
→ automate
→ stop Lenswake work
→ Pixel Camera records
→ exact alarm
→ automate stop
```

Use long-running components only if runtime investigation proves them necessary.

---

# 31. Shizuku Is Optional Infrastructure

Shizuku can be useful, but Lenswake must not collapse if Shizuku is unavailable.

Define an abstraction such as:

```kotlin
interface PrivilegedBridge {

    suspend fun availability(): PrivilegedAvailability

    suspend fun wakeDevice(): Result<Unit>

    suspend fun startActivity(
        request: ActivityLaunchRequest,
    ): Result<Unit>

    suspend fun inputTap(
        point: PixelPoint,
    ): Result<Unit>

    suspend fun inputSwipe(
        gesture: SwipeGesture,
    ): Result<Unit>
}
```

Do not expose Shizuku types outside the implementation module/package.

---

# 32. Android 17 Shizuku Compatibility

Treat Shizuku support on Android 17 as runtime capability, not a guaranteed invariant.

The application should detect:

```text
Shizuku installed?
Shizuku running?
binder reachable?
permission granted?
required operation works?
```

Do not equate:

```text
Shizuku app installed
```

with:

```text
privileged automation available
```

Whenever possible, execute a harmless capability probe.

---

# 33. Shizuku Startup Limitation

On a non-root device, Shizuku may need to be restarted after reboot through Wireless Debugging.

Lenswake must expose this condition clearly.

Example diagnostic:

```text
Privileged automation:
UNAVAILABLE

Reason:
Shizuku service is not running.

Impact:
Scheduled recording cannot use privileged wake/input fallback.
```

Do not obscure this behind a generic error.

---

# 34. Standard APIs Before Privileged APIs

Use standard Android APIs where they are reliable.

Shizuku should not become a hammer for every operation.

Preferred order:

```text
public Android API
    ↓ unavailable/inadequate
Accessibility capability
    ↓ unavailable/inadequate
privileged bridge
```

This reduces coupling and failure modes.

---

# 35. Permissions

Request only permissions needed by current functionality.

Do not request `CAMERA` just because this is a camera-related project.

Lenswake itself does not own the camera.

Potential requirements include:

- exact alarm permission/access;
- boot completed receiver;
- notifications;
- Accessibility Service;
- optional Shizuku authorization.

When adding a permission, document:

```text
why it is needed
where it is used
what happens without it
whether it is optional
```

---

# 36. Preflight Model

A schedule should have an explicit readiness state.

Possible checks:

```text
Pixel Camera installed
Pixel Camera package resolves
Pixel Camera profile available
profile version compatible
Accessibility enabled
exact alarms available
secure camera launch available
privileged fallback available if required
battery policy satisfied
storage policy satisfied
```

Do not collapse all checks into a Boolean.

Prefer:

```kotlin
data class PreflightReport(
    val checks: List<PreflightCheck>,
) {
    val ready: Boolean
}
```

with actionable individual results.

---

# 37. Rehearsal Is a First-Class Feature

"Test now" is not a debug-only shortcut.

It is an important reliability mechanism.

A rehearsal should exercise the actual production automation stack:

```text
launch
navigate
start
verify
wait briefly
stop
verify
```

Do not implement rehearsal using separate mock shortcuts that bypass normal automation.

If production relies on a selector, rehearsal must test that selector.

---

# 38. Physical Device Testing Is Required

An emulator is insufficient to claim capture automation reliability.

Important behavior depends on:

- Pixel Camera;
- lockscreen;
- physical cameras;
- Pixel-specific firmware;
- Pixel-specific Android behavior;
- Shizuku;
- power management.

Use emulator tests for domain and Android framework logic where useful.

Use a physical Pixel 8 Pro for final automation verification.

---

# 39. ADB Investigation

ADB is a primary research tool for the project.

Useful investigation categories include:

```bash
adb shell dumpsys activity
adb shell dumpsys window
adb shell dumpsys power
adb shell dumpsys deviceidle
adb shell dumpsys package com.google.android.GoogleCamera

adb shell cmd package resolve-activity ...
adb shell am start ...
adb shell input keyevent ...
adb shell input tap ...
adb shell input swipe ...

adb shell uiautomator dump
```

Do not blindly copy these commands into production.

Use them to learn the target environment.

---

# 40. Experiments Must Be Reproducible

When investigating a system behavior, record:

```text
Device
OS/build
Pixel Camera version
Lenswake commit
initial device state
exact command/action
observed result
conclusion
```

If useful, add research notes under:

```text
docs/research/
```

Example:

```text
docs/research/
    secure-camera-launch.md
    pixel-camera-accessibility-tree.md
    android-17-shizuku.md
    recording-stop-behavior.md
```

---

# 41. Prefer Runtime Introspection

If information can safely be discovered at runtime, prefer discovery over constants.

Examples:

```text
Pixel Camera version
resolved secure-camera Activity
screen dimensions
density
locale
current package
keyguard state
screen interactive state
```

Do not hardcode facts that Android can provide.

---

# 42. Logging Requirements

Every automation session must be diagnosable.

Structured events should include:

```text
session ID
schedule ID
timestamp
automation state
operation
attempt
duration
interaction method
selector/profile identity
outcome
failure code
```

Example:

```kotlin
data class AutomationEvent(
    val sessionId: UUID,
    val timestamp: Instant,
    val state: AutomationStateName,
    val operation: AutomationOperation?,
    val outcome: AutomationOutcome,
    val durationMs: Long?,
    val metadata: Map<String, String>,
)
```

Avoid logging entire accessibility trees in normal operation.

---

# 43. Event Naming

Prefer semantic names:

```text
automation.camera_launch.started
automation.camera_launch.succeeded

automation.selector.match
automation.selector.no_match

automation.record.start_dispatched
automation.record.start_verified

automation.record.stop_dispatched
automation.record.stop_verified
```

Avoid meaningless logs such as:

```text
here
works
clicked
retrying stuff
```

---

# 44. Failure Codes

Failures should be typed.

Initial taxonomy may include:

```kotlin
enum class AutomationFailureCode {
    EXACT_ALARM_UNAVAILABLE,
    ACCESSIBILITY_DISABLED,
    PIXEL_CAMERA_NOT_INSTALLED,
    PIXEL_CAMERA_LAUNCH_FAILED,
    PIXEL_CAMERA_NOT_FOREGROUND,
    PIXEL_CAMERA_VERSION_CHANGED,
    PROFILE_NOT_FOUND,
    PROFILE_INCOMPATIBLE,
    PRIVILEGED_BRIDGE_UNAVAILABLE,
    WAKE_FAILED,
    VIDEO_MODE_NOT_FOUND,
    TIME_LAPSE_MODE_NOT_FOUND,
    TIME_LAPSE_SPEED_NOT_FOUND,
    RECORD_CONTROL_NOT_FOUND,
    RECORDING_NOT_CONFIRMED,
    STOP_CONTROL_NOT_FOUND,
    STOP_NOT_CONFIRMED,
    UNEXPECTED_DIALOG,
    AUTOMATION_TIMEOUT,
    AUTOMATION_CANCELLED,
    UNKNOWN,
}
```

Use domain-specific failures rather than generic `Exception("failed")`.

---

# 45. Exception Handling

Never do:

```kotlin
try {
    ...
} catch (_: Exception) {
}
```

unless suppressing the exception is explicitly justified and logged appropriately.

Unexpected exceptions should be:

1. mapped to diagnostic context;
2. persisted for session history where relevant;
3. surfaced as an explicit failure.

Do not crash the entire application because one automation node was missing.

---

# 46. Retry Policy

Retries must be:

- bounded;
- operation-specific;
- cancellable;
- observable.

Example:

```kotlin
RetryPolicy(
    maxAttempts = 5,
    initialDelay = 200.milliseconds,
    maxDelay = 1.seconds,
)
```

Do not use the same retry configuration for every operation without justification.

Launching Pixel Camera may need a longer retry window than clicking a local selector.

---

# 47. Timeouts

All interactions with an external UI or system service require finite timeouts.

No call should wait indefinitely for Pixel Camera.

Examples:

```text
wake device              few seconds
launch camera             several seconds
find mode                 several seconds
verify recording          several seconds
verify stopped            several seconds
Shizuku bind              finite timeout
```

Exact values should be centralized/configurable.

---

# 48. Coroutines

Use structured concurrency.

Do not create uncontrolled scopes such as:

```kotlin
CoroutineScope(Dispatchers.IO).launch { ... }
```

inside arbitrary classes.

Inject or bind scopes to explicit lifecycle/application components.

Prefer suspend APIs for automation operations.

Cancellation should propagate through:

```text
automation engine
→ waits
→ retries
→ accessibility operations
```

---

# 49. Flow Usage

Use `StateFlow` for durable observable UI/application state.

Use `SharedFlow` only when transient event semantics are correct.

Do not model durable schedule state as one-shot events.

Do not use `Channel` as an ad hoc global event bus.

---

# 50. Compose

Compose UI should be a projection of application state.

Prefer:

```text
ViewModel
  ↓ StateFlow<UiState>
Compose
```

Business logic must not live inside Composables.

Do not let Compose directly access:

- AlarmManager;
- Shizuku;
- AccessibilityService;
- Room DAOs;
- Pixel Camera inspectors.

---

# 51. UI Priority

The UI is secondary to automation reliability.

Prefer a simple interface with clear operational state over elaborate visual effects.

Important status must be visible:

```text
Ready
Needs setup
Needs rehearsal
Scheduled
Recording expected
Failed
Completed
```

A user must understand whether an unattended session can be trusted.

---

# 52. Room / Persistence

Persist at least:

```text
RecordingSchedule
AutomationProfile
ExecutionSession
AutomationEvent / compact history
RehearsalResult
```

Database migrations must be explicit.

Do not use destructive migration as the default after meaningful user schedules/profiles exist.

---

# 53. Repository Rules

Repositories expose domain-friendly APIs.

Example:

```kotlin
interface ScheduleRepository {
    fun observeSchedules(): Flow<List<RecordingSchedule>>

    suspend fun get(id: ScheduleId): RecordingSchedule?

    suspend fun save(schedule: RecordingSchedule)

    suspend fun delete(id: ScheduleId)
}
```

Do not pass Room entities directly into UI/domain layers.

---

# 54. Environment Snapshot

Each execution session should capture an environment snapshot before automation.

Useful fields:

```text
device model
Android SDK
build fingerprint
Pixel Camera version
Lenswake version
locale
display configuration
accessibility status
privileged bridge status
screen interactive state
keyguard state
battery
charging
available storage
```

This makes later failure analysis much easier.

---

# 55. Security and Privacy

Lenswake is local-first.

Do not add:

- analytics SDKs;
- telemetry services;
- remote logging;
- cloud accounts;
- advertising;
- arbitrary network dependencies.

If networking is introduced later, it must have a clear project-specific purpose.

Accessibility information is sensitive.

Do not persist:

- unrelated app contents;
- arbitrary screen text;
- full UI trees;

unless explicitly enabled for a development diagnostic mode.

---

# 56. No Hidden Surveillance Features

Do not expand Lenswake into:

- covert recording;
- background spying;
- remote stealth control;
- hidden recording indicator suppression;
- lockscreen circumvention.

Lenswake schedules photography on the user's own device through normal Pixel Camera behavior.

Respect Android/Pixel Camera privacy indicators and system behavior.

---

# 57. Battery

Lenswake should be mostly dormant between alarms.

Avoid:

- permanent polling;
- constant Accessibility tree traversal when Pixel Camera is not relevant;
- unnecessary wake locks;
- persistent high-priority services;
- tight loops.

At automation time, short-lived power usage is acceptable.

Pixel Camera is responsible for the energy cost of actual recording.

---

# 58. Storage

Lenswake does not own the output file.

Pixel Camera owns recording output.

Do not assume Lenswake can directly obtain the resulting file URI.

If output association is implemented later, investigate reliable ways to correlate:

```text
session start/end
↔
new Pixel Camera media item
```

Do not guess filenames.

---

# 59. Thermal Behavior

Do not attempt to defeat Pixel thermal safeguards.

Lenswake may inspect thermal state and warn/abort according to user configuration.

Pixel Camera remains authoritative over camera thermal behavior.

---

# 60. Testing Pyramid

## Unit tests

Mandatory for pure logic:

- state transitions;
- retry policy;
- schedule validation;
- selector scoring;
- profile compatibility;
- timeout decisions;
- failure mapping;
- preflight logic.

## Android instrumentation tests

Use for:

- persistence;
- alarms;
- receivers;
- boot restoration;
- permission state;
- Android integration boundaries.

## Physical Pixel tests

Mandatory for:

- secure Pixel Camera launch;
- locked-screen automation;
- Accessibility selectors;
- time-lapse navigation;
- start verification;
- stop verification;
- Shizuku operations;
- Doze behavior.

---

# 61. Required End-to-End Scenarios

Before considering the first version reliable, test:

```text
1. screen on + unlocked

2. screen on + locked

3. screen off + locked

4. screen off + locked + Doze

5. Pixel Camera already open

6. Pixel Camera in Photo mode

7. Pixel Camera in Video mode

8. Pixel Camera already in Time Lapse

9. Lenswake process killed before START

10. Lenswake process killed while Pixel Camera records

11. Pixel Camera process initially stopped

12. Pixel Camera UI delayed

13. STOP alarm while screen off

14. device reboot before scheduled start

15. Pixel Camera version changed

16. Accessibility disabled

17. Shizuku unavailable

18. insufficient preflight conditions
```

Record outcomes.

---

# 62. Reliability Metrics

Measure rather than assume reliability.

Track:

```text
scheduledStartAt
actualAlarmAt
cameraVisibleAt
recordActionAt
recordVerifiedAt

scheduledStopAt
stopActionAt
stopVerifiedAt
```

Derived metrics:

```text
alarm start latency
camera launch latency
automation preparation latency
recording start deviation
recording stop deviation
success rate
retry rate
fallback rate
```

This data should remain local.

---

# 63. Definition of Successful Start

A START operation is successful only when Lenswake has evidence that Pixel Camera is actively recording.

Not sufficient:

```text
Record button was tapped.
```

Required:

```text
Record action dispatched
+
recording state verified
```

---

# 64. Definition of Successful Stop

A STOP operation is successful only when Lenswake verifies Pixel Camera has left the recording state.

Not sufficient:

```text
Stop button was tapped.
```

Required:

```text
Stop action dispatched
+
non-recording state verified
```

---

# 65. Definition of Done for Features

A feature is not done because it compiles.

For automation-related changes, definition of done includes:

```text
[ ] architecture boundary respected
[ ] failure paths implemented
[ ] structured logs added
[ ] tests added for pure logic
[ ] relevant diagnostics surfaced
[ ] physical-device validation performed when required
[ ] no blind delay chain introduced
[ ] no undocumented Pixel Camera assumption added without evidence
[ ] README/docs updated if behavior changed
```

---

# 66. Commit Scope

Keep commits focused.

Good:

```text
feat(automation): add recording verification state

feat(pixelcamera): resolve secure camera activity dynamically

fix(schedule): restore stop alarms after reboot
```

Bad:

```text
misc fixes

update stuff

huge refactor
```

Do not combine unrelated architecture, UI, and experimentation changes unnecessarily.

---

# 67. Comments

Comments should explain:

- why a workaround exists;
- Android platform constraints;
- Pixel Camera-specific assumptions;
- lifecycle reasoning;
- concurrency invariants;
- compatibility boundaries.

Do not comment obvious syntax.

Good:

```kotlin
// Pixel Camera may expose the control before it becomes clickable.
// Verification after ACTION_CLICK is therefore authoritative.
```

Bad:

```kotlin
// Click button
button.click()
```

---

# 68. TODOs

Every TODO must be actionable.

Good:

```text
TODO(pixel-camera-compat):
Verify whether resource ID remains stable on Pixel Camera 10.x before
promoting this selector above contentDescription.
```

Bad:

```text
TODO fix later
```

---

# 69. Documentation

Keep important architecture knowledge outside source comments.

Use:

```text
README.md
AGENTS.md
docs/ARCHITECTURE.md
docs/research/*
docs/testing/*
```

If you discover behavior that another agent would need to know, document it.

Do not rely on conversation history as project knowledge.

---

# 70. Architecture Decision Records

For material decisions, consider:

```text
docs/adr/
```

Examples:

```text
0001-use-native-pixel-camera.md
0002-accessibility-first-ui-automation.md
0003-exact-alarm-start-stop.md
0004-shizuku-as-optional-bridge.md
```

An ADR should state:

```text
Context
Decision
Alternatives
Consequences
```

---

# 71. Research Before Refactoring

Do not perform an architectural rewrite because an API "should" behave differently.

For Android 17 / Pixel Camera problems:

```text
observe
→ reproduce
→ collect evidence
→ determine cause
→ fix
```

Avoid:

```text
guess
→ rewrite
→ hope
```

---

# 72. Avoid Overengineering

Do not introduce:

- distributed architecture;
- server backends;
- plugin frameworks;
- dynamic scripting languages;
- generic workflow DSLs;
- multi-process complexity;

unless a concrete Lenswake requirement justifies them.

A narrow reliable automation system is preferred.

---

# 73. Avoid Underengineering

Conversely, do not simplify away critical reliability mechanisms.

Do not remove:

- verification;
- state machine;
- persistence;
- retries;
- timeouts;
- version checks;
- diagnostics;

just to reduce code size.

---

# 74. Build Configuration

Use contemporary Android tooling appropriate for Android 17.

General expectations:

```text
Kotlin
modern Android Gradle Plugin
JDK version supported by current AGP
Jetpack Compose
Coroutines / Flow
Room
```

Pin meaningful versions through the project version catalog.

Do not randomly update toolchain versions while implementing unrelated features.

Toolchain upgrades should be deliberate commits.

---

# 75. Code Formatting and Static Analysis

The repository should eventually enforce:

```text
ktlint and/or Spotless
Detekt
Android Lint
unit tests
```

CI should reject:

- formatting violations;
- critical lint issues;
- failing tests.

Do not disable lint rules globally merely to silence a local issue.

---

# 76. CI Limitations

CI cannot prove Pixel Camera automation works.

CI may verify:

```text
build
lint
unit tests
database schemas
static architecture rules
```

Physical-device automation remains a separate validation category.

Never describe a green CI run as proof of end-to-end reliability.

---

# 77. Debug Build Capabilities

Debug builds may include developer tools such as:

```text
Accessibility tree explorer
current Pixel Camera state inspector
selector match visualizer
manual state-machine trigger
environment snapshot
raw automation event viewer
profile exporter
ADB experiment notes
```

Keep such tooling clearly separated from normal user flows.

---

# 78. Accessibility Tree Explorer

A useful early developer feature should expose relevant Pixel Camera nodes:

```text
class
resourceId
contentDescription
text
clickable
selected
enabled
visible
bounds
parent context
```

It should support filtering to:

```text
com.google.android.GoogleCamera
```

Do not dump unrelated applications.

---

# 79. Selector Diagnostics

For each attempted target, diagnostics should answer:

```text
What target were we looking for?
Which nodes were considered?
Which node won?
What score did it receive?
Which interaction method was used?
What verification followed?
```

This is far more valuable than simply logging:

```text
button not found
```

---

# 80. Unknown UI States

When Pixel Camera presents an unknown state:

1. do not perform arbitrary clicks;
2. capture safe diagnostic metadata;
3. attempt known bounded recovery if available;
4. fail if confidence remains insufficient.

Examples:

```text
permission dialog
first-run tutorial
storage warning
thermal warning
camera unavailable error
Pixel Camera onboarding
unexpected bottom sheet
```

These should eventually become recognizable states.

---

# 81. First-Run Pixel Camera

Do not assume Pixel Camera has completed its own onboarding.

Preflight or rehearsal should detect problematic first-run dialogs.

For unattended use, the user should manually open Pixel Camera and complete all required setup before relying on Lenswake.

---

# 82. Permissions and Setup UX

Lenswake should explain setup requirements precisely.

Do not simply show:

```text
Missing permissions
```

Prefer:

```text
Accessibility
Required to identify and interact with Pixel Camera controls.
Status: Disabled

Exact alarms
Required to trigger capture at the scheduled wall-clock time.
Status: Allowed

Shizuku
Optional privileged fallback for device wake and input.
Status: Not running
```

---

# 83. User Overrides

For critical readiness failures, default to safe behavior.

A user override may exist for warnings, but not for logically impossible conditions.

Example:

```text
Pixel Camera missing
```

cannot meaningfully be overridden.

Example:

```text
battery below recommended threshold
```

may be overridden.

---

# 84. Main Thread

Never perform:

- Room blocking operations;
- shell commands;
- Shizuku blocking calls;
- long waits;
- accessibility retries;

on the main thread.

Use proper coroutine dispatchers where needed.

---

# 85. Thread Safety

Automation state must have a single authoritative owner.

Avoid multiple components independently mutating session state.

Prefer one coordinator/state-machine execution context per active automation session.

START and STOP triggers must reconcile through persisted state.

---

# 86. Duplicate Alarm Race

Account for races such as:

```text
START still finishing
while
STOP becomes due
```

or duplicate receivers.

State transitions must validate current persisted session state before executing.

Do not assume alarms cannot overlap.

---

# 87. Clock Abstraction

Pure logic should not call:

```kotlin
Instant.now()
System.currentTimeMillis()
```

directly everywhere.

Use an injected clock abstraction where testability matters.

Example:

```kotlin
interface LenswakeClock {
    fun now(): Instant
}
```

---

# 88. Device State Abstraction

System state should be accessible through an interface.

Example:

```kotlin
interface DeviceStateProvider {

    suspend fun snapshot(): DeviceState

    suspend fun isInteractive(): Boolean

    suspend fun isKeyguardLocked(): Boolean

    suspend fun foregroundPackage(): String?
}
```

This improves testing and keeps Android framework access localized.

---

# 89. Pixel Camera State Model

Avoid exposing raw UI hierarchy as application state.

Infer semantic state:

```kotlin
sealed interface PixelCameraState {

    data object NotRunning : PixelCameraState

    data object Unknown : PixelCameraState

    data class Photo(
        val details: CameraModeDetails,
    ) : PixelCameraState

    data class Video(
        val details: CameraModeDetails,
    ) : PixelCameraState

    data class TimeLapse(
        val speed: TimeLapseSpeed?,
        val recording: Boolean,
    ) : PixelCameraState

    data object RecordingUnknownMode : PixelCameraState
}
```

Exact design may evolve.

The principle is to reason semantically.

---

# 90. Desired-State Automation

Where practical, implement automation as convergence toward desired state.

Instead of:

```text
always tap Video
always tap Time Lapse
always tap 120×
```

do:

```text
inspect
    ↓
desired mode already active?
    ↓ yes
skip
    ↓ no
perform minimal transition
```

This improves recovery and idempotency.

---

# 91. Interaction Methods Must Be Traceable

Represent interaction method explicitly:

```kotlin
enum class InteractionMethod {
    ACCESSIBILITY_ACTION,
    ACCESSIBILITY_GESTURE_NODE_BOUNDS,
    ACCESSIBILITY_GESTURE_PROFILE,
    PRIVILEGED_INPUT,
}
```

Store this in execution diagnostics.

Fallback frequency is a useful reliability metric.

---

# 92. Avoid Magic Delays

Small debounce/poll intervals may be necessary.

Centralize them.

Bad:

```kotlin
delay(137)
delay(500)
delay(2000)
```

scattered across code.

Prefer:

```kotlin
automationConfig.uiSettlementDelay
automationConfig.selectorPollInterval
```

More importantly, wait for conditions rather than fixed time whenever possible.

---

# 93. Polling

Polling an accessibility condition is acceptable when event-driven detection is unreliable, provided it is:

- bounded;
- low-frequency;
- cancellable;
- scoped to an active automation operation.

Do not poll continuously in the background.

---

# 94. Repositories and External References

When introducing third-party libraries:

1. verify active maintenance;
2. verify Android 17 compatibility;
3. keep dependency surface small;
4. document why the library is needed.

Do not add libraries for trivial utilities that can be implemented clearly in a few lines.

---

# 95. Shizuku API Integration

Follow the current Shizuku API rather than random online snippets.

Do not copy legacy Shizuku examples without verifying API compatibility.

Keep binder/service lifecycle handling robust:

```text
binder unavailable
permission missing
service died
user service disconnected
timeout
```

All must be normal recoverable states.

---

# 96. No Root-Specific Implementation

Do not introduce root shell commands as the primary architecture.

The supported target is non-root.

If a root backend is ever added experimentally, it must remain completely optional and separate.

---

# 97. Avoid Google Play Constraints Driving Architecture

Lenswake is a personal sideloaded application.

Do not compromise core functionality solely to satisfy Play policy requirements unless the user later changes the distribution goal.

However, still follow Android security architecture and avoid unnecessary privilege.

---

# 98. Compatibility Surface

Explicitly distinguish:

```text
Supported
Tested
Experimental
Unknown
```

Example:

```text
Pixel 8 Pro / Android 17 / Camera version X
TESTED

Pixel 9 Pro / Android 17
UNKNOWN
```

Do not infer device support merely because code compiles.

---

# 99. Documentation Must Match Reality

If implementation changes a core behavior:

- update README;
- update architecture docs;
- update compatibility notes;
- update setup requirements.

Stale documentation is a reliability bug in this project.

---

# 100. First Implementation Priority

Unless the repository already progressed beyond these steps, prioritize work in this order:

```text
1. Inspect current project state.

2. Establish Android project/toolchain.

3. Implement environment diagnostics.

4. Verify secure Pixel Camera launch manually.

5. Implement Pixel Camera accessibility tree explorer.

6. Characterize actual Pixel Camera nodes.

7. Model semantic Pixel Camera state.

8. Implement one manual transition at a time.

9. Build deterministic:
   Pixel Camera → Video → Time Lapse → selected speed.

10. Implement Record + verification.

11. Implement Stop + verification.

12. Introduce formal automation state machine.

13. Implement locked-screen wake/launch.

14. Implement exact START scheduling.

15. Implement exact STOP scheduling.

16. Persist sessions and restore alarms after reboot.

17. Add Shizuku privileged fallback.

18. Add calibration profiles.

19. Add rehearsal.

20. Harden failure/recovery paths.
```

Do not jump directly to a polished scheduling UI before the underlying Pixel Camera automation is proven.

---

# 101. Minimum Viable End-to-End Milestone

The first meaningful milestone is:

```text
Given:
- Pixel 8 Pro
- Android 17
- supported Pixel Camera version
- completed initial setup
- Accessibility enabled
- profile verified
- device mounted and locked
- screen off

When:
- a schedule reaches its start time

Then:
- Lenswake wakes the phone
- opens native Pixel Camera
- enters Video → Time Lapse
- chooses 120×
- starts recording
- confirms recording

And:
- at the stop time
- Lenswake wakes the phone if necessary
- stops the Pixel Camera recording
- confirms stop
- records SUCCESS in execution history
```

Until this flow works repeatedly on the physical target device, broad feature expansion is secondary.

---

# 102. Final Rule

When choosing between a solution that is:

```text
shorter but implicit
```

and one that is:

```text
slightly larger but deterministic,
observable, recoverable, and testable
```

choose the latter.

Lenswake operates unattended.

A failure may mean losing a sunrise, sunset, night sequence, or other event that cannot simply be repeated.

Reliability is therefore a product feature, not an implementation detail.