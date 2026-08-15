# Physical Pixel validation

Physical testing is the only acceptable proof for undocumented Pixel Camera automation. It is also
state-changing: tests can wake the display, open Pixel Camera, record media, grant permissions,
persist schedules, and arm exact alarms.

## Evidence levels

| Level | What it proves | What it does not prove |
| --- | --- | --- |
| Host unit/static analysis | Pure contracts and source quality | Android integration or Pixel Camera behavior |
| Android-test APK assembly | Instrumentation code compiles/packages | Any test ran |
| Emulator instrumentation | Android framework boundary under that image | Pixel hardware, Pixel Camera, lockscreen/Doze reliability |
| Connected tests on another Pixel | That device/build and non-opt-in suites | Certified Pixel 7 and Pixel 8 Pro acceptance |
| Target-device production rehearsal | One profile/capture on one installed artifact/environment | Reboot, Doze, process-death, or later commits unless exercised |
| Target-device scenario matrix | Named states and postconditions on one artifact | Later commits or different Camera/system/profile variants |

## Required provenance

Every physical claim must record:

```text
device model and codename
ADB serial selection method (do not publish the serial)
Android release, SDK, build, fingerprint
Pixel Camera package, versionName, versionCode, relevant APK variant identity
display size/density and locale
Lenswake commit and version
local APK SHA-256 and installed-artifact identity result
profile ID/schema/full definition fingerprint and capture configuration
initial screen/keyguard/idle/Camera/process state
exact commands or UI actions
scheduled and observed timestamps with timezone
recording, stopped, and saved-media postconditions
cleanup performed
conclusion and remaining gates
```

Matching environment identity alone is not acceptance. A physical result from an older APK is
historical evidence until the current artifact reruns the required scenarios.

## Safe device selection

When multiple devices are attached, unqualified ADB or Gradle connected tasks are prohibited.

```bash
adb devices -l
adb -s "$PIXEL_SERIAL" shell getprop ro.product.model
ANDROID_SERIAL="$PIXEL_SERIAL" ./gradlew :app:connectedDebugAndroidTest
```

Confirm model, build, and Pixel Camera version immediately before mutation. Do not store a personal
ADB serial in repository documents.

## Ordinary connected suites

Compile instrumentation first:

```bash
./gradlew \
  :app:assembleDebugAndroidTest \
  :data:assembleDebugAndroidTest
```

Run ordinary suites against one selected device:

```bash
ANDROID_SERIAL="$PIXEL_SERIAL" ./gradlew \
  :data:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest \
  --rerun-tasks \
  --console=plain
```

This does not run the guarded production automation fixtures. Some ordinary tests still grant app
permissions or briefly change display state; inspect the test list and device afterward.

## Opt-in physical fixtures

`PhysicalDeviceWakeFixtureTest` requires explicit instrumentation arguments. Never enable these flags
as part of an unreviewed broad suite.

- `physicalDeviceWakeOnly=true`: requires screen off and keyguard locked; exercises the wake primitive.
- `physicalProfileRehearsal=true`: performs a real production rehearsal.
- `physicalWake=true`: persists a future schedule and arms START plus STOP alarms.
- `physicalWakeCleanup=true`: removes the persisted fixture and alarms.
- `physicalSelectorProbe=true` plus `physicalSelectorExpectedDescription`: observes one manually
  exposed `Stop video` or `Stop time lapse` node without dispatching an action.

The scheduled fixture also accepts bounded start-delay and recording-window arguments. Read the
current test source before invocation; test contracts may change.

The selector probe starts only after the operator has manually exposed the requested recording
state. Instrumentation force-stops the target app, so re-enable Lenswake Accessibility after the
test starts, then stop the recording and clean up its media explicitly.

Example wake-only invocation:

```bash
adb -s "$PIXEL_SERIAL" shell am instrument -w \
  -e physicalDeviceWakeOnly true \
  -e class \
  'dev.po4yka.lenswake.alarm.PhysicalDeviceWakeFixtureTest#wakeLockedDisplayOnlyWhenExplicitlyRequested' \
  dev.po4yka.lenswake.test/androidx.test.runner.AndroidJUnitRunner
```

## Explicit beta calibration exception

A beta Pixel may be used only when the user explicitly authorizes that exact connected device for
selector-template calibration. This exception permits bounded Camera launch, semantic UI
inspection, a short capture needed to expose Record/Stop postconditions, and a guarded diagnostic
Accessibility probe. It does not admit the beta fingerprint into the supported runtime.

Beta calibration evidence must:

- record the full beta fingerprint, Camera package/version/signer, display, locale, source commit,
  diagnostic APK hash, exact actions, observations, created media, and cleanup;
- contribute only selectors and state signals observed on that device; omit absent or unobserved
  controls and typed dialogs instead of copying them from another template;
- remain `EXPERIMENTAL` and require a fresh exact stable-environment profile plus exact-combination
  rehearsal before scheduling;
- never count as a stable-build rehearsal, release acceptance, certification, or reliability
  validation for this or another model; a registry may reuse observed semantic candidates on an
  `EXPERIMENTAL` model, but that model still has no physical evidence until tested itself;
- never transfer normalized gestures or geometry to another model.

Profile installation, preflight, action dispatch, certification, and release workflows must remain
fail-closed for the beta fingerprint even when its semantic selectors seed a template.

## Acceptance scenarios

For each current Certified release claim, install the same signed release APK on Pixel 7 and Pixel
8 Pro, record commit/APK/installed-artifact identity, rehearse every offered combination, then exercise at least:

1. screen on + unlocked;
2. screen on + locked;
3. screen off + locked;
4. screen off + locked + forced Doze;
5. Pixel Camera already open;
6. Pixel Camera in a different mode;
7. Lenswake process killed before START;
8. Lenswake process killed during owned recording;
9. STOP while the screen is off;
10. reboot before scheduled START and normal unlock restoration;
11. delayed or restarted Pixel Camera;
12. profile/environment drift;
13. Accessibility disabled/disconnected;
14. insufficient battery/storage/media permission;
15. saved video published with unambiguous owner/generation/size/duration evidence;
16. each typed recoverable and terminal dialog required by the current profile.

Do not manufacture a success by weakening selector thresholds, bypassing saved-media verification,
or treating a dispatched action as a postcondition.

## Artifact identity

Record the local APK hash:

```bash
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
```

After installation, pull or otherwise verify the installed base artifact against the local APK using
a reproducible method appropriate to the build/install mode. If splits or packaging prevent a direct
byte comparison, record the exact package paths and hashes and explain the comparison boundary.

## Cleanup

After a fixture or manual acceptance run:

- stop any Lenswake-owned Pixel Camera recording through the production STOP path;
- delete fixture schedules through `ScheduleWorkflow` or the fixture's explicit cleanup mode;
- verify START, STOP, retry, rearm, and rehearsal STOP alarm identities are absent;
- reset forced idle and battery simulation;
- cancel Lenswake test/wake notifications;
- restore only permissions/settings intentionally changed for the test;
- retain or delete generated Pixel Camera media deliberately and record the choice;
- verify screen, keyguard, foreground package, app process, and Accessibility state.

If cleanup cannot be confirmed, report the exact remaining device state before stopping.

## Evidence records

Add or supersede a dated file under `docs/research/`. Never edit a historical record so it appears to
cover a newer commit. Update [../STATUS.md](../STATUS.md) with the new artifact/scenario boundary.
