# Pixel 8 Pro release installation and smoke check, 2026-09-09

## Artifact and environment

- Source: clean `main@2714728b6ee85cfaf175c1eb88d32c6277eff36d`; Lenswake 0.1.0 (1).
- APK SHA-256: `877fd5eeee93a85a1ba9bb0fcd22506d242735c714b9677587b14e700dc8384d`.
- Signing certificate SHA-256: `9D66A5BAC93DBFAE9A2EC05E7D11585477E4C84DE5C352DD98976016FC1C3568`.
- Device: Google Pixel 8 Pro / husky; every ADB command explicitly selected its serial, omitted here.
- Android 17 / API 37: `google/husky/husky:17/CP2A.260805.005/15828068:user/release-keys`.
- Installed Pixel Camera: `com.google.android.GoogleCamera`, 10.4.117.936816638.14, versionCode 69481630.
  Package-part hashes were not collected in this UI-only check; no selector or capture claim is made.
- Display: 1008 × 2244, density 360; locale `en-US-u-fw-mon-mu-celsius`.
- Observation: approximately 14:16–14:20, UTC+04:00, on 2026-09-09.

## Actions and observed result

1. Installed with `adb -s <selected-device> install -r app/build/outputs/apk/release/app-release.apk`:
   `Success`. Pulled the installed `base.apk`; its SHA-256 exactly matched the local APK above.
2. Opened `dev.po4yka.lenswake/.MainActivity` with `am start -W`: `Status: ok`.
   Initial app state had no schedules, an installed untested Experimental profile, and no bound
   Lenswake Accessibility service. Screen was on, unlocked, and device idle state was `ACTIVE`.
3. Inspected Schedules, Setup, Profiles and Diagnostics using raw `exec-out screencap -p` snapshots
   and visible-target taps. All opened; no new crash or ANR appeared in package exit history.
   Setup showed three required checks: Accessibility runtime connection, profile test, and camera test.
4. Opened Accessibility settings through Resolve, switched the existing Lenswake service off,
   confirmed Turn off, and switched it back on. Its enabled state was restored, but Lenswake
   remained absent from bound services, with no pending binding or crashed service.
5. Android logs again reported `bindService exceeded max service connection number per process,
   callerApp:system`, including Lenswake's `AlarmRecoveryService` and unrelated system-managed jobs.
   The origin of the exhausted system limit is not established by this observation.
6. Profiles retained the Experimental profile (`5ff50e8d12ef…` displayed fingerprint prefix).
   Test recording was disabled with the explicit disconnected-service explanation. No recording,
   START/STOP, saved-media result, or new profile receipt was produced. Full profile fingerprint
   was not retrieved because the capture test could not begin.

## Cleanup and boundary

The service was left enabled as initially configured. No schedules, alarms, media, permission grants,
idle overrides, or battery overrides were created by the check; `dumpsys alarm` contained no Lenswake
entry. Device remained screen-on with Lenswake Diagnostics foreground. No reboot was performed;
reboot authorization and subsequent manual unlock were requested for the recovery check.

The prior rebuild passed `check assembleDebug`; a forced `:app:assembleRelease --rerun-tasks`
executed all 92 actionable tasks successfully through `build-gate`. The APK verifier passed.
This record establishes installation and UI smoke behavior only. Camera operation and unattended
reliability remain blocked and unverified on these exact installed bytes. No connected
instrumentation or full physical acceptance matrix ran.
