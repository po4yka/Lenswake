# Pixel Camera dialog recovery evidence

> [!IMPORTANT]
> This note records source-derived candidate dialog selectors from one Pixel 7 Pixel Camera package
> variant. Matching `versionCode` does not prove binary identity, Accessibility exposure, or behavior
> on the supported Pixel 8 Pro. Physical Pixel 8 Pro acceptance remains open.

Date: 2026-08-11

## Environment

- Connected device: Google Pixel 7 (`panther`), Android 17.
- Installed package: `com.google.android.GoogleCamera`.
- Pixel Camera version: `10.4.117.936816638.14` (`versionCode=69481630`).
- Lenswake implementation: `e5390c0`.

The bundled production profile remains scoped to its exact Pixel 8 Pro environment and requires a
successful rehearsal. The Pixel 7 inspection below provides candidate strings and call-site behavior
for one package variant with the same reported Camera version. Static resource/code inspection does
not establish the Pixel 8 Pro Accessibility contract or end-to-end behavior.

## Reproducible inspection

The installed base APK was copied without modifying the device:

```text
adb shell pm path com.google.android.GoogleCamera
adb pull /data/app/.../com.google.android.GoogleCamera.../base.apk
apktool d -s -f -o <resources> base.apk
jadx --no-res -d <sources> base.apk
```

The decoded resources and call sites show these typed dialogs:

| Kind | Presence text (`en-US`) | Recovery contract |
|---|---|---|
| `VIDEO_DURATION_LIMIT_REACHED` | `Video reached the duration limit.` | Non-cancelable Material alert with positive `OK` action |
| `VIDEO_FILE_SIZE_LIMIT_REACHED` | `Video reached the 100 GB size limit.` | Non-cancelable Material alert with positive `OK` action |
| `VIDEO_STORAGE_EXHAUSTED` | `There is not enough storage available to continue capturing. You can free up space in the Files app.` | Terminal for unattended capture; no automatic action |
| `CAMERA_DISABLED` | `Your organization doesn't allow you to use Camera. Contact your IT admin for more info.` | Terminal policy failure; no automatic action |

`lka.i(...)` constructs the two limit dialogs with `Theme_Camera_MaterialAlertDialog`, the typed
body string, and `dialog_ok`. `lka.m(...)` makes them non-cancelable. Storage exhaustion offers
`Free up space` and `Dismiss`, but dismissing it cannot make capture ready, so the production rule
fails closed instead of clicking either action. Camera-disabled offers `Close`, which is likewise
not a recovery action.

## Automation consequence

Presence uses `android:id/message`, the exact body text, and `android.widget.TextView`. Recoverable
limit dialogs use the standard positive-button ID `android:id/button1`, the exact `OK` text, and
`android.widget.Button`; the adapter rechecks the typed presence in a fresh snapshot before
resolving that target. A lower-priority `UNKNOWN` rule recognizes any other Pixel Camera standard
alert message through `android:id/message` plus `android.widget.TextView`. Unknown dialogs and
terminal typed dialogs have no recovery target and produce `UNEXPECTED_CAMERA_DIALOG` without a
click.

The remaining physical acceptance check is to induce each recoverable dialog on the supported
Pixel 8 Pro, capture its accessibility tree, run schedule-bound rehearsal, and confirm the dialog
disappears after exactly one dispatch.
