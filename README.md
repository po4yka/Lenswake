# Lenswake

**Schedule unattended recordings in the native Google Pixel Camera.**

Lenswake is a local-first Android application for a personally owned Pixel. It wakes a locked
device at an exact time, opens Google Pixel Camera, drives its visible UI, verifies that capture
started, returns at the independent STOP deadline, verifies that capture stopped, and confirms
that Pixel Camera published a new video.

Pixel Camera always owns the camera and the output. Lenswake does not use CameraX, Camera2,
MediaRecorder, custom encoding, root, a cloud service, or a hidden recording path.

> [!IMPORTANT]
> The supported target is a fixed set of 17 non-folding Pixel 6–10a phones. Pixel Fold/Pro Fold,
> Tablet, Pixel 5a and future models are rejected. Pixel 7 and Pixel 8 Pro are certification
> targets. Until the exact signed release APK passes both physical gates and its release-key-signed
> certification bundle is imported, every installed profile is Experimental; local rehearsal never
> promotes it to Certified.

## Repository status

| Area | Current state |
| --- | --- |
| Application | `0.1.0`, active development |
| Platform | `minSdk 35`, `compileSdk/targetSdk 37` |
| Profile catalog | 17 fixed Pixel model/codename pairs · selector schema v5 · standard/telephoto templates |
| Capture contract | Video 4K/60; Time Lapse Auto/5×/10×/30×/120×; discovered Night Sight Time Lapse; exact lens-specific receipts |
| Local implementation | Schedules, profiles, rehearsal, durable alarms, wake, START/STOP automation, saved-media verification, recovery, diagnostics |
| Historical device proof | Locked/Doze and reboot scenarios passed for explicitly recorded older v3 artifacts |
| Current-HEAD device proof | The same signed release APK still requires full Pixel 7 and Pixel 8 Pro acceptance |
| Distribution | Signed minified APK workflow for GitHub Releases; no release published yet |
| Hosted CI | Host checks, API 35 PR smoke, API 35/36/37 main/nightly matrix, CodeQL and dependency review configured |

The distinction above matters: passing source tests does not prove undocumented Pixel Camera UI
automation, and physical proof for one APK does not automatically transfer to later commits. See
[current status](docs/STATUS.md) and the
[physical Pixel evidence contract](docs/testing/PHYSICAL_PIXEL.md).

## What is implemented

- Create, edit, enable, disable, delete, and test one-time recording schedules.
- Register independent exact START and STOP alarms with stable identities, stale-trigger rejection,
  rollback-safe schedule mutations, reboot/time-change restoration, and durable delivery journals.
- Wake a locked display through a bounded full-screen alarm notification without dismissing keyguard.
- Dynamically resolve and launch the secure Pixel Camera activity; no camera Activity is hard-coded.
- Converge Pixel Camera through explicit START and STOP state machines with finite timeouts,
  operation-specific retries, selector scoring, ambiguity rejection, and postcondition checks.
- Keep action dispatch separate from success. Record and Stop clicks never count as verified state.
- Persist write-ahead recording ownership so process death cannot silently lose responsibility for STOP.
- Capture a MediaStore baseline before Record and complete only after exactly one new, published,
  Pixel Camera-owned video has positive size and duration.
- Run profile-bound and schedule-bound production rehearsals with an independent, session-bound exact
  STOP backstop. Rehearsal evidence is tied to the exact profile definition and capture configuration.
- Recognize typed Pixel Camera duration/file-size limit dialogs; recover only typed safe cases after a
  fresh presence check, and fail closed for storage, policy, unknown, changed, or ambiguous dialogs.
- Show local session timelines, duration, retries, selector confidence, interaction/fallback metrics,
  alarm-transport incidents, and corrupt-profile notices; share the bounded view as plain text.
- Persist schedules, profiles, sessions, events, and environment snapshots in Room v9 with explicit
  migrations. Sensitive Room and SharedPreferences state is excluded from cloud backup and D2D transfer.

## Reliability model

```text
persist schedule
      │
      ├── exact START alarm ── durable handoff ── wake ── secure Pixel Camera
      │                                             │
      │                                             ▼
      │                                  converge + verify recording
      │                                             │
      │                                             ▼
      │                                  Pixel Camera owns capture
      │
      └── exact STOP alarm ─── durable handoff ── locate recording UI
                                                    │
                                                    ▼
                                          stop + verify stopped
                                                    │
                                                    ▼
                                         verify new saved video
```

START succeeds only after Lenswake observes Pixel Camera recording. A current session completes
only after STOP is verified and the required saved-media evidence is verified. Retries are bounded
and observable; low-confidence or unknown UI states fail explicitly instead of clicking blindly.

## Setup

You need:

- JDK 17;
- Android SDK 37 and platform tools;
- a Pixel running Android 15 or newer for installation;
- one of the fixed supported non-folding Pixel 6–10a models in the exact supported environment;
- Google Pixel Camera installed;
- USB debugging for development and physical validation.

Build and install the debug APK:

```bash
./gradlew assembleDebug
adb -s "$PIXEL_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

On the device, complete Setup in this order:

1. Grant notification permission and keep Lenswake notifications enabled.
2. Allow exact alarms and full-screen intents.
3. Grant full video-library read access; selected-photos-only access is insufficient for unattended
   saved-file verification.
4. Enable the Lenswake Accessibility Service. It is package-scoped to Pixel Camera.
5. Install the exact-environment profile. Experimental models require an explicit best-effort warning acceptance.
6. Run the sequential capture matrix and confirm each intended exact combination passes.
7. Enable the future schedule only after Setup reports no blocking readiness checks.

Detailed remediation paths and expected blockers are in [Setup](docs/SETUP.md).

## Development

Run the smallest affected test first. The intended full local gate is:

```bash
./gradlew check assembleDebug
```

Android instrumentation is a separate gate:

```bash
ANDROID_SERIAL="$PIXEL_SERIAL" ./gradlew \
  :data:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest
```

The current repository passes the full local gate shown above. Android-test APK compilation passed
separately; connected and opt-in physical suites were not run for this documentation update. See
[docs/STATUS.md](docs/STATUS.md).

GitHub Actions repeats the host gate, runs ordinary instrumentation on Android emulators, and keeps
those results separate from physical Pixel evidence. Release tags build and retain a signed candidate
without publication permission. A separate protected workflow can publish only that exact candidate
after its SHA-256 and content-addressed Pixel 7 plus Pixel 8 Pro acceptance records match. See the
[GitHub Releases runbook](docs/releasing/GITHUB_RELEASES.md).

Physical fixtures can change device state, create schedules and alarms, or start Pixel Camera. Run
them only with explicit intent, always pin the serial, and follow
[docs/testing/PHYSICAL_PIXEL.md](docs/testing/PHYSICAL_PIXEL.md).

## Architecture

The repository has four Gradle modules:

| Module | Owns |
| --- | --- |
| `:core` | Platform-neutral schedules, profiles, sessions, failures, readiness, repository contracts |
| `:automation` | Platform-neutral START/STOP convergence, ports, selectors, retries, verification |
| `:data` | Room v9 entities, migrations, mappings, repositories, environment/session history |
| `:app` | Compose UI, application composition, alarms, Android services, Accessibility and platform adapters |

Dependency direction is `:app → :automation/:core/:data`, `:automation → :core`, and
`:data → :core`. The complete current design and runtime contracts are in
[Architecture](docs/ARCHITECTURE.md).

## Known limits

- A capture is exposed to schedules only after a current exact receipt for mode, speed, lens and
  video settings. Unsupported or unavailable controls fail closed.
- System builds use a dated positive allowlist of complete Google fingerprint components, including
  the exact build ID and incremental. New monthly, beta, carrier-suffixed, custom, and malformed
  fingerprints fail closed until the full fingerprint provenance is reviewed.
  This local check is not cryptographic attestation against a hostile image spoofing public properties.
- `CERTIFIED` is an immutable release-evidence result limited to Pixel 7/Pixel 8 Pro. The app accepts
  it only from a release-key-signed bundle whose APK SHA-256 and exact Experimental profile fingerprint
  match locally. Changing the APK demotes the effective tier; rehearsal never promotes it.
- There is no interactive selector calibration, profile authoring/import, or arbitrary-device support.
- Shizuku is not implemented. The optional privileged boundary is wired to an explicit unavailable
  provider; the standard target path does not require it.
- Diagnostics sharing contains the ten most recent sessions, not a complete structured archive.
- Typed dialog recovery is implemented, but inducing and accepting each recoverable dialog on the
  supported Pixel 8 Pro remains a physical gate.
- Saved-media logic has integration coverage, but current HEAD still needs a real Pixel Camera
  end-to-end saved-video acceptance run.
- Direct-boot handling records recovery state, but Room-backed restoration waits for normal user unlock.
- No reliability percentage is claimed for current HEAD until its full physical matrix is rerun.

## Documentation

- [Documentation index](docs/README.md)
- [Current status and evidence boundary](docs/STATUS.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Device setup](docs/SETUP.md)
- [Physical Pixel validation](docs/testing/PHYSICAL_PIXEL.md)
- [GitHub Releases runbook](docs/releasing/GITHUB_RELEASES.md)
- [Pixel 6–10a selector-template provenance](docs/research/pixel-6-10a-template-provenance.md)
- [Historical Pixel 8 Pro baseline](docs/research/pixel-8-pro-baseline-2026-08-09.md)
- [Pixel Camera dialog recovery evidence](docs/research/pixel-camera-dialog-recovery.md)
- [Android 15–17 inset research](docs/research/android-15-17-system-insets.md)
- [Agent contract](AGENTS.md)

## Security and privacy

Lenswake is intended only for devices you own and control. It preserves keyguard, Android and Pixel
Camera privacy indicators, and the normal Pixel Camera capture UI. It does not inject credentials,
disable the lock screen, suppress indicators, upload telemetry, or add remote control.

The app intentionally does not request `CAMERA`: Pixel Camera owns capture. Accessibility events are
restricted to `com.google.android.GoogleCamera`, and local history is excluded from Android backup
and device transfer.

## License

[MIT](LICENSE) © 2026 Nikita Pochaev.
