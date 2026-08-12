# Lenswake status and evidence boundary

**Snapshot:** implementation working tree on 2026-08-12

**Reviewed:** 2026-08-12

This file distinguishes implemented behavior, local validation, Android integration evidence, and
physical Pixel proof. It is a snapshot, not the source of implementation truth.

## Current support claim

The implementation now recognizes exactly 17 non-folding models: Pixel 6/6 Pro/6a, 7/7 Pro/7a,
8/8 Pro/8a, 9/9 Pro/9 Pro XL/9a and 10/10 Pro/10 Pro XL/10a. Fold, Pro Fold, Tablet, Pixel 5a,
unknown and future models are rejected. Selector schema is v5 and persistence is Room v9.

Pixel 7 and Pixel 8 Pro are the only certification targets; the other 15 are permanently
`EXPERIMENTAL`. A reachable promotion path now verifies a release-key-signed certification JAR, the
exact installed APK SHA-256, target model and accepted Experimental profile fingerprint before storing
`CERTIFIED`; an APK change makes that tier ineffective. Current HEAD has not passed the same signed
release APK gate on both targets, so no certification bundle or current certified release claim exists.

The historical exact Pixel 8 Pro environment remains:

```text
Device:              Google Pixel 8 Pro (husky)
Android:             17 / SDK 37
Build:               CP2A.260705.006 / 15641320
Display:             1008 × 2244 @ 360 dpi
Locale:              en-US-u-fw-mon-mu-celsius
Pixel Camera:        10.4.117.936816638.14
Camera versionCode:  69481630
Selector schema:     historical v4; current implementation v5
Capture exposed:     receipt-gated matrix; no current signed-release physical receipts
```

On 2026-08-12 read-only probes observed Pixel 8 Pro on the stable fingerprint above and Pixel 7 on
a beta fingerprint, both with Pixel Camera versionCode 69481630. Profile installation, preflight,
and action dispatch now accept only the dated Google-published global Android 17 build-ID window:
`CP2A.260705.006` for Pixel 6–7a and `CP2A.260705.006`/`CP2A.260805.005` for Pixel 8–10a. The observed
beta plus carrier-suffixed, custom, older, and cross-cohort IDs are rejected. These probes are not
installation, rehearsal, saved-media, or acceptance, and the local fingerprint check is not remote
or hardware-backed OS attestation.

The same read-only session pinned both installed Pixel Camera 10.4.117 variants by package-part,
base-APK, signer, resources, and DEX identity. The resulting
[selector provenance record](research/pixel-6-10a-template-provenance.md) maps every new 4K/60,
Time Lapse speed, lens, and Night Sight candidate to its exact resource and code construction where
available. It also corrected the 60 FPS candidate to visible text `60` plus content description
`60 FPS`; both templates are consequently version 2. This is static implementation evidence only:
current Accessibility exposure, uniqueness, selected/checked state, and combination availability
remain subject to a fresh exact-environment rehearsal.

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
| Capture-specific rehearsal receipts | Yes | Unit/Room tests | Current schema-v5 physical rehearsal open |
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
- manually approved candidate signing, package/version/certificate/permission verification,
  checksums and provenance without publication permission;
- a separate manual publication run that verifies the exact candidate run/tag/commit/APK digest and
  content-addressed Pixel 7 plus Pixel 8 Pro acceptance records/profile fingerprints before protected
  approval, signs an APK-bound certification bundle with the release key, and publishes it with the APK.

Actions are full-SHA pinned and repository policy requires SHA pins. The protected `release`
environment is restricted to `v*` tags and contains the signing secrets. The current certificate
fingerprint is committed, while the private keystore remains outside the repository.

Local workflow validation and signed-APK verification do not constitute hosted CI evidence. No
production tag or GitHub Release has been created by this implementation, and no physical-device
claim transfers to a future signed APK until that exact artifact is tested.

On 2026-08-12 the implementation working tree passed:

- `actionlint` for all three workflows and `shellcheck` for all CI scripts;
- release identity/build contract tests, including signed certification-bundle creation, exact
  receipt/signer verification, and negative signer/APK/evidence cases;
- Zizmor 1.29.0 with offline `auditor` persona and complete collection passed before the certification
  workflow delta; the binary was unavailable for a local rerun of that delta, which still awaits hosted Security;
- unsigned and protected-property signed `:app:assembleRelease` builds;
- `apksigner` plus `apkanalyzer` verification of application ID, version `0.1.0` / code `1`, the
  committed certificate fingerprint, and absence of `CAMERA`, `RECORD_AUDIO`, and `INTERNET`;
- `./gradlew check assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest`.
- ordinary `:data:connectedDebugAndroidTest` on the explicitly selected Pixel 8 Pro / Android 17:
  24/24 tests passed, including Room v8→v9 and certification persistence; no Camera fixture ran.

Hosted evidence for the CI/CD implementation on 2026-08-12 currently includes:

- [main CI run 31575634848](https://github.com/po4yka/Lenswake/actions/runs/31575634848)
  passed the host gate and the API-35/36/37 matrix on `8c1454e`; API 37 used the current
  `37.2-beta2` 16 KB Google APIs image, ran 22 data tests and 121 app tests, and kept all four
  opt-in physical fixtures skipped;
- [Security run 31575635008](https://github.com/po4yka/Lenswake/actions/runs/31575635008)
  passed Zizmor and CodeQL on the same SHA;
- temporary validation [PR #3](https://github.com/po4yka/Lenswake/pull/3) passed the PR host gate,
  API-35 instrumentation smoke, Dependency Review, Zizmor, and CodeQL against current `main`; the
  empty validation PR was then closed and its branch deleted;
- GitHub Dependency Graph is enabled so Dependency Review is supported.

No production tag or release workflow has run. Connected opt-in physical suites were not run.

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

1. Build one signed release APK, record its SHA-256, install that exact artifact on Pixel 7 and
   Pixel 8 Pro, and prove installed/local artifact identity on both.
2. Run the complete capture-combination rehearsal matrix and verify the MediaStore saved-video contract.
3. Repeat screen-on/unlocked, screen-on/locked, screen-off/locked, forced Doze, already-open Camera,
   process-death, and reboot-before-START scenarios.
4. Induce each recoverable typed dialog and verify exactly one safe dispatch plus disappearance.
5. Complete the certified-device layout and cleanup checks on both Pixel 7 and Pixel 8 Pro.

Follow [testing/PHYSICAL_PIXEL.md](testing/PHYSICAL_PIXEL.md) for evidence and cleanup requirements.
