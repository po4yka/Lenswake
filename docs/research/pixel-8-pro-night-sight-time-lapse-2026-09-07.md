# Night Sight Time Lapse control observation — 2026-09-07

## Purpose

The first production rehearsal on the connected Pixel 8 Pro failed with
`NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND`. This record captures the live Pixel Camera Accessibility
nodes for the Night Sight Time Lapse control on the exact admitted environment, so the selector
defect is established by observation rather than assumption.

It records what was observed. It does not certify a rehearsal and does not itself change source.

## Environment

Identical to [pixel-8-pro-august-2026-build-admission.md](pixel-8-pro-august-2026-build-admission.md):
Pixel 8 Pro (`husky`), Android 17 / SDK 37,
`google/husky/husky:17/CP2A.260805.005/15828068:user/release-keys`, Pixel Camera
`10.4.117.936816638.14` (versionCode `69481630`), 1008 x 2244 @ 360 dpi, locale
`en-US-u-fw-mon-mu-celsius`.

## The failure

Rehearsal session `633e0a22-af45-4bd1-8e1c-26638e1d5021`, 2026-09-07T05:39:44.31Z. The automation
reached the camera correctly and then failed closed at one action:

```text
05:39:44.585Z  START_TRIGGERED            STARTED
05:39:44.596Z  VALIDATING_SESSION         SUCCEEDED
05:39:46.359Z  WAKING_DEVICE              STARTED    WAKE_DEVICE
05:39:46.362Z  LAUNCHING_SECURE_CAMERA    STARTED    LAUNCH_CAMERA        attempt 1
05:39:46.387Z  LAUNCHING_SECURE_CAMERA    DISPATCHED LAUNCH_CAMERA        13 ms, STANDARD_ANDROID_API
05:39:46.398Z  WAITING_FOR_PIXEL_CAMERA   STARTED    INSPECT_CAMERA       attempt 1
05:39:46.547Z  WAITING_FOR_PIXEL_CAMERA   SUCCEEDED  INSPECT_CAMERA
05:39:46.55Z   INSPECTING_CAMERA_STATE    STARTED    INSPECT_CAMERA
05:39:46.761Z  INSPECTING_CAMERA_STATE    SUCCEEDED  INSPECT_CAMERA
05:39:46.767Z  SELECTING_NIGHT_SIGHT_TIME_LAPSE STARTED  attempt 1
05:39:46.863Z  RETRYING                                  attempt 2
05:39:47.066Z  SELECTING_NIGHT_SIGHT_TIME_LAPSE STARTED  attempt 2
05:39:47.183Z  RETRYING                                  attempt 3
05:39:47.587Z  SELECTING_NIGHT_SIGHT_TIME_LAPSE STARTED  attempt 3
05:39:47.707Z  FAILED  Failure: NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND
```

Session summary: `Retries: 2`, `Gesture fallbacks: 0`, `Privileged: 0`, `Selector: no score`. Three
bounded attempts, no blind click, a typed failure, and the profile stayed `Needs test` with the
capture configuration `Untested`. The engine behaved as its contract requires; the profile's selector
is what does not match this Pixel Camera build.

## Observed nodes

Read with `uiautomator dump` while Lenswake was not running automation. Pixel Camera in Time Lapse
mode, Night Sight control closed:

```text
text=""  content-desc="Night Sight"
resource-id=com.google.android.GoogleCamera:id/minibar_item_ext2
class=android.widget.ImageView  clickable=true  checkable=false  checked=false  selected=false
bounds=[144,49][261,157]
```

The entry point is a **minibar item at the top of the screen**. Its label lives in the content
description; **its text is empty**. Clicking it opens a control row at the bottom:

```text
text=""             content-desc="Auto Night Sight in Time Lapse on"
class=android.view.View        clickable=true   selected=false  bounds=[0,1758][747,1884]

text="Night Sight"  content-desc="Auto Night Sight in Time Lapse on"
class=android.widget.TextView  clickable=false  selected=false  bounds=[36,1816][211,1869]

text=""             content-desc="Auto Night Sight in Time Lapse off"
class=android.widget.ImageButton  clickable=true  selected=false  bounds=[756,1767][864,1875]

text=""             content-desc="Auto Night Sight in Time Lapse on"
class=android.widget.ImageButton  clickable=true  selected=true   bounds=[864,1767][972,1875]
```

So the control is shaped exactly like the Time Lapse speed control: an entry point that opens a
row, then discrete option buttons, with the active option carrying `selected=true`.

Also confirmed live on this build, matching the current profile definition:

| Profile expectation | Observed |
| --- | --- |
| `SELECT_TIME_LAPSE` — `id/mode_chip_text`, desc `Switch to Time Lapse Mode`, text `Time Lapse` | present, exact |
| Time Lapse active — `id/mode_chip_text`, text and desc `Time Lapse` | present, exact |
| `OPEN_TIME_LAPSE_SPEED_CONTROL` — desc `Time Lapse control` | present, exact |
| `START_NIGHT_SIGHT_TIME_LAPSE_RECORDING` — `ComposeShutter`, desc `Start time lapse` | present, exact |
| `SELECT_REAR_MAIN_LENS` — `zoom_toggle_1×`, text `1×` | present, exact |
| `SELECT_FRONT_LENS` — desc `Switch to front camera` | present, exact |
| Video shutter — `ComposeShutter`, desc `Start video` | present, exact |
| Photo shutter — `ComposeShutter`, desc `Take photo` | present, exact |

## Why the current selectors cannot match

```kotlin
AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE to actionSelector(
    text = "Night Sight",
    minimumScore = 30,
)
```

`actionSelector` defaults `requiresClickable = true`. With the control closed no node carries the
text `Night Sight` at all, so the action scores nothing. With the control open the only node with
that text is the `TextView` label, which is **not clickable**, so it is rejected as well. The
selector can therefore never match on this build, in either state.

The matching state signal has the same problem:

```kotlin
PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE to stateSelector(
    text = "Night Sight auto enabled. Learn more",
    minimumScore = 30,
)
```

No node with that text was observed in any Time Lapse state. The observed active indicator is the
option button with content description `Auto Night Sight in Time Lapse on` and `selected=true`.

This is the same defect class the selector-template provenance already recorded once for 60 FPS,
where the selector matched on `text` a value that the build exposes only as a content description.

## Change this observation supports

The flow needs an entry-point action, exactly as the speed flow already has
`OPEN_TIME_LAPSE_SPEED_CONTROL`:

```text
open control      resource ID minibar_item_ext2, description "Night Sight", clickable
select on         description "Auto Night Sight in Time Lapse on", clickable
active signal     description "Auto Night Sight in Time Lapse on", expectedSelected = true
```

That requires a new `AutomationAction`, a new step in the Night Sight branch of the automation state
machine, corrected catalog entries, and a selector-schema version bump — which invalidates installed
profiles and every rehearsal receipt bound to them. It is deliberately **not** applied by this
record, because it is a schema-affecting design change and because a corrected flow must then be
re-proved by a fresh rehearsal on this device, not merely compiled.

## Applied correction (same day, later)

The correction documented above was applied on `main`:

- selector schema bumped to v6; `PixelCameraProfileTemplateFactory`-derived profiles and installed
  v5 profiles are invalidated by it (Room v10→v11 migration marks them `INCOMPATIBLE` and disables
  their schedules);
- new `OPEN_NIGHT_SIGHT_TIME_LAPSE_CONTROL` action over the observed minibar entry point
  (`minibar_item_ext2` + "Night Sight");
- `SELECT_NIGHT_SIGHT_TIME_LAPSE` now targets the ON option button (content description
  "Auto Night Sight in Time Lapse on", role `android.widget.ImageButton`), and the
  `NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE` signal observes that button with `expectedSelected = true`;
- a new observable `NIGHT_SIGHT_TIME_LAPSE_CONTROL_OPEN` signal (either option button present) backs
  a dedicated converging control-row state, mirroring the Time Lapse speed-picker flow, including an
  idempotent opener with bounded retries;
- the engine reaches Time Lapse mode through Video exactly like plain Time Lapse captures, then
  opens the control row, selects ON, and only then converges lens and recording.

Verified by the host gate and both ordinary connected suites on the target device. The flow itself
remains unproved until a fresh production rehearsal on this device; whether Pixel Camera keeps the
control row open during recording is still unobserved, and the engine accepts the postcondition in
either shape.

## Evidence boundary

Confirmed here:

- the exact rehearsal timeline and typed failure code on the admitted environment;
- the live Accessibility nodes for the Night Sight Time Lapse entry point and its option row,
  including class, clickability and selected state;
- that eight other selector expectations in the current profile match this build exactly.

Not confirmed here:

- that the proposed flow drives Night Sight Time Lapse to a verified recording. No corrected flow
  was executed;
- any other capture configuration in the matrix. The rehearsal failed before reaching them, so
  Video 4K/60 and the plain Time Lapse speeds remain unobserved in a production run;
- anything about Pixel 7 or the standard template. Only the Pixel 8 Pro telephoto environment was
  observed.

Device state was restored: the Night Sight control was closed without choosing an option and Pixel
Camera was returned to Photo mode.
