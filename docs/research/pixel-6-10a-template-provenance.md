# Pixel 6-10a selector-template provenance

**Observed:** 2026-08-12 19:11-19:18 +04:00

**Lenswake source inspected:** `main@1be1b90241d795827ab0a4b1057457b8f9f2a910`

**Remediation recorded by this change:** schema v5 selector templates version 2; the record does
not self-assign a commit hash

**Selector schema:** v5

**Scope:** source/catalog review, official Google documentation, and read-only serial-pinned
inspection of the installed Pixel Camera packages. Lenswake was not built or installed; Pixel Camera
was not launched; no UI tree, rehearsal, capture, permission, setting, alarm, or other device state
was changed.

## Evidence classification

This record distinguishes three kinds of evidence:

1. **Catalog contract** is the exact selector currently emitted by
   `KnownPixelCameraProfileCatalog.kt`. It proves what Lenswake will look for, not what Pixel Camera
   will expose.
2. **Version-pinned APK evidence** is a resource or code symbol found in the installed Pixel Camera
   `10.4.117.936816638.14` base APK. It proves that the package contains the value, not that a visible,
   unique, actionable Accessibility node uses it in a particular device/mode/lens/state.
3. **Official product documentation** proves a documented camera capability or human workflow. It
   does not define an Accessibility API and does not validate resource IDs, node properties,
   selector uniqueness, or postconditions.

Only a fresh serial-pinned Accessibility observation followed by a complete exact-combination
rehearsal can promote a candidate beyond these static evidence classes. No such observation or
rehearsal was performed here.

## Read-only device and package identity

Serials were selected from `adb devices -l`, assigned locally to `P7_SERIAL` and `P8P_SERIAL`, and
omitted from this repository record as required by the physical-test policy.

| Field | Pixel 7 | Pixel 8 Pro |
| --- | --- | --- |
| Model / codename | Pixel 7 / `panther` | Pixel 8 Pro / `husky` |
| Android release / SDK | 17 / 37 | 17 / 37 |
| Build ID / incremental | `CP41.260701.005` / `15834971` | `CP2A.260705.006` / `15641320` |
| Fingerprint | `google/panther_beta/panther:CinnamonBun/CP41.260701.005/15834971:user/release-keys` | `google/husky/husky:17/CP2A.260705.006/15641320:user/release-keys` |
| Build admission | Rejected by the current global-stable allowlist: beta product/release and unlisted build | Admitted exact July 2026 global-stable environment |
| Primary locale / system locale list | `en-US` / `en-US` | `en-US-u-fw-mon-mu-celsius` / `en-US-u-fw-mon-mu-celsius,ru-RU-u-fw-mon-mu-celsius` |
| Physical display / density | 1080 x 2400 / 420 dpi | 1008 x 2244 / 360 dpi |
| Font scale / current orientation | 1.0 / portrait (`mCurrentOrientation=0`) | 1.0 / portrait (`mCurrentOrientation=0`) |
| Pixel Camera package | `com.google.android.GoogleCamera` | `com.google.android.GoogleCamera` |
| Active version | `10.4.117.936816638.14` (`69481630`) | `10.4.117.936816638.14` (`69481630`) |
| Install provenance / ABI | Play (`com.android.vending`), arm64-v8a, data update over the system package | Play (`com.android.vending`), arm64-v8a, data update over the system package |
| APK signing | v3, one signer | v3, one signer |
| Signer certificate SHA-256 | `f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83` | same |
| Base APK SHA-256 | `60b9adbc20df3ab6dd21f0a81b353a2f87c0d290bfa6261d659acf0eef37773a` | `1584b19281e23788127895ff1754a450fbbf1bac080206159bc0c2e9358ba1a3` |
| Canonical 16-part manifest SHA-256 | `3cb59f03f42a4094075a2d158481da673ace1ca3cb6407c5572b9d1edb497816` | `1bd38c808a1673b40e56b6c6970f40d2d53fb38bfd261329fff0448e559bdc5f` |

`dumpsys package` also reported the factory system package at version
`9.8.102.738511538.14` (`68281438`); all selector inspection below used the active data package, not
that factory fallback.

### Installed split identity

Both active installs contain 16 APKs: the base, eight byte-identical common splits, and seven
device-generation/device-specific splits. SHA-256 was computed in place on each device; only each
base APK was pulled to a temporary local directory for `aapt2` and `apksigner` inspection.

| Common split | SHA-256 on both devices |
| --- | --- |
| `split_camera_vkp_asset_module.apk` | `1d389db6b48583a8e214145745e4fcf0fdf4b3639defdaad807270f011f43fed` |
| `split_hotshot_split_module.apk` | `98aadfc75617a2c197c41e92e2c196ff78fbca3d637c976934d66de93b66136c` |
| `split_imax_split_module.apk` | `ca7fc762c26169f2d54a12112d53d76ae9ad76e37b28ee146a491853b1e5b942` |
| `split_lightcycle_asset_module.apk` | `091a7e09810636a97842188b86dbbd53950edb1878e8cfa6deea247f29f02b7d` |
| `split_portrait_asset_module.apk` | `5c667389404b6dfe34df431a85a8d7c4c595fb42e03205c96dd93fbd723689c1` |
| `split_smartcapture_split_module.apk` | `7efc87c9ebe57d02170218e8173575387457c19857d8d8d8156d003e67ce3b52` |
| `split_smartzoom_split_module.apk` | `53e5ba1afd44683df746218a37671cff2378d09c7276025845901ad6ac0a2c82` |
| `split_startup_jni_split_module.apk` | `f1840c2c6e939a5d07817a8e8b6d365924e74b69a82b114f3789ed44f5fd6d46` |

| Pixel 7 (`p22` / `panther`) split | SHA-256 |
| --- | --- |
| `split_deeprestore_split_module_p22.apk` | `4ae635d804ffff2044310b7d02ce20a0f182d59c1b311e7a75f05ee5adb8e0d2` |
| `split_geo_cal_split_module_panther.apk` | `c5c3e3c6c6d231c20d404f22213749f81ba73d707afe090e7f3ad482f996ae96` |
| `split_hdrplus_asset_module_p22.apk` | `c9ab5290f75895a4b461c8931e6f4931b2ed6a027e086a6abb2d332d75cd09df` |
| `split_l2l_assets_p22_module.apk` | `cb17e9fde7f881959fb1b666f0634a1f8685b72cd349e209e9e01cc78eaeeb85` |
| `split_motion_blur_asset_module_p22.apk` | `a6047fe4ad175ff3b8ddbda48c5463f1e61324d8bdf10f8596ebddb94cb4f0bc` |
| `split_portrait_asset_module_p22.apk` | `4a3b8b2ac94c71c0b9fc92ae216936f954d5af6e879df18901fb461714028509` |
| `split_roi_tracking_asset_module_p22.apk` | `fc7cd49329a4bd9aab1d5e05fe1ef067165e90ab8ff84d11b6e4d886c2e5d432` |

| Pixel 8 Pro (`p23` / `husky`) split | SHA-256 |
| --- | --- |
| `split_deeprestore_split_module_p23.apk` | `3dd36757c4ba84fb8945ef24ebaf914df1b2ec641510439a7c203111c41250f7` |
| `split_geo_cal_split_module_husky.apk` | `d5f0b2dd9a523ad8914b70d2f8abb19cf9e4b70fa756e907928f2c532e3b3cf4` |
| `split_hdrplus_asset_module_p23.apk` | `6a89f7e1c8050cd9d16963438cf7341b4a8d5cb3f1a904c43c43137f039a65ef` |
| `split_l2l_assets_p23_module.apk` | `aa51db2b46feec3c5dc6d1f1edd3368f5edacbaddef00906f7dc63cccdf81b91` |
| `split_motion_blur_asset_module_p23.apk` | `b359829534400d0228ad500ca201f50400fac8d75ee3253af411e860527f4ebb` |
| `split_portrait_asset_module_p23.apk` | `5d6cd31e3e68d3b6428f8f35abdc1dbaea90de36fb67db901c131b99ac22260c` |
| `split_roi_tracking_asset_module_p23.apk` | `10bd29abe2288d9e9a66ffef4202e08fbb2e78b45116661087ca77d10ea8ec9a` |

The two base APK hashes differ because their packaged variant metadata differs. Their concatenated
`classes*.dex` payloads were nevertheless byte-identical (SHA-256
`478be63de4638b21c4b2b8dce4d89f1231d69070dfde949dd1084bc7a0ae7a8b`), and their complete
`string`, `plurals`, and `integer` resource-table sections normalized from `aapt2 dump resources`
were byte-identical (SHA-256
`c3a195896d94cc024a891ef05d326163e4afb2d5c650a0aea3f2a93f6cd45d6b`). Thus the selector
differences between the standard and telephoto Lenswake templates are Lenswake model policy, not a
difference in these two inspected base-APK code/resource payloads.

## Exact inspection commands

The following commands describe the executed read-only procedure; each command was run once per
explicitly selected serial where applicable:

```bash
adb devices -l

adb -s "$PIXEL_SERIAL" shell getprop ro.product.model
adb -s "$PIXEL_SERIAL" shell getprop ro.product.device
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.release
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.sdk
adb -s "$PIXEL_SERIAL" shell getprop ro.build.id
adb -s "$PIXEL_SERIAL" shell getprop ro.build.version.incremental
adb -s "$PIXEL_SERIAL" shell getprop ro.build.fingerprint
adb -s "$PIXEL_SERIAL" shell getprop persist.sys.locale
adb -s "$PIXEL_SERIAL" shell settings get system system_locales
adb -s "$PIXEL_SERIAL" shell settings get system font_scale
adb -s "$PIXEL_SERIAL" shell wm size
adb -s "$PIXEL_SERIAL" shell wm density
adb -s "$PIXEL_SERIAL" shell dumpsys input

adb -s "$PIXEL_SERIAL" shell dumpsys package com.google.android.GoogleCamera
adb -s "$PIXEL_SERIAL" shell pm path com.google.android.GoogleCamera
adb -s "$PIXEL_SERIAL" shell \
  'for apk_path in $(pm path com.google.android.GoogleCamera | cut -d: -f2); do sha256sum "$apk_path"; done'
adb -s "$PIXEL_SERIAL" shell \
  'for path in $(pm path com.google.android.GoogleCamera | cut -d: -f2); do sha256sum "$path"; done' |
  sed 's#  .*\/\([^/]*\.apk\)$#  \1#' | LC_ALL=C sort | shasum -a 256

adb -s "$PIXEL_SERIAL" pull "$BASE_APK_PATH" "$TEMP_DIR/device-base.apk"
aapt2 dump badging "$TEMP_DIR/device-base.apk"
apksigner verify --verbose --print-certs "$TEMP_DIR/device-base.apk"
aapt2 dump resources "$TEMP_DIR/device-base.apk"
unzip -p "$TEMP_DIR/device-base.apk" 'classes*.dex' | strings -a
unzip -Z1 "$TEMP_DIR/device-base.apk" | rg '^classes.*\.dex$' |
  while IFS= read -r dex; do unzip -p "$TEMP_DIR/device-base.apk" "$dex"; done |
  shasum -a 256
aapt2 dump resources "$TEMP_DIR/device-base.apk" |
  perl -ne '$keep = /^  type (string|plurals|integer) / ? 1 : /^  type / ? 0 : $keep; print if $keep' |
  shasum -a 256
jadx --single-class gtm --single-class-output "$TEMP_DIR/gtm.java" \
  "$TEMP_DIR/device-base.apk"
```

Concrete results were the environment, package, split, signer, and hash values above. `aapt2`
confirmed package/version/SDK metadata and the exact resource values described below. `apksigner`
reported `Verifies`, v3 true, one signer, and the certificate digest above. The DEX string scan found
the `ComposeShutter` code symbol but did not establish an Accessibility view ID or bind it to a node.
JADX decompilation of the obfuscated FPS option presenter `gtm` produced a stronger internal mapping:
method `s(lui, Resources)` builds the visible 60-FPS label with `String.format("%d", fps_60)`, hence
`60`, while method `r(lui, Resources)` builds the content description with `fps_desc` and `fps_60`,
hence `60 FPS`. This is static implementation evidence, not a live Accessibility observation.

## Selector-by-selector mapping

All catalog selectors are package-scoped to `com.google.android.GoogleCamera`. Scores are included
because they are part of the authorization boundary. “APK support” below means only that the value
or formatting resource exists in both inspected active base APKs.

### Video 4K / 60 FPS

| Contract | Exact schema-v5 selector | Static APK result | Official result and limitation |
| --- | --- | --- | --- |
| Select 4K | action: description `4K Ultra HD`, text `4K (Ultra HD)`, clickable, minimum 90 | Exact default-English values exist as `video_res_4k_desc` and `video_res_4k` | [Pixel Camera Help](https://support.google.com/pixelcamera/answer/7064897?hl=en-GB) documents Video settings -> Resolution -> 4K |
| 4K active | signal: same description/text plus `expectedChecked=true`, non-clickable, minimum 105 | Strings exist; checked state and node association are not encoded by the resource table | Official help documents the setting, not its Accessibility checked state |
| Select 60 FPS | version 2 action: description `60 FPS`, text `60`, clickable, minimum 90 | `gtm.r` builds description `60 FPS`; `gtm.s` builds visible text `60` | Official help documents Video settings -> Frames/sec -> 60fps |
| 60 FPS active | version 2 signal: description `60 FPS`, text `60`, `expectedChecked=true`, non-clickable, minimum 105 | Description/text mapping is confirmed; checked state and node association remain unproved | Product support does not prove the exact selector or every mode/lens combination |

Google documents 4K up to 60 FPS and the Pixel 7/Pixel 8 Pro hardware specifications include 4K
60 FPS, but those documents explicitly allow device-dependent steps/features. They do not establish
that 4K/60 is simultaneously available on every lens, in Night Sight Time Lapse, or in every thermal,
storage, stabilisation, HDR, or Video Boost state.

The `gtm` mapping exposed a concrete defect in the selector definition at inspected commit
`1be1b902...`: action and active signal used `text=60 FPS`. The accompanying remediation uses
`text=60` while retaining `contentDescription=60 FPS`, advances both standard and telephoto template
references from version 1 to version 2, and adds a source contract test for the version-pinned
4K/60, speed, lens, and Night Sight values. This record deliberately does not assign its own commit
identity.

### Time Lapse mode and all five speeds

| Contract | Exact schema-v5 selector | Static APK result | Official result and limitation |
| --- | --- | --- | --- |
| Select Time Lapse | two action candidates on `id/mode_chip_text`: description `Switch to Time Lapse Mode` or `Time Lapse`, text `Time Lapse`, non-clickable, minimum 190 | `id/mode_chip_text`, `mode_cheetah_desc`, and `mode_timelapse` exist | Official help documents Video -> Time Lapse, not this node ID/selection behavior |
| Time Lapse active | signal: `id/mode_chip_text`, description/text `Time Lapse`, `expectedSelected=true`, region `(0.35,0.80)-(0.65,0.90)`, minimum 215 | ID/text exist; selected state and normalized region are unproved | No official Accessibility contract |
| Open speed control | action: description `Time Lapse control`, non-clickable, minimum 60 | Exact `timelapse_entrypoint_desc` value exists | Official help says to tap Auto at bottom right, not this description |
| Speed picker open | signal: any of the five speed descriptions below, non-clickable, minimum 60 | Auto description and manual plural format exist; picker visibility/uniqueness is unproved | Official help documents a speed picker workflow only |
| Auto action / active | description `Time Lapse auto speed`, text `Auto`; action clickable minimum 90, signal selected/non-clickable minimum 105 | Exact description and text exist | Official help explicitly documents Auto |
| 5x action / active | description `Time Lapse 5 times speed`, text `5×`; action clickable minimum 90, signal selected/non-clickable minimum 105 | Manual description is formatted from plural `Time Lapse %d times speed`; label is formatted from `%d×`; a `tooltip_msg_timelapse_record_speed_5x` resource exists | Official help explicitly gives 5x as an example |
| 10x action / active | description `Time Lapse 10 times speed`, text `10×`; action clickable minimum 90, signal selected/non-clickable minimum 105 | Same generic formats; `tooltip_msg_timelapse_record_speed_10x` exists | The inspected official help page does not enumerate 10x |
| 30x action / active | description `Time Lapse 30 times speed`, text `30×`; action clickable minimum 90, signal selected/non-clickable minimum 105 | Same generic formats; `tooltip_msg_timelapse_record_speed_30x` exists | The inspected official help page does not enumerate 30x |
| 120x action | description `Time Lapse 120 times speed`, text `120×`, clickable, minimum 100 | Same generic formats; `tooltip_msg_timelapse_record_speed_120x` exists | Official help explicitly gives 120x as an example |
| 120x active | primary: same description/text + selected, non-clickable; fallback: text `120×` in region `(0.65,0.80)-(1.0,1.0)`; set minimum 40 | Formats exist; selected state, fallback region, and ambiguity behavior are unproved | Official help does not define either state signal |

The APK evidence for 5x/10x/30x/120x is stronger than an invented label because the installed
package contains the manual formatting resources and speed-specific tooltip resource names. It is
still weaker than a runtime node observation: static inspection did not prove that all four numeric
values appear in the picker on either device or that the formatted descriptions are exposed through
Accessibility.

### Lenses

| Contract | Exact schema-v5 selector | Static APK result | Official result and limitation |
| --- | --- | --- | --- |
| Select rear main | action: resource ID `zoom_toggle_1×`, text `1×`, non-clickable, minimum 130 | The base APK exposes `Wide`/`W` resources, but static inspection did **not** find this dynamic ID/text binding | Google documents a Wide lens; it does not document this selector |
| Rear main active | signal: `expectedChecked=true`, `requiresClickable=true`, region `(0.40,0.60)-(0.50,0.68)`, minimum 35; no resource/text/description | No static resource can substantiate this geometry/state-only fingerprint | No official Accessibility or layout guarantee |
| Select rear ultrawide | action: description `Ultrawide`, non-clickable, minimum 60 | Exact `lens_toggle_ultrawide_content_desc`; label `UW` also exists | Google documents an Ultrawide lens, not mode-specific control availability |
| Rear ultrawide active | signal: description `Ultrawide`, `expectedChecked=true`, non-clickable, minimum 75 | Exact string exists; checked state is unproved | No official Accessibility contract |
| Select rear telephoto | action: description `Tele`, non-clickable, minimum 60 | Exact `lens_toggle_tele_content_desc`; label `T` also exists | Google documents the Pixel 8 Pro Tele lens, not its availability in every capture mode |
| Rear telephoto active | signal: description `Tele`, `expectedChecked=true`, non-clickable, minimum 75 | Exact string exists; checked state is unproved | No official Accessibility contract |
| Select front | action: description `Switch to front camera`, clickable, minimum 60 | Exact `camera_id_back_desc` value exists | Google documents camera switching, not this exact node contract |
| Front active | signal: description `Switch to back camera`, non-clickable, minimum 60 | Exact `camera_id_front_desc` value exists | The inverse label is a plausible state indicator but is not an official postcondition |

The standard template removes the telephoto action and active signal. The telephoto template retains
them. This is a Lenswake registry policy, not proof that every model assigned the telephoto template
offers the same Pixel Camera control in each Video/Time Lapse/Night Sight configuration. No
coordinate gesture is copied into a derived profile.

The rear-main active signal is the least semantically grounded schema-v5 lens signal: it has no
resource ID, text, or description and depends on checked/clickable state plus a normalized region.
The static package inspection performed here does not validate it.

### Night Sight Time Lapse

| Contract | Exact schema-v5 selector | Static APK result | Official result and limitation |
| --- | --- | --- | --- |
| Select Night Sight Time Lapse | action: text `Night Sight`, clickable, minimum 30 | Several `Night Sight` resources exist, including the Time Lapse feature resources; the generic text is not unique package-wide | [Pixel Camera Help](https://support.google.com/pixelcamera/answer/7064897?hl=en-GB) documents Time Lapse -> Settings -> More light -> Night Sight |
| Night Sight Time Lapse active | signal: text `Night Sight auto enabled. Learn more`, non-clickable, minimum 30 | Exact `nightlapse_entered_hint` value exists | Google's public docs do not define this string as a durable selected-state API |

Google's [December 2023 Pixel Feature Drop](https://blog.google/products-and-platforms/devices/pixel/pixel-feature-drop-december-2023/)
introduced Night Sight in Timelapse specifically for Pixel 8 and Pixel 8 Pro. The current installed
APK containing the resources on Pixel 7 does not prove Pixel 7 feature availability. Nor does one
shared semantic template prove availability across the full Pixel 6-10a registry. Lenswake must find
the fresh active-mode signal and require an exact-combination rehearsal; absence remains an explicit
unsupported result.

### Recording and stopped-state signals used by these captures

| Contract | Exact schema-v5 selector | Static APK result | Limitation |
| --- | --- | --- | --- |
| Start/stop Video | resource ID `ComposeShutter`, descriptions `Start video` / `Stop video`, clickable, minimum 170 | Exact descriptions exist; DEX contains the `ComposeShutter` code symbol | No static proof that this symbol is the reported Accessibility view ID |
| Start/stop Time Lapse and Night Sight Time Lapse | resource ID `ComposeShutter`, descriptions `Start time lapse` / `Stop time lapse`, clickable, minimum 170 | Exact descriptions and code symbol exist | Same limitation; Night Sight reuses the Time Lapse descriptions in the catalog |
| Recording active | `ComposeShutter` plus either stop description, non-clickable, set minimum 160 | Strings/symbol exist | Node visibility, uniqueness, and recording postcondition are unproved |
| Not recording | `ComposeShutter` plus `Take photo`, `Start video`, or `Start time lapse`, set minimum 160 | Exact strings/symbol exist | This is a candidate UI state, not saved-media or capture-success proof |

Official Pixel Camera Help documents tapping Record and Stop, but dispatch is not success. Lenswake
still requires the recording/stopped state transition and, after STOP, a new unambiguous published
Pixel Camera-owned video with positive size and duration.

## System-build admission provenance

On 2026-08-12 the official [Google full OTA image matrix](https://developers.google.com/android/ota)
listed these latest unsuffixed global Android 17 releases for the supported cohorts:

- Pixel 6/6 Pro/6a and Pixel 7/7 Pro/7a: `CP2A.260705.006` (July 2026);
- Pixel 8/8 Pro/8a, Pixel 9/9 Pro/9 Pro XL/9a, and Pixel 10/10 Pro/10 Pro XL/10a:
  `CP2A.260805.005` (August 2026).

The source allowlist admits July for every supported model and August only for the Pixel 8-10a
cohort; the July overlap keeps the observed Pixel 8 Pro stable rollout environment admissible. It
requires an exact product/device codename pair and rejects all unlisted IDs. The connected Pixel 7
beta fingerprint is therefore package-inspection evidence only and cannot produce an installable
exact profile under current source.

The allowlist is a dated local contract check, not cryptographic attestation. A hostile custom image
could spoof public properties; trusted attestation would be a separate design.

## Evidence boundary and remaining gates

Confirmed by this record:

- the exact schema-v5 selector contract inspected at Lenswake commit `1be1b902...`, including the
  then-present 60-FPS text mismatch;
- the accompanying remediation to selector-template version 2, without a self-referential commit claim;
- exact read-only environment and installed split identity for the two connected devices;
- Pixel Camera base-APK metadata, v3 signer, and relevant English resources for versionCode
  `69481630`;
- official product documentation for 4K/60, Time Lapse including Auto/5x/120x examples, camera
  lenses/switching, and Night Sight Time Lapse on Pixel 8/8 Pro.

Not confirmed by this record:

- any current Pixel Camera Accessibility tree, resource-ID exposure, parent actionability,
  `selected`/`checked` values, bounds, uniqueness, or post-action state transition;
- the catalog's `video_supermode` selector (not part of the requested mapping but still used to enter
  Video), dynamic `zoom_toggle_1×` ID, `ComposeShutter` Accessibility ID, or rear-main region signal;
- 10x/30x presence in the live speed picker, despite version-pinned formatting and tooltip resources;
- 4K/60 or any lens/speed/Night Sight combination availability on either connected device;
- any runtime claim on the unsupported Pixel 7 beta build;
- selector validity on the other 15 registry models, another locale/display/font scale/orientation,
  another Pixel Camera split/version/signer, or another system build;
- Lenswake APK identity, physical rehearsal, saved-media proof, locked/Doze/reboot scenarios,
  Android instrumentation, hosted CI, or the same signed release APK on both certification targets.

Consequently both version 2 templates remain `STATIC_RESOURCE_TEMPLATE`, `EXPERIMENTAL`,
`NEEDS_REHEARSAL`, with `verifiedAt=null`. Static package resources and a source contract test must
not be presented as physical certification. Every exact capture combination remains unavailable to
schedules until its current exact-environment rehearsal verifies configuration, recording, stopping,
and saved media.
