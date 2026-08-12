#!/usr/bin/env bash
set -euo pipefail

apk="${1:?Usage: verify-release-apk.sh APK [expected-version-name] [expected-version-code]}"
expected_version_name="${2:-}"
expected_version_code="${3:-}"

if [[ -z "$expected_version_name" || -z "$expected_version_code" ]]; then
  eval "$(scripts/ci/read-version.sh)"
  expected_version_name="${expected_version_name:-$VERSION_NAME}"
  expected_version_code="${expected_version_code:-$VERSION_CODE}"
fi

expected_fingerprint="$(tr -d '[:space:]:' < release-signing-certificate.sha256 | tr '[:lower:]' '[:upper:]')"
android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
apkanalyzer_bin="${android_sdk:+$android_sdk/cmdline-tools/latest/bin/apkanalyzer}"
if [[ -z "$apkanalyzer_bin" || ! -x "$apkanalyzer_bin" ]]; then
  apkanalyzer_bin="$(command -v apkanalyzer)"
fi
actual_fingerprint="$(
  apksigner verify --print-certs "$apk" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
    head -n 1 |
    tr -d '[:space:]:' |
    tr '[:lower:]' '[:upper:]'
)"

[[ "$actual_fingerprint" == "$expected_fingerprint" ]] || {
  echo "Unexpected signing certificate: $actual_fingerprint" >&2
  exit 1
}

application_id="$("$apkanalyzer_bin" manifest application-id "$apk")"
version_name="$("$apkanalyzer_bin" manifest version-name "$apk")"
version_code="$("$apkanalyzer_bin" manifest version-code "$apk")"

[[ "$application_id" == "dev.po4yka.lenswake" ]] || {
  echo "Unexpected application ID: $application_id" >&2
  exit 1
}
[[ "$version_name" == "$expected_version_name" ]] || {
  echo "Unexpected versionName: $version_name" >&2
  exit 1
}
[[ "$version_code" == "$expected_version_code" ]] || {
  echo "Unexpected versionCode: $version_code" >&2
  exit 1
}

permissions="$("$apkanalyzer_bin" manifest permissions "$apk")"
for forbidden_permission in android.permission.CAMERA android.permission.RECORD_AUDIO android.permission.INTERNET; do
  if grep -Fxq "$forbidden_permission" <<<"$permissions"; then
    echo "Release APK declares forbidden permission: $forbidden_permission" >&2
    exit 1
  fi
done

printf 'Verified %s: %s %s (%s), certificate %s\n' \
  "$apk" "$application_id" "$version_name" "$version_code" "$actual_fingerprint"
