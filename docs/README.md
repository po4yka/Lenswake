# Lenswake documentation

This index separates current contracts from dated evidence. A research result proves only the
artifact and environment it names; it does not silently update when `main` changes.

## Current repository documents

| Document | Purpose |
| --- | --- |
| [README](../README.md) | Repository overview, implemented product surface, quick setup, limits |
| [Status](STATUS.md) | Current implementation/evidence boundary and known validation blockers |
| [Architecture](ARCHITECTURE.md) | Current modules, runtime, persistence, state, recovery, and security contracts |
| [Setup](SETUP.md) | Build, install, Android capability setup, profile installation, rehearsal |
| [Physical Pixel validation](testing/PHYSICAL_PIXEL.md) | Evidence levels, safe device selection, scenario and cleanup requirements |
| [Agent contract](../AGENTS.md) | Non-negotiable engineering rules and validation behavior |

## Dated evidence records

| Document | Evidence | Applicability |
| --- | --- | --- |
| [Pixel 8 Pro baseline, 2026-08-09](research/pixel-8-pro-baseline-2026-08-09.md) | Physical wake, rehearsal, locked/Doze schedule, reboot recovery for named historical APKs | Historical; not current selector-schema-v4 HEAD acceptance |
| [Pixel Camera dialog recovery](research/pixel-camera-dialog-recovery.md) | Pixel 7 package/resource inspection for candidate typed dialogs | Implementation input; Pixel 8 Pro physical recovery remains open |
| [Android 15–17 system insets](research/android-15-17-system-insets.md) | Official-platform research plus emulator/Pixel 7 layout evidence | Current contract is consistent; full Pixel 8 Pro UI/IME matrix remains open |

## Maintenance rules

- Update README when user-visible capability, support, setup, or limits change.
- Update Architecture when a module, persisted contract, runtime component, state flow, or security
  boundary changes.
- Update Status after observed gates or acceptance evidence changes.
- Add a dated research record for new Pixel Camera or Android runtime observations. Include device,
  OS/build, Camera variant/version, Lenswake commit, APK hash where applicable, initial state,
  exact actions, result, and conclusion.
- Never rewrite historical evidence to imply it tested a newer artifact. Add a superseding record.
