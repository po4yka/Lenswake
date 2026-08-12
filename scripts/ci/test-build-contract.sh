#!/usr/bin/env bash
set -euo pipefail

scripts/ci/read-version.sh >/dev/null
log_file="$(mktemp)"
version_backup="$(mktemp)"
cp version.properties "$version_backup"
trap 'cp "$version_backup" version.properties; rm -f "$log_file" "$version_backup"' EXIT

printf 'versionName=1.2.3+build.7\nversionCode=1\n' >version.properties
./gradlew help --console=plain >"$log_file" 2>&1

printf 'versionName=01.2.3\nversionCode=1\n' >version.properties
if ./gradlew help --console=plain >"$log_file" 2>&1; then
  echo "Invalid SemVer versionName unexpectedly succeeded" >&2
  exit 1
fi
grep -Fq "versionName must be a SemVer 2.0.0 value" "$log_file" || {
  echo "Invalid versionName failed for an unexpected reason" >&2
  sed -n '1,160p' "$log_file" >&2
  exit 1
}

cp "$version_backup" version.properties

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
