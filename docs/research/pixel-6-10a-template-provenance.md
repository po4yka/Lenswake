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

## System-build admission

On 2026-08-12 the official [Google full OTA image matrix](https://developers.google.com/android/ota)
listed these latest unsuffixed global Android 17 releases for the supported devices:

- Pixel 6/6 Pro/6a and Pixel 7/7 Pro/7a: `CP2A.260705.006` (July 2026);
- Pixel 8/8 Pro/8a, Pixel 9/9 Pro/9 Pro XL/9a, and Pixel 10/10 Pro/10 Pro XL/10a:
  `CP2A.260805.005` (August 2026).

The same matrix identifies carrier/region-specific packages with distinct suffixed build IDs. The
runtime policy admits July for every supported model and August only for the Pixel 8–10a cohort;
the July overlap keeps the read-only-observed Pixel 8 Pro stable rollout environment admissible.
It requires an exact product/device codename pair and rejects all unlisted IDs rather than trying to
infer stability from `user/release-keys`. A future monthly build is intentionally unsupported until
this dated allowlist and its tests are reviewed and updated.

This check can reject beta, carrier, custom, stale, and malformed fingerprint values. It cannot
cryptographically distinguish a hostile custom OS that deliberately spoofs an exact public Google
fingerprint; that would require a separate trusted-attestation design outside the current local-only
contract.

## Evidence boundary

Static package resources do not prove that a control is present, actionable, selected, or available
for a particular mode/lens/device. Every action is freshly resolved and postcondition-checked, and
every exact capture combination remains unavailable to schedules until a complete saved-media
rehearsal produces a current receipt. Neither template is physical certification evidence. Pixel 7
and Pixel 8 Pro remain Experimental until the same signed release APK passes the full physical gate.
