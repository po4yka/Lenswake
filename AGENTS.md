# Lenswake agent contract

Lenswake is a reliability-sensitive personal Android application that schedules unattended capture
in the native Google Pixel Camera. Conversation with the user is in Russian by default; repository
artifacts remain in their established language.

## Sources of truth

- Before design or edits, read `README.md`, this file, and the relevant parts of
  `docs/ARCHITECTURE.md`.
- Current implemented behavior is established by source, manifests, Gradle configuration, committed
  Room schemas, and tests. `docs/STATUS.md` records evidence boundaries, not implementation truth.
- For Pixel Camera or Android runtime behavior, use this precedence: current target Pixel 8 Pro
  observation; current official Android/AOSP documentation; reproducible serial-pinned ADB evidence;
  dated repository research; assumptions.
- If sources disagree materially, surface and resolve the drift. Do not silently encode an assumption
  or transfer evidence from another commit, APK, device, package split, locale, or profile schema.

## Product and security invariants

- Pixel Camera (`com.google.android.GoogleCamera`) owns capture and output. Never replace it with
  CameraX, Camera2, MediaRecorder, custom encoding, or a Lenswake-owned camera service.
- Preserve keyguard and Android/Pixel Camera privacy indicators. Never unlock the device, inject a
  credential, bypass authentication, suppress indicators, or add covert or remote recording.
- Remain local-first: no analytics, remote logging, cloud account, advertising, or arbitrary network
  dependency.
- Scope Accessibility processing to Pixel Camera. Do not persist unrelated app content or complete UI
  trees outside an explicitly authorized, bounded diagnostic.
- Preserve `allowBackup=false` and complete `cloud-backup` plus `device-transfer` exclusions for all
  local data domains.
- Do not request `CAMERA`. Every new permission or production dependency needs a concrete current use,
  explicit failure behavior, and a security/privacy review.
- Do not guess Pixel Camera activities, resource IDs, labels, hidden APIs, geometry, or state. Observe,
  record provenance under `docs/research/`, isolate the detail in a profile/adapter, and fail closed.

## Architecture contracts

- Keep `:core` and `:automation` platform-neutral. Android adapters, services, Compose, and the explicit
  composition root belong in `:app`; Room entities, migrations, mappings, and implementations belong
  in `:data`.
- START and STOP are separate persisted workflows with independent exact alarms. Stable identities,
  durable checkpoints, stale-trigger rejection, process-death recovery, and revision-safe transitions
  are correctness requirements.
- Preserve the service-bound durable alarm transport, credential-protected delivery journal,
  device-protected recovery checkpoints, bounded execution foreground service, recovery `JobService`,
  and private wake gateway unless a replacement is proven on the target device.
- Never keep Lenswake alive for the multi-hour recording. Pixel Camera records while Lenswake is idle.
- Reserve Pixel Camera ownership durably. A matching STOP may preempt its START; unrelated executions
  must not race for the single external camera UI.
- Interaction order is: public Android API; Accessibility semantic action; gesture using freshly
  discovered node bounds; verified normalized profile gesture; optional privileged provider; explicit
  failure. The current production graph has no privileged provider.
- A cached Accessibility path is never authority to click. Re-resolve the semantic fingerprint in a
  fresh snapshot immediately before dispatch and reject changed, missing, ambiguous, or obscured targets.
- Dispatch is not success. Every external action needs a finite timeout, bounded observable retries,
  and a verified postcondition. Cancellation must propagate.
- START succeeds only after Pixel Camera recording is verified. A current session completes only after
  STOP is verified and required saved-media evidence is verified. Pixel Camera still owns the file.
- Preserve the write-ahead Record and Stop checkpoints. Uncertainty must converge through inspection;
  do not resend a potentially accepted action blindly.
- Unattended execution requires a timestamped `VERIFIED` profile for the exact environment, current
  selector schema, and a qualifying rehearsal for the exact capture configuration/profile definition.
- Automatic dialog recovery must be typed, profile-scoped, freshly rechecked before dispatch, and
  postcondition-verified. Unknown or terminal dialogs are never clicked.
- Room migrations are explicit and schema exports remain committed. Never use destructive migration
  for schedules, profiles, sessions, events, or environment history. Corrupt entries must be isolated
  and surfaced without terminating the whole observable stream.
- Shizuku remains optional behind `PrivilegedBridge`; its absence is a normal explicit capability state.

## Working method

- Before editing, confirm working directory, branch/worktree, and `git status`. Preserve unrelated user
  work and inspect ancestry before duplicating a requested fix.
- Understand and, where practical, reproduce the current behavior. Fix the root cause with the smallest
  coherent change; update all in-repository callers when changing a contract.
- Add or update tests for changed pure logic, failures, races, persistence, migrations, selectors,
  manifests, alarms, recovery, and Android boundaries as applicable.
- Use structured concurrency. Do not swallow `CancellationException`, create arbitrary process-wide
  coroutine scopes, use unbounded waits, or substitute fixed delays for observed UI state.
- Keep structured session diagnostics useful: state, operation, outcome, attempt, duration, interaction
  method, selector confidence, failure, and bounded metadata.
- Record new runtime findings with device, OS/build, Pixel Camera package/version, Lenswake commit,
  APK hash when applicable, initial state, exact actions, result, and conclusion.
- Keep README, architecture, status, setup, and evidence documents current when their contracts change.

## Validation and evidence

- Run the smallest relevant test first, then the affected module checks.
- The intended full local gate is `./gradlew check assembleDebug`. If it fails, report the exact task
  and findings; never call a partial green build the full gate.
- Compile or run affected Android instrumentation suites for manifests, packaging, alarms, persistence,
  migrations, services, permissions, and Android adapters.
- Do not run opt-in physical-device fixtures or mutate device state without explicit authorization.
  Always select the exact device using `adb -s "$PIXEL_SERIAL"` or `ANDROID_SERIAL="$PIXEL_SERIAL"`.
- Follow `docs/testing/PHYSICAL_PIXEL.md`. Physical claims must name the device, Android build, Pixel
  Camera version, Lenswake commit, APK hash/installed-artifact evidence, initial state, scenario, and
  observed postconditions; clean up alarms, schedules, idle/battery overrides, and permissions changed.
- Never treat compilation, unit tests, instrumentation, an emulator, a different Pixel, matching device
  identity, or historical evidence as current target Pixel Camera reliability proof.
- Report these evidence categories separately: targeted checks, full local gate, Android instrumentation,
  physical-device scenario, artifact identity, and hosted CI. Do not claim an unobserved category.

## Definition of done

A change is done only when requested behavior and failure paths exist, relevant checks pass or exact
blockers are reported, privacy and architecture invariants remain intact, documentation matches reality,
and every unverified physical/device assumption is named. Compilation alone is not completion.
