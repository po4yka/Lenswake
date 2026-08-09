# Pixel 8 Pro baseline — 2026-08-09

## Purpose

Verify the current Lenswake foundation and runtime preflight on the explicitly supported physical
device without granting special access, launching a recording, or assuming Pixel Camera internals.

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

No system setting or sensitive capability was enabled through ADB.

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
```

## Results

- Room and data instrumentation: 7/7 passed on Pixel 8 Pro / Android 17.
- App instrumentation: 35/35 passed on Pixel 8 Pro / Android 17 at the final code state.
- Local JVM tests, Android-test compilation, `lintDebug`, and `assembleDebug` passed.
- The debug APK installed and `MainActivity` activated successfully.
- The installed `base.apk` was pulled back and compared byte-for-byte with the local artifact.

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
- exact alarms: blocked;
- Lenswake Accessibility Service enabled: blocked;
- Accessibility runtime connection: blocked;
- profile availability and current compatibility: blocked;
- physical rehearsal: unknown and blocking;
- privileged fallback: unknown and optional.

The Setup screen was visually inspected at the physical display resolution. Content remained
readable and scrollable, status was communicated by text and glyph rather than color alone, and no
clipping was observed.

## Conclusion and remaining gates

The application now reports observed device readiness instead of static setup placeholders and
remains fail-closed on this Pixel. This run does **not** establish recording automation reliability.

The following remain deliberately unverified:

1. exact-alarm grant and delivery while screen-off, locked, and in Doze;
2. Accessibility enablement, service connection, and Pixel Camera tree inspection;
3. selector calibration for Pixel Camera `69481630`;
4. real Time Lapse start and postcondition verification;
5. independent stop alarm and stopped-state verification;
6. process-death and reboot recovery on the physical device;
7. runtime notification permission and notification-less escalation behavior;
8. optional Shizuku capability on Android 17.

Secure-camera resolution was observed without dispatching the camera because no verified profile or
Accessibility capability existed. No START or STOP success is claimed.
