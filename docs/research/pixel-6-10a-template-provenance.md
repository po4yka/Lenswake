# Pixel 6-10a selector-template provenance

**Observed:** 2026-08-12
**Scope:** read-only package inspection; no Lenswake install, Camera launch, rehearsal, capture, or
device-state mutation.

## Environment

- Pixel 7 (`panther`), Android 17 beta fingerprint, Pixel Camera versionCode `69481630`.
- Pixel 8 Pro (`husky`), Android 17 stable fingerprint
  `google/husky/husky:17/CP2A.260705.006/15641320:user/release-keys`, Pixel Camera versionCode
  `69481630`.
- Pixel Camera signer SHA-256:
  `f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83`.

## Static observations

Read-only APK resource inspection exposed semantic strings/resources for 4K Ultra HD, 60 FPS,
Time Lapse Auto/5x/10x/30x/120x, Tele, Ultrawide, front-camera switching, and Night Sight. Schema v5
uses those values only as semantic candidate selectors. The standard template omits telephoto; the
telephoto-class template includes it. Derived profiles copy no normalized coordinate gesture.

## Evidence boundary

Static package resources do not prove that a control is present, actionable, selected, or available
for a particular mode/lens/device. Every action is freshly resolved and postcondition-checked, and
every exact capture combination remains unavailable to schedules until a complete saved-media
rehearsal produces a current receipt. Neither template is physical certification evidence. Pixel 7
and Pixel 8 Pro remain Experimental until the same signed release APK passes the full physical gate.
