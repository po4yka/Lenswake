# Pixel 8 Pro baseline — 2026-08-09

## Purpose

Verify the current Lenswake foundation, runtime preflight, and Pixel Camera automation signals on
the explicitly supported physical device. The investigation grants only the app capabilities needed
by the current implementation and records empirically observed Pixel Camera selectors.

## Environment

```text
Device:                  Google Pixel 8 Pro (husky)
Android:                 17 / SDK 37
Build:                   CP2A.260705.006
Build fingerprint:       google/husky/husky:17/CP2A.260705.006/15641320:user/release-keys
Display:                 1008 x 2244 px, 360 dpi
Pixel Camera package:    com.google.android.GoogleCamera
Pixel Camera version:    10.4.117.936816638.14 (versionCode 69481630)
Lenswake baseline:       1a153da
Durable rehearsal code: 17dcb4a
DEVICE_WAKE code:       364d73c
Physical wake fixture:  f2dcf3b
Physical acceptance:    65a5236
Installed APK SHA-256:  926218feb27b4778f76e35bc786c6bbfd93ed259838a0bcc4c6b4c084728bcb3
Lenswake version:        0.1.0 debug
```

The exact ADB serial was selected for every command through `ANDROID_SERIAL` or `adb -s`; it is not
stored in this note.

Initial observed state:

```text
Screen interactive:                  yes
Keyguard showing:                    no
Exact-alarm special access:          not granted
Lenswake Accessibility Service:      not enabled / not connected
POST_NOTIFICATIONS:                  not granted
Pixel Camera secure intent:          resolvable
Persisted Pixel Camera profile:      exact-environment candidate, needs rehearsal
Recorded physical rehearsal:         none
```

Final granted app capabilities:

```text
Exact-alarm special access:          granted through Android Settings UI
Lenswake Accessibility Service:      enabled through Android Settings UI
Accessibility runtime connection:    connected
POST_NOTIFICATIONS:                  granted through package manager
USE_FULL_SCREEN_INTENT AppOp:        allowed for the sideloaded alarm application
```

The existing Bitwarden Accessibility Service remained enabled. No lock-screen credential, camera
permission, Shizuku permission, or unrelated system capability was changed.

## Reproduction

```bash
adb devices -l
adb -s "$PIXEL_SERIAL" shell getprop ro.product.model
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.release
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.sdk
adb -s "$PIXEL_SERIAL" shell getprop ro.build.fingerprint
adb -s "$PIXEL_SERIAL" shell dumpsys package com.google.android.GoogleCamera
adb -s "$PIXEL_SERIAL" shell cmd package resolve-activity \
  --brief -a android.media.action.STILL_IMAGE_CAMERA_SECURE

ANDROID_SERIAL="$PIXEL_SERIAL" ./gradlew \
  :data:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest \
  --rerun-tasks --console=plain

android run \
  --apks=app/build/outputs/apk/debug/app-debug.apk \
  --device="$PIXEL_SERIAL" \
  --type=ACTIVITY \
  --activity=dev.po4yka.lenswake.MainActivity

android layout --device="$PIXEL_SERIAL" --pretty

adb -s "$PIXEL_SERIAL" shell am start \
  -a android.media.action.STILL_IMAGE_CAMERA_SECURE \
  -p com.google.android.GoogleCamera \
  -c android.intent.category.DEFAULT

adb -s "$PIXEL_SERIAL" shell input keyevent KEYCODE_SLEEP
adb -s "$PIXEL_SERIAL" shell dumpsys battery unplug
adb -s "$PIXEL_SERIAL" shell cmd deviceidle force-idle
adb -s "$PIXEL_SERIAL" shell am instrument -w \
  -e physicalDeviceWakeOnly true \
  -e class \
  'dev.po4yka.lenswake.alarm.PhysicalDeviceWakeFixtureTest#wakeLockedDisplayOnlyWhenExplicitlyRequested' \
  dev.po4yka.lenswake.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$PIXEL_SERIAL" shell cmd deviceidle unforce
adb -s "$PIXEL_SERIAL" shell dumpsys battery reset
```

## Results

- Room and data instrumentation: 7/7 passed on Pixel 8 Pro / Android 17 after the selector
  schema-v2 checked-state change.
- App instrumentation: 35/35 passed for the baseline; the focused late-collector preflight suite
  then passed 2/2 on the physical Pixel after the Android 17 race fix.
- Local JVM tests, Android-test compilation, `lintDebug`, and `assembleDebug` passed.
- The debug APK installed and `MainActivity` activated successfully.
- The installed `base.apk` was pulled back and compared byte-for-byte with the local artifact.
- The Profiles UI installed one exact-environment candidate with compatibility
  `NEEDS_REHEARSAL`; it did not promote the candidate to `VERIFIED`.
- The candidate survived a Lenswake `force-stop` and cold activity restart, demonstrating that it
  was loaded from Room rather than retained only in process memory.
- Exact-alarm AppOp, notification runtime permission, Accessibility enabled-service registration,
  and the bound Accessibility service were read back from Android after granting them.
- The secure camera intent opened
  `com.google.android.GoogleCamera/com.android.camera.SecureCameraActivity` without a hard-coded
  activity class.
- Several deliberately short 120x Time Lapse clips were started and stopped while discovering
  postconditions. Pixel Camera, not Lenswake, created the corresponding local media files.

```text
SHA-256:
acb7d360acc00ce11f120de2a3427f3bd2b1d365fb8185ca7a4c32f8d903f977

cmp result:
identical
```

Runtime preflight correctly reported:

- Pixel Camera installed: available, versionCode `69481630`;
- secure Pixel Camera launch: available, dynamically resolved to
  `com.google.android.GoogleCamera/com.android.camera.SecureCameraActivity`;
- exact alarms: available after the grant;
- Lenswake Accessibility Service enabled: available after the grant;
- Accessibility runtime connection: available after service attachment;
- profile availability: available after installing one persisted candidate;
- profile compatibility: blocked because the exact-environment candidate still needs rehearsal;
- physical rehearsal: unknown and blocking;
- privileged fallback: unknown and optional.

The Setup screen was visually inspected at the physical display resolution. Content remained
readable and scrollable, status was communicated by text and glyph rather than color alone, and no
clipping was observed.

## Candidate profile installation

The app offered installation only after the runtime environment exactly matched the observed
Pixel 8 Pro baseline. The installed profile has deterministic identity:

```text
google-pixel-8-pro-sdk37-cp2a-260705-006-camera-69481630-1008x2244-en-us-v2
```

Its environment binds the Pixel 8 Pro model, SDK 37, build `CP2A.260705.006`, Pixel Camera
versionCode `69481630`, `1008 x 2244` display, current locale, and selector schema v2. Installation
changed the required readiness count from three to two. The two remaining gates were inspected in
the Setup UI and are intentional:

1. profile compatibility is blocked until rehearsal;
2. no successful physical-device rehearsal is recorded for the current environment.

The production rehearsal action now uses a persisted execution session and arms an independent,
session-bound exact STOP alarm before START automation. A successful production rehearsal promotes
only the exact profile and environment exercised by the complete start/stop proof.

After a deliberate `force-stop`, Android removed Lenswake from enabled Accessibility services. The
profile was still present after the new process started. Accessibility was then re-enabled through
the Android confirmation UI, both Lenswake and Bitwarden were observed bound, exact-alarm access
remained allowed, and readiness returned to the expected two blockers.

## Durable rehearsal and process-death proof

A normal production rehearsal completed as session
`c871531e-72fa-475d-93b2-3216e17933a1`. Room recorded the following write-ahead and verification
timestamps:

```text
record action:       1786294067638
recording verified:  1786294067764
stop action:         1786294077879
stopped verified:    1786294078385
```

The independent safety alarm was cancelled only after the stopped postcondition was persisted, and
the UI showed the exact profile as `VERIFIED` with a passed production rehearsal.

Process-death recovery was then exercised with session
`661e02c5-de52-45a1-91a6-4c3aa10963c9`. The replacement process and alarm were observed with:

```bash
adb -s "$PIXEL_SERIAL" shell pidof dev.po4yka.lenswake
adb -s "$PIXEL_SERIAL" shell run-as dev.po4yka.lenswake kill -9 1880
adb -s "$PIXEL_SERIAL" shell pidof dev.po4yka.lenswake
adb -s "$PIXEL_SERIAL" shell dumpsys alarm
```

Pixel Camera was visibly recording when PID `1880` died at 21:36:08. Android recreated the bound
Accessibility service in PID `3068`; the exact `REHEARSAL_STOP` PendingIntent remained registered
for 21:37:43. The alarm then delivered independently, dispatched the semantic stop action, and
verified the non-recording Pixel Camera state at 21:37:44. Room contains:

```text
session status:       FAILED
record action:        1786296965787
recording verified:   null
stop action:          1786297063833
stopped verified:     1786297064343
terminal event:       automation.record.stop_verified_after_failure
```

`FAILED` is intentional: the process died before START verification could be persisted. The safety
cleanup stopped the Lenswake-owned recording but did not manufacture successful rehearsal evidence
or promote a profile. The safety deadline is conservative (START budget + requested duration +
margin), so a process-death cleanup may occur later than the requested ten-second rehearsal.

## Locked-display DEVICE_WAKE proof

The first durable alarm transport experiment used an Activity `PendingIntent`. Android 17 delivered
the exact RTC alarm in forced deep idle but rejected the background Activity launch. Activity task
manager reported `BAL_BLOCK`: the app opted the pending-intent creator into background launch, but
the AlarmManager system sender did not provide the required sender opt-in. This transport was
therefore removed rather than treated as a device-specific exception.

The production design now keeps START, STOP, rehearsal STOP, retry, and rearm alarms service-bound.
`AutomationExecutionService` journals and validates the durable alarm before automation begins.
Only the `DEVICE_WAKE` operation posts a local, silent, high-importance alarm notification whose
immutable full-screen intent targets a private, bounded gateway Activity. The gateway uses
`setShowWhenLocked(true)` and `setTurnScreenOn(true)`; it does not dismiss keyguard, acquire a wake
lock, forward alarm work, or own recording. The controller reports success only after
`PowerManager.isInteractive` becomes true and cancels the notification on every terminal path.

The corrective service transport was also observed receiving its exact alarm from forced deep idle.
That schedule then failed closed before `DEVICE_WAKE` because reinstalling the development APK had
removed the previously verified Room profile. The coordinator correctly rejected the unattended
START with `Unattended execution requires a verified Pixel Camera profile`; it did not start a
recording under an unverified profile.

The wake primitive itself was subsequently exercised through the guarded production-controller
instrumentation fixture. Its asserted initial state was:

```text
PowerManager wakefulness: Dozing
Device idle state:         IDLE / OVERRIDE
Keyguard deviceLocked:     true
```

The one-test run passed in 0.319 seconds. Its production log and post-run system state were:

```text
Physical DEVICE_WAKE passed interactive=true deviceLocked=true
PowerManager wakefulness: Awake
Keyguard deviceLocked:     true
Wake notification channel: importance HIGH, sound null, vibration disabled
```

This proves the `DEVICE_WAKE` postcondition on the target Pixel while preserving the locked
security state. An earlier production rehearsal additionally showed the wake path followed by the
dynamically resolved secure Pixel Camera Activity while `deviceLocked=1`; that rehearsal then
failed safely at `SELECT_TIME_LAPSE_SPEED`. Repeating it produced the same selector failure, so the
profile/selectors remain a separate automation-calibration issue and were not weakened to obtain a
green wake result. The later acceptance run recorded below used a corrected profile interaction
flow and supersedes that historical selector blocker.

## Observed Pixel Camera selectors

All selectors below were observed on the environment recorded above. They are not claimed to be
stable on another Pixel Camera version, locale, build fingerprint, or display configuration.

| Purpose | Observed semantic signal |
| --- | --- |
| Select Video | content description `Video` on the `video_supermode` clickable parent |
| Select Time Lapse | `mode_chip_text`, text `Time Lapse`, description `Switch to Time Lapse Mode` |
| Time Lapse active | `mode_chip_text`, text/description `Time Lapse`, selected state `true` |
| Open speed control | description `Time Lapse control` on a clickable parent |
| Select 120x | description `Time Lapse 120 times speed` |
| Rear main lens active | `zoom_toggle_1×`, text `1×`; its clickable parent is checked |
| Start action | `ComposeShutter`, description `Start time lapse` |
| Recording active | `ComposeShutter`, description `Stop time lapse` |
| Recording corroboration | `pause_resume_button`, description `Pause recording` |
| Recording progress | `recording_timer`, description advances from `Recording time 1 second` |
| Stop action | `ComposeShutter`, description `Stop time lapse` |
| Stopped postcondition | `ComposeShutter`, description returns to `Start time lapse` |

Action dispatch and postcondition observation remained separate. Recording was not considered
started merely because a tap was submitted; the stop control, pause control, and advancing timer
were all observed. After stop, the start control returned and the recording controls disappeared.

## Android 17 Accessibility instrumentation finding

Starting UIAutomator for `android layout` temporarily disconnected and reconnected enabled
Accessibility services on this Android 17 build. That behavior briefly changed runtime preflight
from available to blocked and exposed a Lenswake race: a late collector dropped the current
connection state. Commit `d114256` removes that dropped initial emission and adds a physical-device
instrumentation regression test. Process-death validation confirmed that Android recreated the
bound service and delivered its connection callback in the replacement process. Final readiness
and alarm recovery were verified using screenshots without running UIAutomator in the automation
window.

## Locked exact-alarm and reboot-recovery acceptance — 2026-08-10

Two production schedule sessions closed the remaining target-device acceptance gates. Both used
the installed APK SHA-256 recorded above, the exact environment in this note, independent exact
START and STOP alarms, the normal alarm foreground service, `DEVICE_WAKE`, secure Pixel Camera,
profile selectors, and persisted execution events. No UIAutomator process ran during either
automation window.

The first session started with the display off, keyguard locked, Pixel Camera cold, and device idle
forced to `IDLE`:

```text
Schedule:             988bf1b7-a9ce-4ed5-89cc-7bcb88f695d8
Execution session:    b6c5aa05-c432-3f6e-9d8b-fb8b1e8363cc
START delivered:      2026-08-10T06:07:20Z
Record dispatched:    2026-08-10T06:07:27Z
Recording verified:   2026-08-10T06:07:27Z
STOP delivered:       2026-08-10T06:08:20Z
Stop dispatched:      2026-08-10T06:08:20Z
Stopped verified:     2026-08-10T06:08:21Z
Terminal status:      COMPLETED
```

The reboot scenario persisted an enabled future schedule, rebooted the phone, observed
`LOCKED_BOOT_COMPLETED`, and restored alarms only after the user's normal unlock produced
`USER_UNLOCKED`. The phone was then returned to screen-off, locked, cold-Camera, forced-idle state
before START:

```text
Schedule:             8c184b26-6b15-4008-914b-50c87626ab6b
Execution session:    2352753d-ae7a-370d-b316-c6826b9ce875
START delivered:      2026-08-10T14:09:23Z
Record dispatched:    2026-08-10T14:09:29Z
Recording verified:   2026-08-10T14:09:30Z
STOP delivered:       2026-08-10T14:10:23Z
Stop dispatched:      2026-08-10T14:10:23Z
Stopped verified:     2026-08-10T14:10:24Z
Terminal status:      COMPLETED
```

The event streams show `DEVICE_WAKE` through the standard Android API, semantic picker close,
Record dispatch and verification, and independent Stop dispatch and verification. Test fixtures
were deleted through the production schedule workflow afterward; forced idle and battery overrides
were reset, and Lenswake plus the pre-existing Bitwarden Accessibility Service were restored.

This proof is tied to the installed APK SHA and commit `65a5236`. Later fail-closed scheduling,
resource-readiness, ownership, process-death picker recovery, and semantic-fingerprint hardening are
covered by local automated gates; they do not retroactively change the identity of the physically
tested artifact.

## Conclusion and remaining gates

The application reports observed device readiness instead of static setup placeholders. Required
Android permissions, semantic Pixel Camera start/stop signals, a normal production rehearsal, and
session-bound process-death STOP recovery are now verified on this Pixel. The exact-environment
profile was promoted only by the complete normal rehearsal; the incomplete process-death START
remained failed even though its safety cleanup succeeded.

Continuous locked/Doze exact-alarm START-to-STOP and reboot-before-START recovery are physically
verified for the recorded Pixel environment and installed APK identity. The remaining deliberately
unverified capabilities are notification-less escalation behavior and optional Shizuku support;
neither is required by the verified standard-API target path.
