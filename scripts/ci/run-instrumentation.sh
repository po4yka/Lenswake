#!/usr/bin/env bash
set -euo pipefail

deadline=$((SECONDS + 180))
while (( SECONDS < deadline )); do
  boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  package_ready="$(adb shell cmd package list packages android 2>/dev/null | tr -d '\r')"
  storage_ready="$(adb shell sm list-volumes all 2>/dev/null | tr -d '\r')"
  if [[ "$boot_completed" == "1" ]] &&
    [[ "$package_ready" == *"package:android"* ]] &&
    [[ "$storage_ready" == *"mounted"* ]] &&
    adb shell test -d /sdcard/Android 2>/dev/null; then
    exec ./gradlew \
      :data:connectedDebugAndroidTest \
      :app:connectedDebugAndroidTest \
      --no-parallel \
      --console=plain
  fi
  sleep 2
done

echo "Android framework did not become ready for instrumentation within 180 seconds." >&2
adb shell getprop sys.boot_completed >&2 || true
adb shell cmd package list packages android >&2 || true
adb shell sm list-volumes all >&2 || true
exit 1
