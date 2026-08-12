# Lenswake status and evidence boundary

**Snapshot:** `main@dc155b8`

**Reviewed:** 2026-08-12

This file distinguishes implemented behavior, local validation, Android integration evidence, and
physical Pixel proof. It is a snapshot, not the source of implementation truth.

## Current support claim

The application can be installed on Android 15+ (`minSdk 35`), but the only bundled automation
profile targets exactly:

```text
Device:              Google Pixel 8 Pro (husky)
Android:             17 / SDK 37
Build:               CP2A.260705.006 / 15641320
Display:             1008 × 2244 @ 360 dpi
Locale:              en-US-u-fw-mon-mu-celsius
Pixel Camera:        10.4.117.936816638.14
Camera versionCode:  69481630
Selector schema:     v4
Capture exposed:     Time Lapse 120×, rear main lens
```

On 2026-08-12 a connected Pixel 8 Pro matched the device/build/display/Camera identity above. That
read-only identity check is not a Lenswake installation, rehearsal, saved-media run, or current-HEAD
acceptance result.

## Evidence matrix

| Capability | Implemented at snapshot | Local/integration evidence | Physical evidence boundary |
| --- | --- | --- | --- |
| Four-module app and Compose UI | Yes | Build/unit/UI tests exist | Layout evidence is mixed emulator + Pixel 7; full Pixel 8 Pro matrix open |
| Exact independent START/STOP alarms | Yes | Unit/instrumentation coverage | Locked/Doze passed for historical named APK |
| Reboot/time recovery | Yes | Unit/instrumentation coverage | Reboot-before-START passed for historical named APK |
| Locked display `DEVICE_WAKE` | Yes | Instrumentation fixtures | Passed for historical named APK |
| Secure Pixel Camera launch | Yes | Dynamic resolver tests | Passed for historical named APK |
| Time Lapse 120× rear-main START/STOP | Yes | Engine/adapter tests | Passed for historical profile schema v3 artifact |
| Process-death rehearsal STOP backstop | Yes | Coordinator/alarm tests | Passed for historical named APK |
| Saved-media verification | Yes | Android integration tests cover permission/version/ambiguity/no-candidate behavior | Real current-HEAD Pixel Camera media publication open |
| Capture-specific rehearsal receipts | Yes | Unit/Room tests | Current schema-v4 physical rehearsal open |
| Typed dialog recovery | Yes | Unit tests; Pixel 7 static package inspection | Induced Pixel 8 Pro dialog scenarios open |
| Diagnostics timeline/text sharing | Yes | Unit/UI/intent tests | No special physical acceptance required; bounded to ten sessions |
| Shizuku privileged path | No | Explicit unavailable provider only | Not applicable |

## CI/CD evidence boundary

The repository now defines separate GitHub Actions contracts for:

- the full host gate and androidTest APK compilation;
- ordinary API-35 emulator instrumentation on pull requests;
- ordinary API-35/36 AOSP ATD and API-37 Google APIs preview instrumentation on `main`, nightly,
  and release tags;
- dependency review, CodeQL, and fail-closed Zizmor workflow auditing;
- manually approved signing, package/version/certificate/permission verification, checksums,
  provenance, and GitHub Release publication.

Actions are full-SHA pinned and repository policy requires SHA pins. The protected `release`
environment is restricted to `v*` tags and contains the signing secrets. The current certificate
fingerprint is committed, while the private keystore remains outside the repository.

Local workflow validation and signed-APK verification do not constitute hosted CI evidence. No
production tag or GitHub Release has been created by this implementation, and no physical-device
claim transfers to a future signed APK until that exact artifact is tested.

On 2026-08-12 the implementation working tree passed:

- `actionlint` for all three workflows and `shellcheck` for all CI scripts;
- release identity/build contract tests, including the four negative release cases;
- Zizmor 1.29.0 with offline `auditor` persona and complete collection, with no findings;
- unsigned and protected-property signed `:app:assembleRelease` builds;
- `apksigner` plus `apkanalyzer` verification of application ID, version `0.1.0` / code `1`, the
  committed certificate fingerprint, and absence of `CAMERA`, `RECORD_AUDIO`, and `INTERNET`;
- `./gradlew check assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest`.

Hosted evidence for the CI/CD implementation on 2026-08-12 currently includes:

- the `host` gate passed on `dc155b8`;
- the ordinary API-35 and API-36 emulator matrix passed on `dc155b8`;
- API-37 reached and executed all 21 data instrumentation tests after the AVD received the required
  4096 MB of RAM, then exposed an Android-17 migration-fixture incompatibility: Room could not create
  its lock file because the app database directory did not yet exist;
- Zizmor passed on `dc155b8`; CodeQL compiled the project but extracted no sources because Gradle
  restored every compilation task from cache. Both hosted findings are fixed after this snapshot and
  require confirmation on the next `main` run.

The PR-only API-35 smoke and Dependency Review jobs remain unobserved. No production tag or release
workflow has run. Connected opt-in physical suites were not run.

## Historical physical artifacts

The detailed record is [pixel-8-pro-baseline-2026-08-09.md](research/pixel-8-pro-baseline-2026-08-09.md).
Its strongest acceptance artifacts are:

| Commit | APK SHA-256 | Profile generation | Scenarios evidenced |
| --- | --- | --- | --- |
| `65a5236` | `926218feb27b4778f76e35bc786c6bbfd93ed259838a0bcc4c6b4c084728bcb3` | historical v3-era profile | Locked/screen-off/forced-idle scheduled START→STOP; reboot restoration scenario |
| `e699afd` | `7549e641615aadf431e1de5498f4659d33d82bcfe19a29eb28c83d06ac2c7b05` | historical v3-era profile | Hardened production rehearsal and focused scheduler acceptance |

Later changes added mandatory saved-media verification, capture-specific rehearsal receipts,
selector schema v4, typed dialogs, and richer diagnostics. Therefore the table is historical proof,
not current `main` proof.

## Validation observed for this documentation update

The audit began from a clean `main@172dd0b`. Before remediation, the following was observed:

- `:app:assembleDebug` passed;
- host unit tests were rerun: 343 passed, 0 failed;
- `:app:assembleDebugAndroidTest` and `:data:assembleDebugAndroidTest` passed;
- the full `./gradlew check assembleDebug` gate failed on six Detekt findings:
  `JsonColumnCodec` (`TooManyFunctions`), `PixelCameraPort` (`TooManyFunctions`),
  `recoverCameraDialog` (`LongMethod`), `PixelCameraAccessibilityPort` (`TooManyFunctions`),
  `PixelCameraDialogRecoveryDispatcher.recover` (`ReturnCount`), and
  `KnownPixelCameraProfileCatalogTest` (`LongMethod`);
- connected and opt-in physical automation suites were not run as part of that audit.

Commit `f33d15c` structurally split the broad ports/adapters/codecs and dialog verification routines
without suppressions or policy changes. After that remediation:

- focused `:data`, `:automation`, and `:app` Detekt tasks passed;
- `:automation:test`, `:app:testDebugUnitTest`, and `:app:compileDebugAndroidTestKotlin` passed;
- `./gradlew check assembleDebug --console=plain` passed.

Connected and opt-in physical automation suites were not run for this documentation update.

## Current acceptance work

1. Build a fresh current-HEAD APK, record its SHA-256, install it on the exact Pixel 8 Pro, and prove
   installed/local artifact identity.
2. Run a production profile/schedule rehearsal and verify the real MediaStore saved-video contract.
3. Repeat screen-on/unlocked, screen-on/locked, screen-off/locked, forced Doze, already-open Camera,
   process-death, and reboot-before-START scenarios.
4. Induce each recoverable typed dialog and verify exactly one safe dispatch plus disappearance.
5. Complete the Pixel 8 Pro layout matrix for orientation, navigation modes, and real IME behavior.

Follow [testing/PHYSICAL_PIXEL.md](testing/PHYSICAL_PIXEL.md) for evidence and cleanup requirements.
