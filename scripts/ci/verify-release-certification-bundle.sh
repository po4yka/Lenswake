#!/usr/bin/env bash
set -euo pipefail

bundle="${1:?Usage: verify-release-certification-bundle.sh BUNDLE ACCEPTANCE}"
acceptance="${2:?Missing physical acceptance record}"
certificate_fingerprint_file="${3:-release-signing-certificate.sha256}"

[[ -f "$bundle" && -f "$acceptance" ]] || {
  echo "Certification bundle and acceptance record must exist" >&2
  exit 1
}

jarsigner -verify "$bundle" >/dev/null
payloads="$(jar --list --file "$bundle" | sed '/\/$/d; /^META-INF\//d')"
[[ "$payloads" == "PHYSICAL-ACCEPTANCE.txt" ]] || {
  echo "Certification bundle contains unexpected payload entries" >&2
  exit 1
}

temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
(
  cd "$temporary"
  jar --extract --file "$bundle" PHYSICAL-ACCEPTANCE.txt
)
cmp --silent "$acceptance" "$temporary/PHYSICAL-ACCEPTANCE.txt" || {
  echo "Certification bundle receipt differs from physical acceptance" >&2
  exit 1
}

expected_fingerprint="$(tr -d '[:space:]:' <"$certificate_fingerprint_file" | tr '[:upper:]' '[:lower:]')"
actual_fingerprint="$(
  LC_ALL=C keytool -printcert -jarfile "$bundle" |
    sed -n 's/^[[:space:]]*SHA256: //p' |
    head -n 1 |
    tr -d ':' |
    tr '[:upper:]' '[:lower:]'
)"
[[ "$actual_fingerprint" == "$expected_fingerprint" ]] || {
  echo "Certification bundle has an unexpected signing certificate" >&2
  exit 1
}

echo "Verified release certification bundle: $bundle"
