# Pixel 8 Pro capture corrections, 2026-09-09

## Observation identity

The user authorized fixing all failures found in the release smoke check, commit/push to main,
release rebuild, reinstallation, and target-device verification. Observation used the same
serial-pinned Google Pixel 8 Pro / husky, Android 17 / API 37,
`google/husky/husky:17/CP2A.260805.005/15828068:user/release-keys`, 1008 x 2244 at 360 dpi,
locale `en-US-u-fw-mon-mu-celsius`. Active Pixel Camera was 10.4.117.936816638.14 / 69481630;
the package manager also reports its older system-image version, which was not the active artifact.

Lenswake's installed release was built from `2714728b6ee85cfaf175c1eb88d32c6277eff36d`, APK
`877fd5eeee93a85a1ba9bb0fcd22506d242735c714b9677587b14e700dc8384d`, as verified in
[the preceding smoke check](pixel-8-pro-release-smoke-2026-09-09.md). Source inspection began at
`890c796c1ca3d3ca4e6d9d87521020dac8676284`. New observations were collected approximately
10:32–11:10 UTC, with the device manually unlocked, display on, no recording active, and Pixel
Camera foreground. No credential was entered and no idle/battery/permission override was applied.

## Reproduction and findings

The prior Lenswake export reproduced selection confidence 70/90 during Video configuration,
lens verification failure after switching to the front camera, and failure to find Night Sight.
Bounded Pixel Camera-only UI dumps and visible-control taps then established:

- 4K and 60 FPS are clickable ImageButtons with content descriptions and selected state. Their
  text labels are separate siblings; expecting both text and description on the button is wrong.
  The closed UI does not expose FPS. Video settings must be opened and verified before Record.
- `zoom_toggle_1×` appears on front and rear cameras. Camera-switch descriptions distinguish
  facing. Rear zoom labels change from `.5`, `1`, `5` to `.5×`, `1×`, `5×` when selected.
  A front-camera 1x label cannot establish the rear main lens.
- Night Sight's minibar position varies and the entry is absent for some lenses. The complete
  `options_entry_button` / `Time Lapse settings` panel is the stable observed entry point.
- An open panel contains both Night Sight option buttons. A presence selector matching both with
  equal score is ambiguous; one unique option anchors presence, while selected state proves ON.
- Pixel Camera explicitly reports **Options unavailable** for More light on the observed front
  and rear ultrawide Time Lapse settings; both Night Sight option buttons are disabled. This is a
  native unavailable configuration. Ordinary Time Lapse remains usable; Night Sight must fail with
  `UNSUPPORTED_CAPTURE_CONFIGURATION`, close its panel, and never dispatch Record.
- All five speed options are visible simultaneously when the picker is open. One marker anchors
  presence; the selected option identifies speed. The closed lower-right speed label is separate
  observable evidence. Auto and 30x closed/open states were inspected along with the prior 120x rule.
- Alarm recovery executed camera/profile/rehearsal admission after restoring alarms. Consequently
  an empty installation could retain a false alarm-restoration warning solely because no camera
  rehearsal had passed. Transport restoration and scheduled START admission are separate contracts.

The official [Pixel Camera video instructions](https://support.google.com/pixelcamera/answer/7064897?hl=en)
independently describe opening Video Settings for 4K/60 and Time Lapse Settings for Night Sight.
They do not establish per-lens availability; that finding above comes from the named target.

## Implementation and test boundary

Capture preparation now follows lens/speed convergence and precedes the durable Record checkpoint.
It observes settings, performs only profile-defined semantic actions, verifies selected states,
binds Back to a fresh settings panel, and checks the closed idle capture UI. Failure/cancellation
cannot dispatch Record. Settings are not injected into cached UI snapshots. A persisted Record
checkpoint is created only after preparation, allowing uncertain-dispatch inspection without
reopening settings or resending Record.

Telephoto template version 3 and standard version 4 invalidate older definition fingerprints and
rehearsal receipts. The added standard settings-panel entry contract uses the shared version's
observed target resource shape; it is not new Pixel 7 physical proof. The original beta observation
remains excluded from runtime admission and certification.

Reduced, package-scoped observation fixtures are under
`app/src/test/resources/pixel-8-pro-2026-09-09/`. Tests replay observed shapes and model settings
postconditions; they are regression evidence, not live capture or saved-media proof. New tests cover
front/rear discrimination, unique panel presence, button targeting, closed/open speed state,
no selected-state confirmation after an accepted click, changed lens after dismissal, ambiguous
panels, native unavailable Night Sight, and preparation failure before the Record checkpoint.

Release installation and actual capture outcomes are recorded below only after observation.
