#!/usr/bin/env bash
set -euo pipefail

scripts/ci/read-version.sh >/dev/null
log_file="$(mktemp)"
trap 'rm -f "$log_file"' EXIT

if ./gradlew help -Plenswake.release.storeFile=/tmp/incomplete-lenswake-release.jks \
  --console=plain >"$log_file" 2>&1; then
  echo "Partial release signing configuration unexpectedly succeeded" >&2
  exit 1
fi

grep -Fq "Release signing is partially configured" "$log_file" || {
  echo "Partial release signing failed for an unexpected reason" >&2
  sed -n '1,160p' "$log_file" >&2
  exit 1
}

echo "Build contract tests passed"
