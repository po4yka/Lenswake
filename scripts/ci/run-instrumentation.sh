#!/usr/bin/env bash
set -euo pipefail

adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

deadline=$((SECONDS + 240))
healthy_checks=0
while (( SECONDS < deadline )); do
  boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  credential_storage="$(adb shell getprop sys.user.0.ce_available 2>/dev/null | tr -d '\r' || true)"
  package_ready="$(adb shell pm path android 2>/dev/null | tr -d '\r' || true)"
  activity_ready="$(adb shell cmd activity get-current-user 2>/dev/null | tr -d '\r' || true)"
  storage_ready="$(adb shell sm list-volumes all 2>/dev/null | tr -d '\r' || true)"
  if [[ "$boot_completed" == "1" ]] &&
    [[ "$credential_storage" == "true" ]] &&
    [[ "$package_ready" == package:* ]] &&
    [[ "$activity_ready" =~ ^[0-9]+$ ]] &&
    [[ "$storage_ready" == *"mounted"* ]] &&
    adb shell test -d /sdcard/Android 2>/dev/null; then
    healthy_checks=$((healthy_checks + 1))
    if (( healthy_checks >= 5 )); then
      ./gradlew :data:connectedDebugAndroidTest --no-parallel --console=plain
      exec ./gradlew :app:connectedDebugAndroidTest --no-parallel --console=plain
    fi
  else
    healthy_checks=0
  fi
  sleep 2
done

echo "Android framework did not remain ready for instrumentation within 240 seconds." >&2
adb shell getprop sys.boot_completed >&2 || true
adb shell getprop sys.user.0.ce_available >&2 || true
adb shell pm path android >&2 || true
adb shell cmd activity get-current-user >&2 || true
adb shell sm list-volumes all >&2 || true
exit 1
