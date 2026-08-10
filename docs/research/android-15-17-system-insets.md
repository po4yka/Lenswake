# Android 15–17 system insets and edge-to-edge — 2026-08-10

## Purpose

Establish the platform requirements that an audit of Lenswake's top and bottom layout insets must
use on Android 15, 16, and 17 (API levels 35, 36, and 37). This note covers system bars, display
cutouts, gesture and three-button navigation, the IME, and Jetpack Compose Material 3. It also
records the Lenswake audit, the resulting remediation, and the validation evidence that was actually
observed. Physical-device and runtime claims remain explicitly separated from static evidence.

All sources are first-party Android Developers or AOSP sources. They were accessed on
2026-08-10.

## Executive conclusion

The correct model is edge-to-edge drawing plus selective protection of important content, not one
fixed top and bottom padding for the whole application. A conforming implementation must:

1. enable the same edge-to-edge behavior on older supported Android versions with
   `enableEdgeToEdge()`;
2. keep backgrounds able to draw behind transparent system bars;
3. protect interactive and semantically important content using live `WindowInsets`;
4. distinguish visual protection (`safeDrawing` / system bars and cutout) from gesture protection
   (`safeGestures` / system gestures);
5. let Material 3 components own their documented insets and apply `Scaffold`'s `innerPadding`
   exactly once;
6. receive and consume IME insets instead of treating the navigation-bar inset as a keyboard
   inset; and
7. validate both gesture and three-button navigation because their bottom-bar appearance differs
   even though neither restores the old content offset.

Android's current Compose guidance says edge-to-edge is enforced on Android 15 and later when the
app targets SDK 35, and recommends `enableEdgeToEdge()` for backward-compatible behavior.
[`enableEdgeToEdge()` makes the bars transparent except for a translucent three-button navigation
scrim](https://developer.android.com/develop/ui/compose/system/setup-e2e).

## Version-specific platform behavior

### Android 15 / API 35

For an app targeting API 35 and running on Android 15, edge-to-edge is enabled by default. The
platform removes the old top and bottom content offsets, so content draws under the system UI until
the app applies insets. This is a target-SDK-gated breaking change, not an OEM styling detail.
[Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15).

The enforced system-bar behavior is:

- gesture navigation: the navigation bar is transparent, the bottom offset is disabled, and
  navigation-bar color APIs have no effect;
- three-button navigation: the bottom offset is also disabled, while the navigation area gets an
  80% opaque background by default; navigation-bar color and contrast enforcement still affect
  this mode;
- status bar: transparent by default, with the top offset disabled; status-bar color APIs are
  deprecated and have no effect;
- non-floating display-cutout windows: `DEFAULT`, `SHORT_EDGES`, and `NEVER` are interpreted as
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`.

These details and the applicable deprecated/disabled APIs are enumerated in the
[Android 15 window-inset change](https://developer.android.com/about/versions/15/behavior-changes-15#edge-to-edge).
The AOSP implementation is tied to the API 35 (`VANILLA_ICE_CREAM`) target-SDK compatibility
change and sets non-fitting windows to the always-cutout mode; see the
[AOSP edge-to-edge enforcement change](https://android.googlesource.com/platform/frameworks/base.git/+/1f7d924828e5ceb49454520b49227b878e589f23%5E%21/).

Android 15 also changes `Configuration.screenWidthDp` and `screenHeightDp` so that they no longer
exclude system bars for target-35 apps. Layout code must not derive top or bottom bar padding from
`Configuration`, display height, or a fixed Pixel dimension; use `WindowInsets` (and
`WindowMetrics` when the actual app-window size is needed).
[Android 15 stable-configuration change](https://developer.android.com/about/versions/15/behavior-changes-15#stable-configuration).

Android 15 provided a temporary theme escape hatch,
`windowOptOutEdgeToEdgeEnforcement=true`. It is not an acceptable correctness strategy because
Android 16 disables it on the target-36/runtime-36 combination.

### Android 16 / API 36

Android 16 makes edge-to-edge non-optional for apps targeting API 36 when they run on Android 16:
`windowOptOutEdgeToEdgeEnforcement` is deprecated and disabled. The official compatibility detail
is asymmetric:

- target 36 on an Android 15 runtime: the opt-out continues to work;
- target 36 on an Android 16 runtime: the opt-out is disabled.

The supported migration is to remove the opt-out and correctly handle insets on both runtimes.
[Android 16 target-SDK behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16#edge-to-edge).

No Android 16-specific replacement top or bottom inset should be introduced. The same runtime
`WindowInsets` contract applies; the audit must look for app code that only appeared correct because
the Android 15 opt-out or old decor fitting was still active.

### Android 17 / API 37

The generic Android guidance explicitly covers Android 15 **and higher** for apps targeting SDK 35
or later. As of the access date, neither the Android 17 all-app behavior page, the target-37
behavior page, nor the Android 17 release notes documents a new phone system-bar, cutout, or
edge-to-edge exception. Therefore the defensible working contract for API 37 is the same enforced,
non-optional edge-to-edge contract introduced in API 35 and made non-optional for target 36 on the
API 36 runtime. This is an inference from the generic guidance and the absence of an Android
17-specific override, not a claim that every Android 17 build is visually identical.

Official Android 17 references checked:

- [behavior changes for apps targeting Android 17](https://developer.android.com/about/versions/17/behavior-changes-17);
- [behavior changes for all apps on Android 17](https://developer.android.com/about/versions/17/behavior-changes-all);
- [Android 17 release notes](https://developer.android.com/about/versions/17/release-notes);
- [Android 17 SDK setup (`compileSdk` and `targetSdk` 37)](https://developer.android.com/about/versions/17/setup-sdk).

One Android 17 change is relevant to keyboard-state testing but not to inset geometry: after an
unhandled configuration change such as rotation, the platform no longer restores the previous IME
visibility. An activity that requires the keyboard to remain visible must explicitly request it or
use the documented visibility mode. The layout must still react to the resulting `WindowInsets.ime`
state.
[Android 17 IME visibility behavior](https://developer.android.com/about/versions/17/behavior-changes-all#restore-default-ime-visibility).

Android 17 reached API surface stability in Beta 3, but runtime and documentation updates can still
affect observed system UI. The absence of a documented edge-to-edge change does not replace testing
the exact Pixel build used for acceptance.
[Android 17 release-note history](https://developer.android.com/about/versions/17/release-notes#beta-3).

## Correct inset ownership in Compose Material 3

### Activity/window boundary

Call `enableEdgeToEdge()` in `Activity.onCreate()` even when the current target/runtime combination
already enforces edge-to-edge. This supplies consistent behavior on earlier Android versions and
configures transparent bars, adaptive icon appearance, and the default three-button navigation
scrim. Configure the activity with `android:windowSoftInputMode="adjustResize"` so Compose receives
IME inset changes.
[Compose edge-to-edge setup](https://developer.android.com/develop/ui/compose/system/setup-e2e).

System-bar icon appearance must remain legible over the actual background. Use
`WindowInsetsControllerCompat` light/dark appearance controls; do not expect a status-bar color to
cover bad contrast on API 35+. A custom translucent protection layer can be drawn behind the bar
where the design requires it.
[Compose system-bar protection](https://developer.android.com/develop/ui/compose/system/system-bars).

### Material 3 components

Material 3 components own the insets documented for their placement:

- `TopAppBar` variants apply the top and horizontal system-bar insets;
- `BottomAppBar` and `NavigationBar` apply bottom and horizontal system-bar insets;
- `ModalBottomSheet` applies the bottom inset;
- `Scaffold` computes inset-aware `PaddingValues`, but does **not** apply them to its content.

The content lambda must apply `innerPadding`. For a `LazyColumn`, the official pattern uses
`contentPadding = innerPadding` and `Modifier.consumeWindowInsets(innerPadding)`. Adding another
`safeDrawingPadding()`, `systemBarsPadding()`, or equivalent around the same content can double the
top or bottom spacing. Conversely, ignoring `innerPadding` allows the content to be obscured.
[Material 3 inset ownership and `Scaffold` example](https://developer.android.com/develop/ui/compose/system/material-insets).

For custom composables outside that ownership, use Compose inset modifiers or rulers. Prefer the
built-in modifiers because inset values update after composition but before layout; reading raw
inset values during composition can be one frame late. Inset padding modifiers also consume the
portion they apply, which prevents nested inset handlers from applying the same space twice.
[Compose inset setup and consumption](https://developer.android.com/develop/ui/compose/system/insets-ui).

### Choosing the inset type

- `WindowInsets.systemBars` is the union of status, navigation, and caption bars. Use it for
  tappable content that must remain visible in either gesture or button navigation.
- `WindowInsets.displayCutout` protects against a notch or pinhole, including side cutouts after
  rotation.
- `WindowInsets.safeDrawing` protects content from system UI and cutouts; it is a useful safe
  default for a custom screen, but applying it to the entire app prevents intentional
  edge-to-edge background drawing.
- `WindowInsets.systemGestures` / `safeGestures` protect swipeable app controls from system gesture
  interception. Gesture-navigation insets include the bottom home gesture and left/right back
  gestures.
- `WindowInsets.safeContent` combines visual and gesture protection.
- `WindowInsets.tappableElement` describes the area where system navigation handles taps and is
  useful when designing custom protection for the three-button navigation region.
- `WindowInsets.ime` describes the software keyboard's bottom extent; it is separate from the
  navigation-bar inset.

Definitions and safe-type guidance are in
[About window insets](https://developer.android.com/develop/ui/compose/system/insets). Gesture and
cutout examples, including the distinction between button and gesture navigation, are in the
[edge-to-edge Views guide](https://developer.android.com/develop/ui/views/layout/edge-to-edge).

### IME behavior

With `adjustResize`, use `imePadding()` or another `WindowInsets.ime` consumer on the content that
must remain visible while typing. Compose inset modifiers animate with IME changes and account for
nested consumption: when IME padding consumes the lower region, a nested system-bar spacer shrinks
instead of adding navigation-bar and keyboard heights twice.
[Compose IME/inset consumption example](https://developer.android.com/develop/ui/compose/system/insets-ui#inset-consumption).

An audit should reject fixed keyboard heights, `Configuration`-derived bottom padding, and layouts
that only move a text field while leaving the focused field, submit action, snackbar, or modal
control covered by the IME.

## Audit and validation criteria

### Static code criteria

- One clear owner applies each top and bottom inset; nested `Scaffold`s and screen containers do
  not duplicate it.
- Every `Scaffold` content lambda uses its `innerPadding`, including scrollable and empty/error
  states.
- Custom top content accounts for both status bars and display cutouts; custom bottom content
  accounts for navigation bars and, where interactive gestures matter, system gestures.
- Backgrounds may extend behind bars, but text, buttons, list endpoints, FABs, snackbars, dialogs,
  sheets, and accessibility targets remain reachable.
- IME-sensitive screens use `adjustResize` plus `WindowInsets.ime`; navigation-bar and IME insets
  are not manually summed twice.
- No top/bottom correctness depends on `screenHeightDp`, `Display.getSize`, hard-coded Pixel
  coordinates, a guessed status-bar height, or deprecated bar-color APIs.
- Light and dark status/navigation icons remain legible over every background they can overlay.
- Dialog windows that intentionally go full-screen are handled as their own window; the official
  guidance calls `WindowCompat.enableEdgeToEdge()` from the dialog's `onStart()`.

### Runtime evidence matrix

At minimum, render and inspect every application screen and important transient surface under:

| Dimension | Required cases |
|---|---|
| Runtime/target | API 35/target 35, API 36/target 36, API 37/target 37 |
| Navigation | gesture navigation and three-button navigation |
| Bars | light and dark app surfaces; status/navigation bars visible |
| IME | hidden, shown, animating, focus moved between first and last editable controls |
| Orientation/cutout | supported orientation(s), including side-cutout behavior if landscape is allowed |
| Window/content state | first launch/setup, normal list, long scrolled list, empty, error, dialog/sheet, snackbar/FAB |
| Android 17 | repeat IME flow after an activity-recreating configuration change |

For Lenswake's narrow Pixel target, final acceptance should be repeated on the exact Pixel 8 Pro
Android 17 build in addition to emulator coverage. A screenshot that merely shows a background
under the bars is insufficient: the evidence must show that the first and last actionable content
remain unobscured and reachable in both navigation modes and with the IME open.

## Android 17 uncertainties to retain

1. No Android 17-specific phone edge-to-edge change is listed in the checked behavior or release
   pages. This supports inheritance of the API 35/36 contract, but it is not positive proof that
   OEM rendering, Pixel System UI, or a QPR has no regression.
2. Android 17 changes IME visibility restoration after configuration change. It does not document
   a new IME inset size or consumption rule, so the risk is state restoration and transition
   handling rather than a new padding constant.
3. Desktop/freeform windows can add a top caption bar. `WindowInsets.systemBars` includes it, but
   this is outside the primary locked Pixel phone scenario and should not be confused with the
   phone status-bar inset.
4. Platform documentation is living guidance. Re-check the Android 17 behavior and release pages
   when the accepted Pixel build or Compose/Material 3 version changes.

## Lenswake application audit

### Result matrix

| Case | Result | Evidence |
|---|---|---|
| Android 15 / API 35 | **Fixed and exercised** | Commit `b2eba88` lowers `minSdk` to 35 in both Android modules and supplies an API-35 accessibility checked-state fallback. A fresh API-35 emulator installed both APKs and passed all seven inset/manifest tests. |
| Android 16 / API 36 | **Fixed and exercised** | The same compatibility change keeps the API-36 tri-state path isolated behind its runtime API check. A fresh API-36 emulator passed the same seven tests. |
| Android 17 portrait | **Fixed and exercised** | `enableEdgeToEdge()` remains enabled; all four screens apply and consume the root `Scaffold` padding at the viewport boundary. Seven dedicated tests passed on a physical Pixel 7 running API 37. |
| Android 17 landscape / side cutout | **Fixed and exercised synthetically** | Commits `e795006` and `ad3f5d5` preserve asymmetric start/end scaffold insets outside the scrollable content. LTR and RTL bounds tests cover side-inset propagation. |
| Android 17 with IME visible | **Contract fixed; real-keyboard inspection pending** | Commit `ec60ea3` adds `adjustResize` and applies `imePadding()` after consuming scaffold padding on the schedule editor. Manifest ownership is tested; real IME animation and reachability still require device inspection. |
| Gesture vs three-button navigation | **Synthetic geometry covered; physical matrix pending** | Material 3 continues to own the `NavigationBar` system-bar inset. Tests verify the viewport and last content remain above a non-zero bottom inset, but navigation-mode screenshots were not captured. |

Lenswake now keeps `compileSdk` and `targetSdk` at 37 while supporting installation from API 35.
The compatibility change is intentionally narrow: the only production API-36-only call found in
the affected path is isolated behind a runtime check, and its API-35 boolean fallback preserves the
same semantic selector input. The same built APK and instrumentation APK were installed on clean
API-35 and API-36 emulator data partitions before the version-specific runs.

### Remediation

1. **Android 15/16 installation (`b2eba88`)** — lowered both modules' `minSdk` to 35 and split the
   accessibility checked-state reader into API-35 and API-36 implementations. The generated debug
   APK reports minimum SDK 35.
2. **Horizontal safe insets (`e795006`)** — introduced shared screen padding and preserved the
   scaffold's asymmetric side insets on every screen.
3. **IME ownership (`ec60ea3`)** — declared `android:windowSoftInputMode="adjustResize"` on
   `MainActivity`; the editable schedules list consumes scaffold padding and then applies
   `imePadding()`, preventing the navigation/system-bar inset from being counted twice.
4. **Regression tests (`d80002a`)** — added pure LTR/RTL arithmetic tests, an Android merged-manifest
   test for `adjustResize`, and activity-hosted Compose bounds tests that inject asymmetric
   status/navigation/cutout insets through the real `Scaffold` path.
5. **Scroll viewport ownership (`ad3f5d5`)** — moved scaffold padding from the `LazyColumn`'s
   scrollable `contentPadding` to `Modifier.scaffoldContentViewport()`. This keeps scrolled content
   outside the status/navigation-bar regions while leaving deliberate screen margins scrollable.
6. **Bottom-bound coverage (`0d89530`)** — added explicit assertions for the viewport's lower edge
   and the last reachable item above the injected navigation inset.

The shared padding helper intentionally does not apply a second `safeDrawingPadding()` around the
root. Material 3 remains the single owner of system/cutout insets, while each screen adds only its
content spacing.

### Observed validation

- `:app:lintDebug :app:assembleDebug` passed after lowering the minimum SDK; lint reported no
  errors, and the resulting APK declares minimum SDK 35.
- `:app:testDebugUnitTest` passed with the API-35 fallback and inset arithmetic tests.
- Seven Android tests passed on a physical Pixel 7 running Android 17 / API 37: the merged manifest
  reports `adjustResize`; the activity-hosted layout preserves asymmetric top, bottom, start, and
  end insets in LTR/RTL; and scrolled/last content stays inside the protected viewport.
- The same seven tests returned `OK (7 tests)` through `AndroidJUnitRunner` on fresh API-35 and
  API-36 ARM64 Google Play emulators after successful APK installation. The temporary AVDs were
  removed after the runs; the repaired bootable system images remain installed in the local SDK.
- The target Pixel 8 Pro (`38080DLJG000GX`) disconnected before the device gate. A serial-pinned
  Gradle test invocation failed because the device was no longer present, so this run provides no
  Pixel 8 Pro layout or IME evidence.

### Required runtime closure

1. On API 37, render every screen in portrait and landscape under gesture and three-button
   navigation, light and dark themes, and IME hidden/shown/animating.
2. Repeat the acceptance matrix on the exact Pixel 8 Pro build and capture screenshots or bounds
   evidence showing the first and last actionable content remains unobscured.
3. Exercise the first and last schedule fields plus save/cancel controls with a real IME, including
   an Android 17 activity-recreating configuration change.

## Primary sources

All accessed 2026-08-10:

- [Android 15 behavior changes: window insets](https://developer.android.com/about/versions/15/behavior-changes-15#window-inset-changes)
- [Android 16 behavior changes: edge-to-edge opt-out removal](https://developer.android.com/about/versions/16/behavior-changes-16#edge-to-edge)
- [Android 17 target-SDK behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Android 17 all-app behavior changes](https://developer.android.com/about/versions/17/behavior-changes-all)
- [Android 17 release notes](https://developer.android.com/about/versions/17/release-notes)
- [Set up edge-to-edge in Compose](https://developer.android.com/develop/ui/compose/system/setup-e2e)
- [About window insets in Compose](https://developer.android.com/develop/ui/compose/system/insets)
- [Set up and consume window insets in Compose](https://developer.android.com/develop/ui/compose/system/insets-ui)
- [Use Material 3 insets](https://developer.android.com/develop/ui/compose/system/material-insets)
- [About system-bar protection](https://developer.android.com/develop/ui/compose/system/system-bars)
- [Display content edge-to-edge in Views](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
- [AOSP edge-to-edge enforcement implementation change](https://android.googlesource.com/platform/frameworks/base.git/+/1f7d924828e5ceb49454520b49227b878e589f23%5E%21/)
