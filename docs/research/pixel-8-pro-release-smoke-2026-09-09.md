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

## Authorized reboot and production rehearsal follow-up

At approximately 14:22–14:23 UTC+04:00, the user authorized a reboot. The selected Pixel was
rebooted with `adb -s <selected-device> reboot`; `sys.boot_completed=1` was observed. The agent
waited for manual unlock and did not enter credentials. `dumpsys user` changed from
`RUNNING_LOCKED` to `RUNNING_UNLOCKED`, and Lenswake appeared in bound Accessibility services.
The runtime connection blocker disappeared; the untested profile and camera-test checks remained.

The same installed release APK was used. Pixel Camera's installed base APK SHA-256 was
`ecc35ec3ad08ba5fec7252372021b79c07469ad44d76b73f89afa63f21462b80`; its 15 installed split APKs
were individually hashed during the check. No package update or profile replacement was performed.

With the screen on and unlocked, **Profiles → Test recording** was pressed once. The production
action exercised the sequential capture matrix, including mode, speed and lens transitions. Raw
screenshots observed Pixel Camera opening and switching controls. The matrix finished with failed
and unavailable entries, including **Video · 4K · 60 FPS · Rear main: Failed**. The profile remained
Experimental and **Needs test**. This was a failed rehearsal, not a successful recording.

After the matrix finished, **Diagnostics → Export diagnostics** opened the Android share sheet.
Only the Lenswake diagnostics text was retrieved from its UI; no recipient or external sharing
target was selected. The exporter retains only ten recent sessions, so this is not the whole matrix:

| Latest ten exported sessions | Count | Observed failure stage |
| --- | --- | --- |
| `NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND` | 4 | Opening the Night Sight Time Lapse control, after three attempts |
| `UI_TARGET_CONFIDENCE_TOO_LOW` | 4 | Selecting Video, with confidence 70 below required 90 |
| `CAMERA_STATE_UNKNOWN` | 2 | Verifying a dispatched lens change, after three attempts |

These ten sessions span 10:24:56.246Z–10:25:25.936Z. The latest session is
`e0a23837-e1cf-444d-b281-117716e22950`. No Record dispatch appears in those ten traces.
An external-primary video MediaStore query restricted to `date_added >= 2026-09-09T10:23:00Z`
returned no rows. No successful START, recording, STOP, saved-media receipt, or new profile receipt
was established. The exact cause of each selector/state mismatch requires a separate targeted
investigation; these results do not authorize weakening thresholds or guessing camera controls.

Cleanup checks found no active Lenswake alarms, no execution foreground service, no active camera
clients, and no forced idle state. The share sheet was dismissed and Lenswake Diagnostics remained
foreground with the display unlocked. Accessibility was still bound after diagnostic retrieval.
No schedules, video files, battery overrides, or new permissions were created; no media deletion
was needed. Rehearsal failure history was retained for diagnosis. One ongoing notification remained:
**Scheduled alarms need attention**, reporting that Lenswake could not restore scheduled alarms.
It was posted at approximately 14:24:15 UTC+04:00 and was deliberately not suppressed; the precise
recovery failure was not established. Thus no active recording or alarm remained, but an unresolved
alarm-recovery warning did remain. Other capture scenarios, full profile fingerprint extraction,
and current-artifact release acceptance were not established by this check.
