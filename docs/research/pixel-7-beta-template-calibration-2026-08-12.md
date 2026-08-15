# Pixel 7 beta semantic-template calibration

**Observed:** 2026-08-12 22:20-22:47 +04:00

**Source baseline:** `main@6d80050ec1009aee50335efa716943b5e8bc2745`

**Resulting contract:** selector schema v5, standard template `pixel-7-semantic` version 3

**Authorization and scope:** the user explicitly authorized the connected Pixel 7 beta to resolve
the missing independent Pixel 7 selector template. The session was limited to secure Camera launch,
live semantic inspection, short manual Video/Time Lapse captures needed to expose Stop controls, and
an ephemeral Lenswake Accessibility snapshot. It was not a profile installation, production
rehearsal, stable-build test, certification, or release acceptance.

## Exact environment and artifacts

The device was selected from `adb devices -l`; its personal serial is intentionally not published.
Every device command used `adb -s "$P7_SERIAL"`.

| Field | Observed value |
| --- | --- |
| Device | Google Pixel 7 / `panther` (`panther_beta` product) |
| Android | release `17`, SDK 37, build `CP41.260717.006` / `15938186` |
| Fingerprint | `google/panther_beta/panther:DEV/CP41.260717.006/15938186:user/release-keys` |
| Build type/tags | `user` / `release-keys` |
| Display | physical 1080 x 2400, 420 dpi, portrait, font scale 1.0 |
| Locale | `en-US` |
| Pixel Camera | `com.google.android.GoogleCamera` 10.4.117.936816638.14 (`69481630`) |
| Camera signer SHA-256 | `f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83` |
| Camera base APK SHA-256 | `60b9adbc20df3ab6dd21f0a81b353a2f87c0d290bfa6261d659acf0eef37773a` |
| Lenswake version | `0.1.0` (`versionCode=1`) |
| Diagnostic Lenswake APK SHA-256 | `235eead9241dfa5d949aa17e856bcbbe5dd1d525539214e10c82af4500f48084` |
| Installed Lenswake base APK SHA-256 | same; computed in place after installation |
| Diagnostic androidTest APK SHA-256 | `942287da6937ad7bc0a76b04b004443f02a627e180fd801bdac5afc7dee17ac2` |
| Template profile ID | `pixel-7-beta-cp41-260717-006-physical-template-v5` |
| Template definition fingerprint | `dac75095dca416bf08e2bc713f41a689d90f7bccb5188e9b90eee8c51788e83c` |

The device began locked and Dozing. Camera was opened through
`android.media.action.STILL_IMAGE_CAMERA_SECURE`; no credential was injected and keyguard was not
dismissed. Lenswake was installed only to bind its package-scoped Accessibility Service for bounded
snapshots. The production build-admission code rejected this beta environment throughout.

## Exact procedure

Environment/package identity and Camera UI were inspected with the following command families:

```bash
adb devices -l
adb -s "$P7_SERIAL" shell getprop ro.product.manufacturer
adb -s "$P7_SERIAL" shell getprop ro.product.model
adb -s "$P7_SERIAL" shell getprop ro.product.device
adb -s "$P7_SERIAL" shell getprop ro.build.version.sdk
adb -s "$P7_SERIAL" shell getprop ro.build.id
adb -s "$P7_SERIAL" shell getprop ro.build.version.incremental
adb -s "$P7_SERIAL" shell getprop ro.build.fingerprint
adb -s "$P7_SERIAL" shell wm size
adb -s "$P7_SERIAL" shell wm density
adb -s "$P7_SERIAL" shell settings get system font_scale
adb -s "$P7_SERIAL" shell dumpsys package com.google.android.GoogleCamera
adb -s "$P7_SERIAL" shell pm path com.google.android.GoogleCamera
adb -s "$P7_SERIAL" shell \
  'for apk_path in $(pm path com.google.android.GoogleCamera | cut -d: -f2); do sha256sum "$apk_path"; done'

adb -s "$P7_SERIAL" shell am start -a android.media.action.STILL_IMAGE_CAMERA_SECURE
android layout --device="$P7_SERIAL" --pretty
adb -s "$P7_SERIAL" shell uiautomator dump /sdcard/lenswake-selector.xml
adb -s "$P7_SERIAL" exec-out cat /sdcard/lenswake-selector.xml
```

Each manual tap used bounds from the immediately preceding fresh tree. To observe recording-only
nodes despite Camera's continuous animation, a temporary guarded androidTest called the production
`PixelCameraAccessibilityRuntime.snapshot()` boundary, waited for an exact description, required a
unique node, and logged only that node. The checked-in
`PhysicalPixelCameraSelectorProbeTest` preserves the same reproducible boundary and explicit gate:

```bash
adb -s "$P7_SERIAL" shell am instrument -w \
  -e physicalSelectorProbe true \
  -e physicalSelectorExpectedDescription 'Stop video' \
  -e class \
  'dev.po4yka.lenswake.accessibility.PhysicalPixelCameraSelectorProbeTest' \
  dev.po4yka.lenswake.test/androidx.test.runner.AndroidJUnitRunner
```

Instrumentation force-stops the target package, so the service was re-enabled only after the
instrumentation process began. The same procedure was used for `Stop time lapse`. The final guarded
fixture was added after the observation and was compiled, not rerun; the observed temporary probe
used the same runtime snapshot implementation and assertions.

## Live selector observations

All nodes below were visible, enabled, package-scoped to Pixel Camera, and observed in en-US portrait
on this exact environment.

### Modes, recording, and Video settings

| State/action | Live result |
| --- | --- |
| Photo active | `id/mode_chip_text`, text/description `Photo`, selected |
| Enter Video | clickable/checkable `video_supermode`; active chip text/description `Video`, selected |
| Enter Time Lapse | `id/mode_chip_text`, text `Time Lapse`; switch description or active description `Time Lapse` |
| Start Video | `id/shutter_button`, description `Start video`, clickable |
| Stop Video | `id/shutter_button`, description `Stop video`, clickable; normalized bounds `(0.37592593,0.6933333)-(0.6240741,0.805)` |
| Start Time Lapse | `id/shutter_button`, description `Start time lapse`, clickable |
| Stop Time Lapse | `id/shutter_button`, description `Stop time lapse`, clickable; same normalized bounds |
| Select 4K | settings `ImageButton`, description `4K Ultra HD`, clickable; selected after dispatch |
| Select 60 FPS | settings `ImageButton`, description `60 FPS`, clickable; selected after dispatch |

The clickable 4K/60 nodes have no text. Their visible labels are separate sibling nodes (`4K (Ultra
HD)` and `60`). Therefore standard template v3 intentionally uses description plus observed selected
state rather than requiring description and label text on one node. Video's minibar showed
`4K Ultra HD`, and the settings panel simultaneously reported the 4K and 60 FPS controls selected
before the short Video capture.

### Time Lapse speeds

Opening the node described as `Time Lapse control` exposed all five clickable picker nodes:

| Speed | Description | Text |
| --- | --- | --- |
| Auto | `Time Lapse auto speed` | `Auto` |
| 5x | `Time Lapse 5 times speed` | `5×` |
| 10x | `Time Lapse 10 times speed` | `10×` |
| 30x | `Time Lapse 30 times speed` | `30×` |
| 120x | `Time Lapse 120 times speed` | `120×` |

Auto was initially selected. Each of 5x, 10x, 30x, and 120x was then dispatched from fresh bounds
and its exact description node was observed with `selected=true`. This supports action and
selected-state selectors for every value, but does not qualify any stable-environment capture
combination or reliability claim.

### Lenses and omitted capabilities

| Contract | Live result |
| --- | --- |
| Rear main action/active | unselected `zoom_toggle_1` / text `1`; active `zoom_toggle_1×` / text `1×` |
| Rear ultrawide action/active | unselected `zoom_toggle_.7` / text `.7`; active `zoom_toggle_.7×` / text `.7×` |
| Front action | `id/camera_switch_button`, description `Switch to front camera`, clickable |
| Front active / back action | same ID, description `Switch to back camera`, clickable |

No telephoto control exists on Pixel 7. Time Lapse settings exposed Resolution only; no Night Sight
or additional-light mode was present. No typed dialog was induced. Standard template v3 therefore
omits telephoto, Night Sight Time Lapse, and all dialog profiles rather than inheriting them from the
Pixel 8 Pro template. It also contains no normalized fallback gesture or geometry-only selector.

## Capture/media observations and cleanup

Manual captures existed only to expose live Stop state. They did not create Lenswake sessions,
profiles, receipts, schedules, or alarms, and they did not run Lenswake's saved-media verifier.
MediaStore attributed every generated file to Pixel Camera. Iterative diagnostic attempts created
the following exact rows, all deleted individually by ID after Stop was observed:

| MediaStore ID | Name | Size | Duration |
| --- | --- | ---: | ---: |
| 331 | `PXL_20260812_183017271.mp4` | 1,888,300 bytes | 367 ms |
| 339 | `PXL_20260812_183914181.mp4` | 297,528 bytes | 100 ms |
| 342 | `PXL_20260812_184343987.mp4` | 62,166,778 bytes | 7,566 ms |
| 345 | `PXL_20260812_184542209.mp4` | 111,482,474 bytes | 13,636 ms |
| 346 | `PXL_20260812_184635379.mp4` | 92,202,121 bytes | 11,232 ms |

Cleanup verification found no remaining row for each exact ID, no Lenswake or test package, and
`enabled_accessibility_services=null`. Temporary UI XML files were removed, Camera was closed, and
the device returned to locked Dozing state. No alarm, schedule, idle override, battery simulation,
or runtime permission was created.

## Conclusion and evidence boundary

This session supports an independent `PHYSICAL_TEMPLATE` source for standard template version 3
and invalidates prior standard-template fingerprints/receipts. It does not support `CERTIFIED`, does
not admit the beta build, and does not prove unattended execution. Runtime code and regression tests
continue to reject the exact beta environment at catalog installation, preflight, and action
dispatch. A supported stable exact environment must derive a fresh Experimental profile and pass a
saved-media rehearsal for every capture combination before scheduling.
