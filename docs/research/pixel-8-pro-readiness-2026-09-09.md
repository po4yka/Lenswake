# Pixel 8 Pro readiness correction, 2026-09-09

## Scope and artifact

This observation covers Setup accuracy and its profile-remediation navigation. It does not certify
camera capture, saved media, unattended operation, or any profile.

- Device: Google Pixel 8 Pro, `husky`, explicitly selected with `adb -s`; personal serial omitted.
- Android: 17 / API 37, `google/husky/husky:17/CP2A.260805.005/15828068:user/release-keys`.
- Pixel Camera: `com.google.android.GoogleCamera`, `10.4.117.936816638.14`, versionCode `69481630`.
- Display: 1008 x 2244, density 360, portrait; device locale `en-US-u-fw-mon-mu-celsius`.
- Lenswake: `0.1.0` (1), source commit `398a97d207d886861ce39f1f8f613ee3180cbbab`.
- Signed release APK SHA-256: `ef0532ac8118a3bfd32b2b32a175281915b76ba9665df5b654aaae11249871aa`.
- Signing certificate SHA-256: `9D66A5BAC93DBFAE9A2EC05E7D11585477E4C84DE5C352DD98976016FC1C3568`.
- The installed `base.apk` was pulled and its SHA-256 matched the local release APK exactly.
  AGP's optional VCS metadata reports `NO_VALID_GIT_FOUND` in this worktree; source identity above
  comes from the clean checkout and build command, not from an embedded commit claim.

## Reproduced failures

The initial app was in Setup with an installed but untested current Pixel 8 Pro profile and no
schedules. The previous installed release SHA-256 was
`342f2064e6b340c7e28476c49847a19d72419eaeaf993b38dcc8daab2578eebc`.

1. `dumpsys battery` reported USB power connected, level 100, status 4 (`NOT_CHARGING`). Setup
   nevertheless showed a red **Charging / Blocked / Optional** item. The adapter read charging
   activity instead of the plugged-in state.
2. The profile matched the current environment but had not been rehearsed. Setup incorrectly said
   the camera environment had changed. Neither this check nor Camera test provided a resolution
   action. In Profiles, Test recording was below the full capture matrix.
3. The unimplemented privileged provider appeared as an unresolved optional feature instead of
   explicitly stating that this build does not use it.

A live screen/battery comparison reproduced the first two failures. Three newly added unit tests
failed on the old code before implementation. All targeted regression tests then passed.

## Observed correction

The final artifact was installed with `adb -s <selected-device> install -r`, then opened with
`am start -W -n dev.po4yka.lenswake/.MainActivity`. Existing data and permissions were preserved.
Setup was inspected with raw `exec-out screencap -p` snapshots and bounded scroll/tap actions.

- Connected USB power no longer appeared in outstanding checks.
- The exact untested profile explicitly requested its first Test recording.
- Both profile compatibility and Camera test exposed **Resolve** actions.
- Resolve opened Profiles. Test recording appeared before the capture matrix, and navigation did
  not start a recording or grant a permission.
- Accessibility remained a real blocking condition when the service was not connected. Its message
  now explains enabling/reconnecting the service and restarting/unlocking the phone if necessary.
- Privileged fallback is informational, marked **Not used**, in **Optional features**. Its absence
  does not change readiness to a warning.

## Separate Android service problem

After APK replacement, `dumpsys accessibility` showed Lenswake enabled but no bound service and no
crashed service. ActivityManager also repeatedly reported
`bindService exceeded max service connection number per process, callerApp:system`, including for
Lenswake's recovery job and multiple unrelated system-managed jobs. This establishes a system-wide
binding failure; it does not establish which component originally exhausted the limit.

An earlier Accessibility disconnect was transient during UI automation. Android documents that
UiAutomation suppresses Accessibility services by default. Later raw screenshots and service-state
checks avoided treating that diagnostic side effect as an application failure.

No reboot, service toggle, recording, schedule creation, permission change, battery override, or
idle override was performed during this observation. A reboot and manual unlock remain a separate
recovery check; the app never treats an enabled-but-disconnected service as ready.

## Validation and limits

- Full local gate passed: `build-gate -- ./gradlew check assembleDebug :app:assembleDebugAndroidTest
  :data:assembleDebugAndroidTest :app:assembleRelease --max-workers=4 --no-configuration-cache`.
- Host tests: 396 passed, zero failed or skipped (core 54, automation 81, app 261).
- Both Android-test APKs compiled; connected instrumentation was not run on this physical phone.
- `scripts/ci/verify-release-apk.sh` passed for the signed, non-debuggable release APK.
- The clean commit was built again with `:app:assembleRelease` before final installation.
- No START/STOP or saved-media postconditions were exercised. The profile remains Experimental and
  still requires actual rehearsal; this observation cannot be reused as release acceptance.

Platform references: [BatteryManager.EXTRA_PLUGGED](https://developer.android.com/reference/android/os/BatteryManager#EXTRA_PLUGGED)
and [UiAutomation service suppression](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).
