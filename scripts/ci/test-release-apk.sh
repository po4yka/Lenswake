#!/usr/bin/env bash
set -euo pipefail

apk="${1:?Usage: test-release-apk.sh APK VERSION_NAME VERSION_CODE}"
version_name="${2:?Missing versionName}"
version_code="${3:?Missing versionCode}"
apk="$(cd -- "$(dirname -- "$apk")" && pwd)/$(basename -- "$apk")"
verifier="$(pwd)/scripts/ci/verify-release-apk.sh"

# Exercise the installed SDK's real apksigner output against the actual distribution APK.
"$verifier" "$apk" "$version_name" "$version_code"

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT
printf '%064d\n' 0 >"$test_root/release-signing-certificate.sha256"
if (cd "$test_root" && "$verifier" "$apk" "$version_name" "$version_code") \
  >"$test_root/wrong-certificate.log" 2>&1; then
  echo "Release verifier accepted an unexpected signing certificate" >&2
  exit 1
fi
grep -Fq 'Unexpected signing certificate:' "$test_root/wrong-certificate.log" || {
  cat "$test_root/wrong-certificate.log" >&2
  exit 1
}

cp release-signing-certificate.sha256 "$test_root/release-signing-certificate.sha256"
printf 'Invalid APK\n' >"$test_root/invalid.apk"
if (cd "$test_root" && "$verifier" "$test_root/invalid.apk" "$version_name" "$version_code") \
  >"$test_root/invalid-apk.log" 2>&1; then
  echo "Release verifier accepted an invalid APK" >&2
  exit 1
fi

echo "Release APK verification tests passed"
