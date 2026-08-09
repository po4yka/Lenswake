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
Lenswake commit:         047a4937c8b1
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
Persisted Pixel Camera profile:      none
Recorded physical rehearsal:         none
```

Final granted app capabilities:

```text
Exact-alarm special access:          granted through Android Settings UI
Lenswake Accessibility Service:      enabled through Android Settings UI
Accessibility runtime connection:    connected
POST_NOTIFICATIONS:                  granted through package manager
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
```

## Results

- Room and data instrumentation: 7/7 passed on Pixel 8 Pro / Android 17.
- App instrumentation: 35/35 passed on Pixel 8 Pro / Android 17 at the final code state.
- Local JVM tests, Android-test compilation, `lintDebug`, and `assembleDebug` passed.
- The debug APK installed and `MainActivity` activated successfully.
- The installed `base.apk` was pulled back and compared byte-for-byte with the local artifact.
- Exact-alarm AppOp, notification runtime permission, Accessibility enabled-service registration,
  and the bound Accessibility service were read back from Android after granting them.
- The secure camera intent opened
  `com.google.android.GoogleCamera/com.android.camera.SecureCameraActivity` without a hard-coded
  activity class.
- Several deliberately short 120x Time Lapse clips were started and stopped while discovering
  postconditions. Pixel Camera, not Lenswake, created the corresponding local media files.

```text
SHA-256:
84858973ae8ec8f08e7e6740d71669dabfbfc06d65157fd3ff89fcfcbac43384

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
- profile availability and current compatibility: blocked;
- physical rehearsal: unknown and blocking;
- privileged fallback: unknown and optional.

The Setup screen was visually inspected at the physical display resolution. Content remained
readable and scrollable, status was communicated by text and glyph rather than color alone, and no
clipping was observed.

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
instrumentation regression test. Final readiness was therefore verified without leaving
UIAutomator connected.

## Conclusion and remaining gates

The application reports observed device readiness instead of static setup placeholders. Required
Android permissions and semantic Pixel Camera start/stop signals are now verified on this Pixel,
but the application remains fail-closed until a profile is persisted and a production-stack
rehearsal is recorded.

The following remain deliberately unverified:

1. exact-alarm delivery while screen-off, locked, and in Doze;
2. persisted selector profile for Pixel Camera `69481630`;
3. production-stack rehearsal rather than the bounded ADB calibration run;
4. independent stop alarm and stopped-state verification;
5. process-death and reboot recovery on the physical device;
6. notification-less escalation behavior;
7. optional Shizuku capability on Android 17.

The manual calibration proves Pixel Camera behavior and selectors for this exact environment. It
does not yet prove that an alarm-driven Lenswake execution can complete the same sequence while the
screen is off and the keyguard is locked.
