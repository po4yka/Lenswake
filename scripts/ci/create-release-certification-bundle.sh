#!/usr/bin/env bash
set -euo pipefail

acceptance="${1:?Usage: create-release-certification-bundle.sh ACCEPTANCE OUTPUT KEYSTORE ALIAS}"
output="${2:?Missing output bundle path}"
keystore="${3:?Missing release keystore path}"
alias="${4:?Missing release key alias}"
certificate_fingerprint_file="${5:-release-signing-certificate.sha256}"

: "${RELEASE_STORE_PASSWORD:?RELEASE_STORE_PASSWORD is required}"
: "${RELEASE_KEY_PASSWORD:?RELEASE_KEY_PASSWORD is required}"

[[ -f "$acceptance" && -f "$keystore" ]] || {
  echo "Acceptance record and release keystore must exist" >&2
  exit 1
}
[[ "$(sed -n 's/^schemaVersion=//p' "$acceptance")" == "2" ]] || {
  echo "Certification bundle requires physical acceptance schema 2" >&2
  exit 1
}

[[ ! -e "$output" ]] || {
  echo "Refusing to overwrite an existing certification bundle: $output" >&2
  exit 1
}
jar --create --file "$output" -C "$(dirname "$acceptance")" "$(basename "$acceptance")"
jarsigner \
  -keystore "$keystore" \
  -storepass:env RELEASE_STORE_PASSWORD \
  -keypass:env RELEASE_KEY_PASSWORD \
  -digestalg SHA-256 \
  "$output" "$alias" >/dev/null
scripts/ci/verify-release-certification-bundle.sh \
  "$output" "$acceptance" "$certificate_fingerprint_file" >/dev/null

echo "Created signed release certification bundle: $output"
