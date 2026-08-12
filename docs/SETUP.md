# Lenswake setup

Lenswake is a personal sideloaded app for an explicitly calibrated Pixel Camera environment. Setup
must complete both Android capability grants and a production rehearsal; installing the APK alone
does not make unattended schedules safe.

## Developer prerequisites

- JDK 17 toolchain;
- Android SDK Platform 37 and current platform tools;
- USB debugging and ADB authorization;
- a selected device serial when more than one device is connected.

Build and install:

```bash
./gradlew :app:assembleDebug
adb -s "$PIXEL_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatively:

```bash
ANDROID_SERIAL="$PIXEL_SERIAL" ./gradlew :app:installDebug
```

## Required Android capabilities

The app does not request `CAMERA` or `RECORD_AUDIO`; Pixel Camera owns capture. The checked-in
manifest declares the following runtime/special capabilities:

| Capability | Why it is needed | Behavior without it |
| --- | --- | --- |
| Exact alarms | Independent user-defined START and STOP deadlines | Enabled schedules are blocked; no inexact fallback |
| Notifications | Bounded execution status, wake alarm notification, transport failure notification | Wake/readiness is blocked |
| Full-screen intent | Turns on a non-interactive locked display through the private wake gateway | Screen-off wake is blocked; already-interactive flow can still pass wake |
| Accessibility Service | Inspect and operate only Pixel Camera UI | Automation is blocked |
| Full video-library access | Query MediaStore for the new Pixel Camera-owned video after STOP | New session completion is blocked |
| Boot completed | Restore future work after normal unlock | Reboot restoration is unavailable |
| Foreground service/system-exempted type | Bounded exact-alarm delivery handoff | Alarm work cannot enter its production service path |

`READ_MEDIA_VISUAL_USER_SELECTED` is declared for Android's selected-media model, but selected-only
access is insufficient for unattended saved-file verification. Grant full video-library access.

## First-run flow

1. Open Lenswake and enter **Setup** from Schedules.
2. Grant notification permission. If notifications or the wake channel are disabled, open app
   notification settings from the remediation action and enable them.
3. Open exact-alarm settings and allow exact alarms for Lenswake.
4. Open full-screen-intent settings and allow Lenswake to use full-screen intents.
5. Grant full video-library read permission.
6. Open Accessibility settings, choose Lenswake, review Android's warning, and enable the service.
7. Return to Lenswake and verify both **Accessibility enabled** and **Accessibility connected** pass.
8. Open Profiles. Install the profile only if Lenswake reports an exact supported environment match.
   Experimental models require a separate explicit risk confirmation.
9. Run the sequential profile capture matrix. Keep the phone in a safe position: Pixel Camera will really open, record,
   stop, and publish a short video.
10. Create a schedule. Only capture combinations with current qualifying rehearsal evidence are shown.
11. Enable the schedule after every blocking readiness check passes.

## Readiness interpretation

Blocking checks include exact alarms, notifications, media access, full-screen intent, Pixel Camera,
secure launch, device wake, Accessibility enabled/connected, profile availability/compatibility,
current rehearsal, battery, and unknown resource state.

Known lack of charging and known storage below the current advisory threshold are warnings. Battery
below the application threshold blocks. Shizuku/privileged fallback remains an optional warning
because it is not part of the supported standard path.

## Environment mismatch

The profile requires an exact match for model, codename, Android SDK/build fingerprint, Camera
package/versionCode, en-US locale, portrait orientation, display dimensions/density, font scale,
selector schema, template provenance and version. Any drift invalidates prior receipts.

The fixed registry contains Pixel 6/6 Pro/6a, 7/7 Pro/7a, 8/8 Pro/8a, 9/9 Pro/9 Pro XL/9a and
10/10 Pro/10 Pro XL/10a. Fold, Pro Fold, Tablet, Pixel 5a, unknown and future models are rejected.
Experimental support never becomes Certified after local testing.

Do not edit Room state or mark the profile verified manually. Recalibration/profile authoring is not
implemented; collect reproducible evidence and update the profile through source plus tests.

## Before an unattended run

- Mount and power the Pixel safely.
- Confirm the desired schedule and time zone.
- Confirm Lenswake reports no blockers.
- Run schedule-bound **Test now** after any relevant environment/profile change.
- Ensure Pixel Camera has storage and can record normally.
- Leave keyguard locked; Lenswake neither needs nor supports unlocking it.

For development scenarios and device cleanup, use
[testing/PHYSICAL_PIXEL.md](testing/PHYSICAL_PIXEL.md).
