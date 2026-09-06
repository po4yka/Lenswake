# Pixel 8 Pro August 2026 build admission — 2026-09-06

## Purpose

[Selector-template provenance](pixel-6-10a-template-provenance.md) recorded that the August 2026
global stable build ID `CP2A.260805.005` was listed for the Pixel 8/9/10 cohorts but stayed rejected
because its incremental and complete fingerprint had no reproducible provenance. This record supplies
that provenance for **Pixel 8 Pro (`husky`) only**, from a read-only observation of the connected
certification target, and admits exactly that one tuple.

Nothing here is rehearsal, certification, or release evidence. Admission only lets the exact
environment reach the rehearsal gate; the derived profile is still `NEEDS_REHEARSAL` with
`verifiedAt = null`.

## Environment

```text
Device:                  Google Pixel 8 Pro (husky)
Android:                 17 / SDK 37
Build ID:                CP2A.260805.005
Incremental:             15828068
Build fingerprint:       google/husky/husky:17/CP2A.260805.005/15828068:user/release-keys
Build tags / type:       release-keys / user
Build flavor:            husky-user
Security patch:          2026-08-05
Display:                 1008 x 2244 px, 360 dpi (no wm size/density override)
Rotation:                0 (portrait)
Font scale:              1.0
Locale:                  en-US-u-fw-mon-mu-celsius
System locales:          en-US-u-fw-mon-mu-celsius,ru-RU-u-fw-mon-mu-celsius
Device timezone:         Asia/Tbilisi
Pixel Camera package:    com.google.android.GoogleCamera
Pixel Camera version:    10.4.117.936816638.14 (versionCode 69481630)
Pixel Camera signer:     f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83
```

The exact ADB serial was selected for every command through `adb -s "$PIXEL_SERIAL"`; it is not
stored in this note. The device was not modified: no permission was granted or revoked, no setting
was written, no alarm or schedule was created, and no capture was started.

## Difference from the previously admitted environment

Only the operating-system build moved. Every other admission input is byte-identical to the
[historical Pixel 8 Pro baseline](pixel-8-pro-baseline-2026-08-09.md):

| Admission input | Previously admitted | Observed now | Same? |
| --- | --- | --- | --- |
| Model / codename | Pixel 8 Pro / husky | Pixel 8 Pro / husky | yes |
| Android SDK | 37 | 37 | yes |
| Build ID / incremental | `CP2A.260705.006` / `15641320` | `CP2A.260805.005` / `15828068` | **no** |
| Display | 1008 x 2244 @ 360 | 1008 x 2244 @ 360 | yes |
| Locale | `en-US-u-fw-mon-mu-celsius` | `en-US-u-fw-mon-mu-celsius` | yes |
| Font scale / orientation | 1.0 / portrait | 1.0 / portrait | yes |
| Camera versionCode | 69481630 | 69481630 | yes |
| Camera signer SHA-256 | `f0fd6c5b…d60db83` | `f0fd6c5b…d60db83` | yes |

Because `PixelSystemBuildPolicy.isApprovedGlobalStable` requires the exact `(buildId, incremental)`
pair, the single changed row made `KnownPixelCameraProfileCatalog.exactMatch` return `null` on the
current target device. Profile installation, preflight profile availability, and every action
dispatch were therefore unreachable on the connected Pixel 8 Pro until this admission.

## Exact inspection commands

```bash
adb devices -l

adb -s "$PIXEL_SERIAL" shell getprop ro.build.fingerprint
adb -s "$PIXEL_SERIAL" shell getprop ro.build.id
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.incremental
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.release
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.sdk
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.security_patch
adb -s "$PIXEL_SERIAL" shell getprop ro.build.tags
adb -s "$PIXEL_SERIAL" shell getprop ro.build.type
adb -s "$PIXEL_SERIAL" shell getprop ro.build.flavor
adb -s "$PIXEL_SERIAL" shell getprop ro.product.model
adb -s "$PIXEL_SERIAL" shell getprop ro.product.device
adb -s "$PIXEL_SERIAL" shell getprop ro.product.name
adb -s "$PIXEL_SERIAL" shell getprop persist.sys.locale
adb -s "$PIXEL_SERIAL" shell settings get system system_locales
adb -s "$PIXEL_SERIAL" shell settings get system font_scale
adb -s "$PIXEL_SERIAL" shell settings get system user_rotation
adb -s "$PIXEL_SERIAL" shell wm size
adb -s "$PIXEL_SERIAL" shell wm density

adb -s "$PIXEL_SERIAL" shell dumpsys package com.google.android.GoogleCamera
adb -s "$PIXEL_SERIAL" shell pm path com.google.android.GoogleCamera
adb -s "$PIXEL_SERIAL" shell \
  'for p in $(pm path com.google.android.GoogleCamera | cut -d: -f2); do sha256sum "$p"; done'
adb -s "$PIXEL_SERIAL" pull "$BASE_APK_PATH" "$TEMP_DIR/gcam-base.apk"
apksigner verify --print-certs "$TEMP_DIR/gcam-base.apk"
```

`wm size` and `wm density` reported only `Physical size` / `Physical density` with no override line,
so the observed geometry is the device default and not an ADB-applied override.

## Installed Pixel Camera identity

The active `com.google.android.GoogleCamera` is the updated `/data/app` variant, versionCode
`69481630`, alongside the untouched `/product/priv-app` factory variant (versionCode `68281438`,
`9.8.102.738511538.14`), which the runtime does not resolve.

`apksigner verify --print-certs` on the pulled base APK reported:

```text
Signer #1 certificate SHA-256 digest: f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83
Source Stamp Signer certificate SHA-256 digest: 3257d599a49d2c961a471ca9843f59d341a405884583fc087df4237b733bbd6d
```

Signer #1 equals `SUPPORTED_PIXEL_CAMERA_IDENTITY.signingCertificate`, so the camera-identity gate is
unchanged by this record.

Per-part SHA-256 of the resolved package (base plus fifteen splits):

```text
ecc35ec3ad08ba5fec7252372021b79c07469ad44d76b73f89afa63f21462b80  base.apk
1d389db6b48583a8e214145745e4fcf0fdf4b3639defdaad807270f011f43fed  split_camera_vkp_asset_module.apk
3dd36757c4ba84fb8945ef24ebaf914df1b2ec641510439a7c203111c41250f7  split_deeprestore_split_module_p23.apk
d5f0b2dd9a523ad8914b70d2f8abb19cf9e4b70fa756e907928f2c532e3b3cf4  split_geo_cal_split_module_husky.apk
6a89f7e1c8050cd9d16963438cf7341b4a8d5cb3f1a904c43c43137f039a65ef  split_hdrplus_asset_module_p23.apk
98aadfc75617a2c197c41e92e2c196ff78fbca3d637c976934d66de93b66136c  split_hotshot_split_module.apk
ca7fc762c26169f2d54a12112d53d76ae9ad76e37b28ee146a491853b1e5b942  split_imax_split_module.apk
aa51db2b46feec3c5dc6d1f1edd3368f5edacbaddef00906f7dc63cccdf81b91  split_l2l_assets_p23_module.apk
091a7e09810636a97842188b86dbbd53950edb1878e8cfa6deea247f29f02b7d  split_lightcycle_asset_module.apk
b359829534400d0228ad500ca201f50400fac8d75ee3253af411e860527f4ebb  split_motion_blur_asset_module_p23.apk
5c667389404b6dfe34df431a85a8d7c4c595fb42e03205c96dd93fbd723689c1  split_portrait_asset_module.apk
5d6cd31e3e68d3b6428f8f35abdc1dbaea90de36fb67db901c131b99ac22260c  split_portrait_asset_module_p23.apk
10bd29abe2288d9e9a66ffef4202e08fbb2e78b45116661087ca77d10ea8ec9a  split_roi_tracking_asset_module_p23.apk
7efc87c9ebe57d02170218e8173575387457c19857d8d8d8156d003e67ce3b52  split_smartcapture_split_module.apk
53e5ba1afd44683df746218a37671cff2378d09c7276025845901ad6ac0a2c82  split_smartzoom_split_module.apk
f1840c2c6e939a5d07817a8e8b6d365924e74b69a82b114f3789ed44f5fd6d46  split_startup_jni_split_module.apk
```

## Source change admitted by this record

`PixelGlobalStableBuildSet` gains one constant that admits both the July tuple and the newly observed
August tuple, and only Pixel 8 Pro is assigned to it. Every other supported model keeps
`JULY_2026_EXACT`.

```text
JULY_2026_EXACT                      CP2A.260705.006 / 15641320
PIXEL_8_PRO_JULY_AUGUST_2026_EXACT   CP2A.260705.006 / 15641320
                                     CP2A.260805.005 / 15828068
```

Pixel 8 Pro keeps the July tuple so the historical baseline environment and its recorded evidence
stay admissible.

## Evidence boundary

Confirmed by this record:

- the complete Android 17 fingerprint, build ID, and incremental currently running on the connected
  Pixel 8 Pro, read from `ro.build.*` through a serial-pinned read-only ADB session;
- that display geometry, density, locale, font scale, orientation, Pixel Camera versionCode, and
  Pixel Camera signer are unchanged from the previously admitted Pixel 8 Pro environment;
- the resolved Pixel Camera package-part identity for that environment.

Not confirmed by this record:

- any August-build incremental for Pixel 8, 8a, 9, 9 Pro, 9 Pro XL, 9a, 10, 10 Pro, 10 Pro XL, or
  10a. Those models are not admitted for `CP2A.260805.005` and must not inherit this observation;
- any Pixel Camera Accessibility tree, node, selector, or geometry on the August build. The selector
  templates are unchanged and remain the evidence described in
  [selector-template provenance](pixel-6-10a-template-provenance.md);
- any rehearsal, saved-media, capture-combination, locked/Doze, reboot-recovery, or certification
  result for the August build. The derived profile is created as `NEEDS_REHEARSAL`, and production
  use still requires a successful rehearsal of the exact capture configuration in this exact
  environment;
- any release-signed APK acceptance. The current-HEAD signed release gate on Pixel 7 and Pixel 8 Pro
  remains open.

The fingerprint allowlist is a dated local contract check, not remote or hardware-backed OS
attestation. A hostile custom image could spoof these public properties.

This record deliberately does not assign its own commit identity.
